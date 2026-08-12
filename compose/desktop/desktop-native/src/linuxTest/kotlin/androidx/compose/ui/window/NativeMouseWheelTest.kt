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

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeMouseWheelTest {
    @Test
    fun shiftConvertsVerticalWheelToHorizontalScroll() {
        assertEquals(
            Offset(x = 80f, y = 0f),
            nativeMouseWheelScrollDelta(x = 0f, y = 80f, isShiftPressed = true),
        )
    }

    @Test
    fun wheelAxesRemainUnchangedWithoutShift() {
        assertEquals(
            Offset(x = 12f, y = 80f),
            nativeMouseWheelScrollDelta(x = 12f, y = 80f, isShiftPressed = false),
        )
    }

    @Test
    fun nativeHorizontalDeltaIsNotRemappedWhenShiftIsPressed() {
        assertEquals(
            Offset(x = 12f, y = 80f),
            nativeMouseWheelScrollDelta(x = 12f, y = 80f, isShiftPressed = true),
        )
    }
}
