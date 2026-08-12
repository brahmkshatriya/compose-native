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

/**
 * Caps host-driven frames independently of the graphics driver's VSync implementation.
 *
 * The next deadline is based on frame start rather than presentation completion. This means a
 * blocking VSync swap and this guard do not accidentally halve the effective refresh rate.
 */
internal class NativeFramePacer(
    performanceFrequency: ULong,
    framesPerSecond: Int,
) {
    private val validatedFramesPerSecond =
        framesPerSecond.also { require(it > 0) { "framesPerSecond must be positive" } }
    private val frequency = performanceFrequency.coerceAtLeast(1uL)
    private val intervalTicks =
        ((frequency + validatedFramesPerSecond.toULong() - 1uL) /
            validatedFramesPerSecond.toULong())
            .coerceAtLeast(1uL)
    private var nextFrameTick: ULong? = null

    /** Returns the rounded-up wait before another frame may start, or zero when it is due. */
    fun delayMillis(now: ULong): Int {
        val deadline = nextFrameTick ?: return 0
        if (now >= deadline) return 0
        val remaining = deadline - now
        return ((remaining * 1_000uL + frequency - 1uL) / frequency)
            .coerceAtLeast(1uL)
            .coerceAtMost(Int.MAX_VALUE.toULong())
            .toInt()
    }

    /** Advances the cadence after a frame starts, dropping missed deadlines rather than catching up. */
    fun onFrameStarted(now: ULong) {
        val deadline = nextFrameTick
        if (deadline == null) {
            nextFrameTick = now + intervalTicks
            return
        }
        if (now < deadline) return
        val elapsedIntervals = (now - deadline) / intervalTicks + 1uL
        nextFrameTick = deadline + elapsedIntervals * intervalTicks
    }
}
