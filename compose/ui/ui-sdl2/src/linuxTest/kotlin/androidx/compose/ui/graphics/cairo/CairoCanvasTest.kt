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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import cairo.kc_create
import cairo.kc_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret

class CairoCanvasTest {
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
