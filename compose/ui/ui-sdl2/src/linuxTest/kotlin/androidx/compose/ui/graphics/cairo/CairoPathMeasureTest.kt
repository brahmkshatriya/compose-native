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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CairoPathMeasureTest {
    @Test
    fun extractsAnimatedCheckboxCheckmark() {
        val checkPath =
            CairoGraphics.createPath().apply {
                moveTo(4f, 10f)
                lineTo(8f, 14f)
                lineTo(16f, 6f)
            }
        val pathMeasure = CairoGraphics.createPathMeasure()
        val pathToDraw = CairoGraphics.createPath()
        pathMeasure.setPath(checkPath, forceClosed = false)

        assertTrue(pathMeasure.length > 0f)
        assertTrue(
            pathMeasure.getSegment(
                startDistance = 0f,
                stopDistance = pathMeasure.length,
                destination = pathToDraw,
                startWithMoveTo = true,
            )
        )
        assertEquals(Offset(4f, 10f), pathMeasure.getPosition(0f))
        assertEquals(Offset(16f, 6f), pathMeasure.getPosition(pathMeasure.length))

        val animatedHalf = CairoGraphics.createPath()
        assertTrue(pathMeasure.getSegment(0f, pathMeasure.length / 2f, animatedHalf, true))
        val animatedHalfMeasure = CairoGraphics.createPathMeasure()
        animatedHalfMeasure.setPath(animatedHalf, forceClosed = false)
        assertEquals(pathMeasure.length / 2f, animatedHalfMeasure.length, absoluteTolerance = 0.01f)
    }
}
