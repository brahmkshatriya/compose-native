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

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameDamageTrackerTest {
    @Test
    fun expandsFractionalBoundsForAntialiasing() {
        val tracker = FrameDamageTracker()

        tracker.add(Rect(10.25f, 20.75f, 58.1f, 68.2f))

        assertEquals(FrameDamage(8, 18, 53, 53), tracker.take(100, 100))
    }


    @Test
    fun unionsDamageAndClipsToFramebuffer() {
        val tracker = FrameDamageTracker()

        tracker.add(Rect(-10f, 5f, 12f, 18f))
        tracker.add(Rect(80f, 70f, 120f, 110f))

        assertEquals(FrameDamage(0, 3, 100, 97), tracker.take(100, 100))
    }

    @Test
    fun consumingDamageResetsTracker() {
        val tracker = FrameDamageTracker()
        tracker.add(Rect(1f, 1f, 4f, 4f))

        tracker.take(20, 20)

        assertNull(tracker.take(20, 20))
    }

    @Test
    fun fullFrameRequestOverridesBoundedDamage() {
        val tracker = FrameDamageTracker()
        tracker.add(Rect(4f, 4f, 8f, 8f))
        tracker.requireFullFrame()
        tracker.add(Rect(10f, 10f, 12f, 12f))

        assertEquals(true, tracker.takeFullFrameRequest())
        assertNull(tracker.take(20, 20))
        assertEquals(false, tracker.takeFullFrameRequest())
    }

    @Test
    fun ignoresEmptyAndNonFiniteDamage() {
        val tracker = FrameDamageTracker()

        tracker.add(Rect.Zero)
        tracker.add(Rect(Float.NaN, 0f, 10f, 10f))

        assertNull(tracker.take(20, 20))
    }
}
