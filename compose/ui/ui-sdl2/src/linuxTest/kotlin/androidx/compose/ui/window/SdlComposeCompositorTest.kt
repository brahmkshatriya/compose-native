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

package androidx.compose.ui.window

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoSurface
import androidx.compose.ui.viewinterop.GpuInteropLayerCommand
import cairo.kc_create
import cairo.kc_destroy
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import androidx.compose.ui.graphics.cairo.CairoGraphics
import androidx.compose.ui.graphics.cairo.CairoLayerCompositor
import androidx.compose.ui.graphics.cairo.CairoLayerRegistration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class SdlComposeCompositorTest {
    @Test
    fun recordsFrameAndRootTextureWork() {
        val counters = SdlCompositorCounters()

        counters.onFrameStarted()
        counters.onRootTextureAllocated()
        counters.onRootUploaded(5_776 * 4)
        counters.onExternalCommands(2)
        counters.onRootCommand()
        counters.onFramePresented()

        assertEquals(
            SdlCompositorStatsSnapshot(
                startedFrames = 1,
                presentedFrames = 1,
                rootTextureAllocations = 1,
                rootUploads = 1,
                rootUploadedBytes = 23_104,
                rootCommands = 1,
                externalCommands = 2,
                createdLayers = 0,
                activeLayers = 0,
                layerContentChanges = 0,
                layerPropertyChanges = 0,
            ),
            counters.snapshot(),
        )
    }

    @Test
    fun tracksLayerLifecycleAndInvalidationCategories() {
        val counters = SdlCompositorCounters()

        counters.onLayerCreated()
        counters.onLayerContentChanged()
        counters.onLayerPropertiesChanged()
        counters.onLayerPropertiesChanged()
        counters.onLayerReleased()

        val stats = counters.snapshot()
        assertEquals(1, stats.createdLayers)
        assertEquals(0, stats.activeLayers)
        assertEquals(1, stats.layerContentChanges)
        assertEquals(2, stats.layerPropertyChanges)
    }

    @Test
    fun recordsCpuExternalCpuOrderAndRebuildsFallbackRoot() {
        val root = CairoSurface(8, 8)
        root.clear()
        val context = checkNotNull(kc_create(root.handle))
        val canvas = CairoCanvas(context)
        val white = CairoGraphics.createPaint().also { it.color = Color.White }
        val red = CairoGraphics.createPaint().also { it.color = Color.Red }
        val mask =
            CairoGraphics.createPath().apply {
                addRect(Rect(2f, 2f, 6f, 6f))
            }
        val external =
            object : GpuInteropLayerCommand {
                override val compositionId = 7L

                override fun draw(gpu: COpaquePointer): Boolean = true
            }
        val recorder =
            SdlOrderedCompositionRecorder(
                rootCanvas = canvas,
                rootSurface = root,
                width = 8,
                height = 8,
                damage = FrameDamage(0, 0, 8, 8),
            )

        try {
            canvas.drawRect(0f, 0f, 8f, 8f, white)
            assertTrue(recorder.emit(external, canvas, mask))
            canvas.drawRect(3f, 3f, 5f, 5f, red)

            val capture = recorder.finish()
            try {
                assertEquals(SdlOrderedFallbackReason.None, capture.fallbackReason)
                assertEquals(listOf(7L), capture.topology)
                assertEquals(2, capture.segments.size)

                root.flush()
                val pixels = root.data.reinterpret<IntVar>()
                val stride = root.stride / 4
                assertEquals(-1, pixels[1 * stride + 1])
                assertEquals(0, pixels[2 * stride + 2])
                assertEquals(0xffff0000u.toInt(), pixels[4 * stride + 4])
            } finally {
                capture.close()
            }
        } finally {
            kc_destroy(context)
            root.close()
        }
    }

    @Test
    fun nonRootBoundaryMarksOwningCanvasForFlattening() {
        val root = CairoSurface(8, 8)
        val rootContext = checkNotNull(kc_create(root.handle))
        val offscreen = CairoSurface(8, 8)
        val offscreenContext = checkNotNull(kc_create(offscreen.handle))
        var marked = false
        val external =
            object : GpuInteropLayerCommand {
                override val compositionId = 9L

                override fun draw(gpu: COpaquePointer): Boolean = true
            }
        val recorder =
            SdlOrderedCompositionRecorder(
                rootCanvas = CairoCanvas(rootContext),
                rootSurface = root,
                width = 8,
                height = 8,
                damage = FrameDamage(0, 0, 8, 8),
            )

        try {
            assertFalse(
                recorder.emit(
                    external,
                    CairoCanvas(offscreenContext) { marked = true },
                    CairoGraphics.createPath(),
                )
            )
            assertTrue(marked)
            val capture = recorder.finish()
            try {
                assertEquals(SdlOrderedFallbackReason.NonRootCanvas, capture.fallbackReason)
            } finally {
                capture.close()
            }
        } finally {
            kc_destroy(offscreenContext)
            offscreen.close()
            kc_destroy(rootContext)
            root.close()
        }
    }

    @Test
    fun rejectsBoundaryWhileRootContextTargetsSaveLayer() {
        val root = CairoSurface(8, 8)
        root.clear()
        val context = checkNotNull(kc_create(root.handle))
        val canvas = CairoCanvas(context)
        val external =
            object : GpuInteropLayerCommand {
                override val compositionId = 11L

                override fun draw(gpu: COpaquePointer): Boolean = true
            }
        val recorder =
            SdlOrderedCompositionRecorder(
                rootCanvas = canvas,
                rootSurface = root,
                width = 8,
                height = 8,
                damage = FrameDamage(0, 0, 8, 8),
            )

        try {
            canvas.saveLayer(Rect(0f, 0f, 8f, 8f), CairoGraphics.createPaint())
            assertFalse(recorder.emit(external, canvas, CairoGraphics.createPath()))
            canvas.restore()
            val capture = recorder.finish()
            try {
                assertEquals(SdlOrderedFallbackReason.NestedCairoGroup, capture.fallbackReason)
                assertTrue(capture.segments.isEmpty())
            } finally {
                capture.close()
            }
        } finally {
            kc_destroy(context)
            root.close()
        }
    }

    @Test
    fun cairoGraphicsContextRegistersLayerChangesWithWindowCompositor() {
        var created = 0
        var released = 0
        var contentChanges = 0
        var propertyChanges = 0
        val layerCompositor =
            object : CairoLayerCompositor {
                override fun createLayerRegistration(): CairoLayerRegistration {
                    created++
                    return object : CairoLayerRegistration {
                        override val id = created.toLong()

                        override fun onContentChanged() {
                            contentChanges++
                        }

                        override fun onPropertiesChanged() {
                            propertyChanges++
                        }

                        override fun close() {
                            released++
                        }
                    }
                }
            }
        val context = CairoGraphics.createGraphicsContext(layerCompositor)
        val layer = context.createGraphicsLayer()

        assertEquals(1, created)
        val propertyChangesBeforeMutation = propertyChanges
        layer.translationX = 12f
        layer.alpha = 0.5f
        assertEquals(propertyChangesBeforeMutation + 2, propertyChanges)

        val contentChangesBeforeRecord = contentChanges
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(16, 16)) {}
        assertTrue(contentChanges > contentChangesBeforeRecord)

        context.releaseGraphicsLayer(layer)
        assertEquals(1, released)
        context.close()
    }
}
