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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.viewinterop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoGraphics
import androidx.compose.ui.graphics.cairo.CairoSurface
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry
import cairo.kc_create
import cairo.kc_destroy
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalComposeUiApi::class)
class GpuInteropRegistryTest {
    private object TestLayer : GpuInteropLayerCommand {
        override val compositionId = 1L
        override fun draw(gpu: kotlinx.cinterop.COpaquePointer): Boolean = true
    }

    @Test
    fun fallbackMaskIsAppliedAfterSceneDrawing() {
        PlatformGraphicsRegistry.register(CairoGraphics)
        val surface = CairoSurface(8, 8)
        val context = checkNotNull(kc_create(surface.handle))
        val canvas = CairoCanvas(context)
        val damagedBounds = mutableListOf<Rect>()
        val registry = GpuInteropRegistry(damagedBounds::add)
        val mask = androidx.compose.ui.graphics.Path().apply { addRect(Rect(2f, 2f, 6f, 6f)) }

        try {
            canvas.drawRect(
                0f,
                0f,
                8f,
                8f,
                CairoGraphics.createPaint().also {
                    it.color = Color.White
                    it.blendMode = BlendMode.Src
                },
            )
            surface.flush()
            registry.rootCanvas = canvas
            registry.setFallbackMask(TestLayer, mask)

            val pixels = surface.data.reinterpret<IntVar>()
            assertEquals(0xffffffffu.toInt(), pixels[3 * (surface.stride / 4) + 3])

            registry.applyFallbackMasks()
            surface.flush()
            assertEquals(0, pixels[3 * (surface.stride / 4) + 3])

            // A later frame can redraw an opaque retained ancestor without replaying NativeView.
            // The registry must retain and reapply the mask until the native layer is removed or
            // promoted back into the ordered stream.
            canvas.drawRect(
                0f,
                0f,
                8f,
                8f,
                CairoGraphics.createPaint().also {
                    it.color = Color.White
                    it.blendMode = BlendMode.Src
                },
            )
            registry.applyFallbackMasks()
            surface.flush()
            assertEquals(0, pixels[3 * (surface.stride / 4) + 3])

            canvas.drawRect(
                0f,
                0f,
                8f,
                8f,
                CairoGraphics.createPaint().also {
                    it.color = Color.White
                    it.blendMode = BlendMode.Src
                },
            )
            val movedMask = androidx.compose.ui.graphics.Path().apply {
                addRect(Rect(1f, 1f, 3f, 3f))
            }
            registry.refreshFallbackMask(TestLayer, movedMask, mask.getBounds())
            assertEquals(listOf(mask.getBounds(), movedMask.getBounds()), damagedBounds)
            registry.applyFallbackMasks()
            surface.flush()
            assertEquals(0, pixels[2 * (surface.stride / 4) + 2])
            assertEquals(0xffffffffu.toInt(), pixels[5 * (surface.stride / 4) + 5])
        } finally {
            registry.rootCanvas = null
            kc_destroy(context)
            surface.close()
            PlatformGraphicsRegistry.clear()
        }
    }
}
