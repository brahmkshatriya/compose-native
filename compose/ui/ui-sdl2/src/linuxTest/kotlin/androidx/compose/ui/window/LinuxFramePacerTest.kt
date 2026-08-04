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

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxFramePacerTest {
    @Test
    fun capsFramesAtConfiguredCadence() {
        val pacer = LinuxFramePacer(performanceFrequency = 1_000uL, framesPerSecond = 60)

        pacer.onFrameStarted(100uL)

        assertEquals(17, pacer.delayMillis(100uL))
        assertEquals(1, pacer.delayMillis(116uL))
        assertEquals(0, pacer.delayMillis(117uL))
    }

    @Test
    fun blockingPresentationDoesNotHalveFrameRate() {
        val pacer = LinuxFramePacer(performanceFrequency = 1_000uL, framesPerSecond = 60)

        pacer.onFrameStarted(0uL)
        assertEquals(0, pacer.delayMillis(17uL))
        pacer.onFrameStarted(17uL)

        assertEquals(17, pacer.delayMillis(17uL))
        assertEquals(0, pacer.delayMillis(34uL))
    }

    @Test
    fun missedDeadlinesAreDropped() {
        val pacer = LinuxFramePacer(performanceFrequency = 1_000uL, framesPerSecond = 60)

        pacer.onFrameStarted(0uL)
        pacer.onFrameStarted(100uL)

        assertEquals(2, pacer.delayMillis(100uL))
        assertEquals(0, pacer.delayMillis(102uL))
    }
}
