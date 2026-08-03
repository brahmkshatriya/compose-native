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

package androidx.compose.foundation.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxScrollableTest {
    @Test
    fun wheelDeltaIsForwardedWithNaturalDirection() {
        val event = pointerEvent(scrollDelta = Offset(12f, 48f))

        val result =
            with(LinuxScrollConfig) {
                with(Density(2f)) { calculateMouseWheelScroll(event, bounds = IntSize(800, 600)) }
            }

        assertEquals(Offset(-12f, -48f), result)
    }

    @Test
    fun wheelDeltasFromMultiplePointersAreCombined() {
        val event =
            PointerEvent(
                listOf(
                    pointerChange(id = 1L, scrollDelta = Offset(0f, 20f)),
                    pointerChange(id = 2L, scrollDelta = Offset(5f, 7f)),
                )
            )

        val result =
            with(LinuxScrollConfig) {
                with(Density(1f)) { calculateMouseWheelScroll(event, bounds = IntSize(400, 300)) }
            }

        assertEquals(Offset(-5f, -27f), result)
    }

    private fun pointerEvent(scrollDelta: Offset): PointerEvent =
        PointerEvent(listOf(pointerChange(id = 1L, scrollDelta = scrollDelta)))

    private fun pointerChange(id: Long, scrollDelta: Offset): PointerInputChange =
        PointerInputChange(
            id = PointerId(id),
            uptimeMillis = 1L,
            position = Offset.Zero,
            pressed = false,
            previousUptimeMillis = 0L,
            previousPosition = Offset.Zero,
            previousPressed = false,
            isInitiallyConsumed = false,
            type = PointerType.Mouse,
            scrollDelta = scrollDelta,
        )
}
