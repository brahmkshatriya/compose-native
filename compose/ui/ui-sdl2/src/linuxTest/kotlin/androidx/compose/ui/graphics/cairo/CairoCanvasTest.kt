/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.graphics.cairo

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import cairo.kc_create
import cairo.kc_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret

@OptIn(InternalComposeUiApi::class)
class CairoCanvasTest {
    @Test
    fun graphicsLayerRetainsRasterForPropertyOnlyChanges() {
        PlatformGraphicsRegistry.register(CairoGraphics)
        val graphicsContext = CairoGraphics.createGraphicsContext()
        val layer = graphicsContext.createGraphicsLayer()
        val target = CairoSurface(32, 32)
        val context = checkNotNull(kc_create(target.handle))
        var rasterCount = 0

        try {
            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(16, 16)) {
                rasterCount++
                drawRect(Color.White)
            }
            val scope = CanvasDrawScope()
            fun drawLayerFrame() {
                scope.draw(
                    Density(1f),
                    LayoutDirection.Ltr,
                    CairoCanvas(context),
                    Size(32f, 32f),
                ) {
                    drawLayer(layer)
                }
            }

            drawLayerFrame()
            layer.translationX = 4f
            layer.scaleX = 0.75f
            layer.rotationZ = 15f
            layer.alpha = 0.5f
            drawLayerFrame()

            assertEquals(1, rasterCount)

            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(16, 16)) {
                rasterCount++
                drawRect(Color.White)
            }
            drawLayerFrame()
            assertEquals(2, rasterCount)
        } finally {
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsContext.close()
            kc_destroy(context)
            target.close()
            PlatformGraphicsRegistry.clear()
        }
    }


    @Test
    fun currentTransformMapsLocalCoordinatesIntoTargetSpace() {
        val surface = CairoSurface(32, 32)
        val context = checkNotNull(kc_create(surface.handle))

        try {
            val canvas = CairoCanvas(context)
            canvas.translate(7f, 9f)
            canvas.scale(2f, 3f)

            assertEquals(Offset(9f, 15f), canvas.currentTransform().map(Offset(1f, 2f)))
        } finally {
            kc_destroy(context)
            surface.close()
        }
    }

    @Test
    fun graphicsLayerContainingExternalBoundaryFlattensIntoParent() {
        PlatformGraphicsRegistry.register(CairoGraphics)
        val graphicsContext = CairoGraphics.createGraphicsContext()
        val layer = graphicsContext.createGraphicsLayer().also {
            it.rotationZ = 2f
        }
        val target = CairoSurface(16, 16)
        val context = checkNotNull(kc_create(target.handle))
        var rasterCount = 0
        var rootBoundaryCount = 0
        val targetCanvas = CairoCanvas(context) { rootBoundaryCount++ }

        try {
            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(16, 16)) {
                rasterCount++
                assertTrue((drawContext.canvas as CairoCanvas).markExternalBoundary())
                drawRect(Color.White)
            }
            val scope = CanvasDrawScope()
            fun drawLayerFrame() {
                scope.draw(
                    Density(1f),
                    LayoutDirection.Ltr,
                    targetCanvas,
                    Size(16f, 16f),
                ) {
                    drawLayer(layer)
                }
            }

            // Discovery rasterizes once, then immediately replays directly into the parent root.
            drawLayerFrame()
            assertEquals(2, rasterCount)
            assertEquals(1, rootBoundaryCount)

            // Subsequent frames stay on the direct path.
            drawLayerFrame()
            assertEquals(3, rasterCount)
            assertEquals(2, rootBoundaryCount)
        } finally {
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsContext.close()
            kc_destroy(context)
            target.close()
            PlatformGraphicsRegistry.clear()
        }
    }

    @Test
    fun clipOnlyLayerDoesNotCoverExternalRootCutout() {
        PlatformGraphicsRegistry.register(CairoGraphics)
        val graphicsContext = CairoGraphics.createGraphicsContext()
        val layer = graphicsContext.createGraphicsLayer()
        val target = CairoSurface(16, 16)
        target.clear()
        val context = checkNotNull(kc_create(target.handle))
        val targetCanvas = CairoCanvas(context)
        val cutout =
            CairoGraphics.createPath().apply {
                addRect(Rect(4f, 4f, 12f, 12f))
            }

        try {
            layer.clip = true
            layer.setRectOutline(size = Size(16f, 16f))
            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(16, 16)) {
                // Models an opaque ancestor such as Material Surface/Scaffold. NativeView clears
                // the actual root canvas even while this display-list block is being replayed.
                drawRect(Color.White)
                targetCanvas.clearInteropPathInRoot(cutout)
            }

            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                targetCanvas,
                Size(16f, 16f),
            ) {
                drawLayer(layer)
            }
            target.flush()

            val pixels = target.data.reinterpret<IntVar>()
            val stride = target.stride / 4
            assertEquals(-1, pixels[2 * stride + 2])
            assertEquals(0, pixels[8 * stride + 8])
        } finally {
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsContext.close()
            kc_destroy(context)
            target.close()
            PlatformGraphicsRegistry.clear()
        }
    }

    @Test
    fun rectangularClearPreservesPixelsOutsideDamage() {
        val surface = CairoSurface(16, 16)
        val context = checkNotNull(kc_create(surface.handle))

        try {
            val canvas = CairoCanvas(context)
            canvas.drawRect(
                0f,
                0f,
                16f,
                16f,
                CairoGraphics.createPaint().also { it.color = Color.White },
            )
            surface.clear(androidx.compose.ui.geometry.Rect(4f, 5f, 9f, 11f))
            surface.flush()

            val pixels = surface.data.reinterpret<IntVar>()
            val stride = surface.stride / 4
            assertEquals(-1, pixels[3 * stride + 3])
            assertEquals(0, pixels[7 * stride + 6])
            assertEquals(-1, pixels[12 * stride + 12])
        } finally {
            kc_destroy(context)
            surface.close()
        }
    }

    @Test
    fun differenceClipCutsLabelAreaOutOfOutline() {
        val surface = CairoSurface(32, 16)
        surface.clear()
        val context = checkNotNull(kc_create(surface.handle))

        try {
            val canvas = CairoCanvas(context)
            canvas.clipRect(10f, 0f, 22f, 6f, ClipOp.Difference)
            canvas.drawRect(
                0f,
                0f,
                32f,
                2f,
                CairoGraphics.createPaint().also { it.color = Color.White },
            )
            surface.flush()

            val pixels = surface.data.reinterpret<IntVar>()
            assertEquals(-1, pixels[1 * (surface.stride / 4) + 5])
            assertEquals(0, pixels[1 * (surface.stride / 4) + 16])
        } finally {
            kc_destroy(context)
            surface.close()
        }
    }

    @Test
    fun adjacentProjectedTrianglesDoNotLeaveSeams() {
        val source = CairoSurface(24, 24)
        source.clear()
        val sourceContext = checkNotNull(kc_create(source.handle))
        CairoCanvas(sourceContext)
            .drawRect(0f, 0f, 24f, 24f, CairoGraphics.createPaint().also { it.color = Color.White })
        kc_destroy(sourceContext)

        val target = CairoSurface(32, 32)
        target.clear()
        val targetContext = checkNotNull(kc_create(target.handle))

        try {
            val canvas = CairoCanvas(targetContext)
            canvas.drawSurfaceTriangle(
                source,
                Offset(0f, 0f),
                Offset(24f, 0f),
                Offset(24f, 24f),
                Offset(4f, 4f),
                Offset(28f, 4f),
                Offset(28f, 28f),
                1f,
                BlendMode.SrcOver,
            )
            canvas.drawSurfaceTriangle(
                source,
                Offset(0f, 0f),
                Offset(24f, 24f),
                Offset(0f, 24f),
                Offset(4f, 4f),
                Offset(28f, 28f),
                Offset(4f, 28f),
                1f,
                BlendMode.SrcOver,
            )
            target.flush()

            val pixels = target.data.reinterpret<IntVar>()
            assertEquals(-1, pixels[16 * (target.stride / 4) + 16])
        } finally {
            kc_destroy(targetContext)
            target.close()
            source.close()
        }
    }
}
