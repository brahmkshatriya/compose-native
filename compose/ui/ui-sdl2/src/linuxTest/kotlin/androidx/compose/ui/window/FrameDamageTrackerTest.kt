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
        val tracker = FrameDamageTracker(drawOverflowPaddingDp = 0f)

        tracker.add(Rect(10.25f, 20.75f, 58.1f, 68.2f))

        assertEquals(FrameDamage(8, 18, 53, 53), tracker.take(100, 100))
    }


    @Test
    fun includesDensityScaledOverflowForUnboundedMaterialIndications() {
        val tracker = FrameDamageTracker()
        tracker.updateDensity(1.5f)

        // A 24 dp / 36 px radio draw node with a 20 dp / 30 px state-layer radius needs
        // 12 px of overflow on every side at this density. The production margin is deliberately
        // conservative and also includes the antialias fringe.
        tracker.add(Rect(100f, 100f, 136f, 136f))

        assertEquals(FrameDamage(80, 80, 76, 76), tracker.take(300, 300))
    }

    @Test
    fun unionsDamageAndClipsToFramebuffer() {
        val tracker = FrameDamageTracker(drawOverflowPaddingDp = 0f)

        tracker.add(Rect(-10f, 5f, 12f, 18f))
        tracker.add(Rect(80f, 70f, 120f, 110f))

        assertEquals(FrameDamage(0, 3, 100, 97), tracker.take(100, 100))
    }

    @Test
    fun consumingDamageResetsTracker() {
        val tracker = FrameDamageTracker(drawOverflowPaddingDp = 0f)
        tracker.add(Rect(1f, 1f, 4f, 4f))

        tracker.take(20, 20)

        assertNull(tracker.take(20, 20))
    }

    @Test
    fun fullFrameRequestOverridesBoundedDamage() {
        val tracker = FrameDamageTracker(drawOverflowPaddingDp = 0f)
        tracker.add(Rect(4f, 4f, 8f, 8f))
        tracker.requireFullFrame()
        tracker.add(Rect(10f, 10f, 12f, 12f))

        assertEquals(true, tracker.takeFullFrameRequest())
        assertNull(tracker.take(20, 20))
        assertEquals(false, tracker.takeFullFrameRequest())
    }

    @Test
    fun ignoresEmptyAndNonFiniteDamage() {
        val tracker = FrameDamageTracker(drawOverflowPaddingDp = 0f)

        tracker.add(Rect.Zero)
        tracker.add(Rect(Float.NaN, 0f, 10f, 10f))

        assertNull(tracker.take(20, 20))
    }
}
