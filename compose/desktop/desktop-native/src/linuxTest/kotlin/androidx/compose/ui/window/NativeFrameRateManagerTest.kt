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
import kotlin.test.assertNull

class NativeFrameRateManagerTest {
    @Test
    fun exactVoteCapsHostCadence() {
        val manager = NativeFrameRateManager(performanceFrequency = 1_000uL)

        manager.voteFrameRate(30f, 0f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        manager.onFrameStarted(0uL)

        assertEquals(30, manager.currentFramesPerSecond)
        assertEquals(34, manager.delayMillis(0uL))
    }

    @Test
    fun normalAndHighCategoriesUseDisplayRefreshRate() {
        val manager = NativeFrameRateManager(performanceFrequency = 1_000uL)

        manager.voteFrameRate(Float.NaN, -3f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(60, manager.currentFramesPerSecond)

        manager.voteFrameRate(Float.NaN, -4f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(144, manager.currentFramesPerSecond)
    }

    @Test
    fun highestVoteWinsWithinHostFrame() {
        val manager = NativeFrameRateManager(performanceFrequency = 1_000uL)

        manager.voteFrameRate(30f, 0f, maximumFramesPerSecond = 144f)
        manager.voteFrameRate(Float.NaN, -3f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()

        assertEquals(60, manager.currentFramesPerSecond)
    }

    @Test
    fun configuredMaximumRemainsHardCapAndDefaultRestoresIt() {
        val manager =
            NativeFrameRateManager(
                performanceFrequency = 1_000uL,
                configuredMaximumFramesPerSecond = 48,
            )

        manager.voteFrameRate(Float.NaN, -4f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(48, manager.currentFramesPerSecond)

        manager.voteFrameRate(24f, 0f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(24, manager.currentFramesPerSecond)

        manager.voteFrameRate(Float.NaN, Float.NaN, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(48, manager.currentFramesPerSecond)
    }

    @Test
    fun platformDefaultRemovesComposeCap() {
        val manager = NativeFrameRateManager(performanceFrequency = 1_000uL)

        manager.voteFrameRate(30f, 0f, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()
        assertEquals(30, manager.currentFramesPerSecond)

        manager.voteFrameRate(Float.NaN, Float.NaN, maximumFramesPerSecond = 144f)
        manager.applyPendingVote()

        assertNull(manager.currentFramesPerSecond)
    }
}
