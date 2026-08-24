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

/** Leaves time for input messages between synchronous frames rendered during a live resize. */
internal class LiveResizeFrameThrottle(performanceFrequency: ULong, framesPerSecond: Int) {
    private val frequency = performanceFrequency.coerceAtLeast(1uL)
    private val intervalTicks =
        ((frequency + framesPerSecond.requirePositive().toULong() - 1uL) /
                framesPerSecond.toULong())
            .coerceAtLeast(1uL)
    private var nextFrameTick: ULong? = null

    fun shouldRender(now: ULong): Boolean = nextFrameTick?.let { now >= it } ?: true

    /**
     * Starts the cooldown after rendering so an expensive frame cannot consume the next interval.
     */
    fun onFrameRendered(now: ULong) {
        nextFrameTick = now + intervalTicks
    }

    fun reset() {
        nextFrameTick = null
    }

    private fun Int.requirePositive(): Int = also {
        require(it > 0) { "framesPerSecond must be positive" }
    }
}
