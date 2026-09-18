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

import kotlin.math.roundToInt

/** Resolves Compose frame-rate votes into the cadence used by the native host event loop. */
internal class NativeFrameRateManager(
    private val performanceFrequency: ULong,
    private val configuredMaximumFramesPerSecond: Int? = null,
) {
    private companion object {
        // Encoded values used by androidx.compose.ui.FrameRateCategory. PlatformContext exposes the
        // category as a Float so platform hosts can consume it without depending on public API
        // type.
        const val NormalCategory = -3f
        const val HighCategory = -4f
    }

    private var pendingVote = Float.NaN
    private var effectiveFramesPerSecond: Int? = configuredMaximumFramesPerSecond
    private var framePacer =
        effectiveFramesPerSecond?.let { NativeFramePacer(performanceFrequency, it) }

    internal val currentFramesPerSecond: Int?
        get() = effectiveFramesPerSecond

    fun voteFrameRate(frameRate: Float, frameRateCategory: Float, maximumFramesPerSecond: Float) {
        val displayMaximum = maximumFramesPerSecond.takeIf { it.isFinite() && it > 0f } ?: 60f
        val exact = frameRate.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(displayMaximum)
        val category =
            when {
                frameRateCategory.isNaN() -> 0f // Platform default: no extra Compose cap.
                frameRateCategory == NormalCategory -> minOf(60f, displayMaximum)
                frameRateCategory == HighCategory -> displayMaximum
                else -> Float.NaN
            }
        val resolved =
            when {
                exact != null && !category.isNaN() -> maxOf(exact, category)
                exact != null -> exact
                !category.isNaN() -> category
                else -> return
            }
        if (pendingVote.isNaN() || resolved > pendingVote) pendingVote = resolved
    }

    /** Applies all votes collected while the host rendered the current frame. */
    fun applyPendingVote() {
        if (pendingVote.isNaN()) return
        val requested = pendingVote
        pendingVote = Float.NaN
        val votedFramesPerSecond = requested.takeIf { it > 0f }?.roundToInt()?.coerceAtLeast(1)
        val next =
            when {
                configuredMaximumFramesPerSecond == null -> votedFramesPerSecond
                votedFramesPerSecond == null -> configuredMaximumFramesPerSecond
                else -> minOf(configuredMaximumFramesPerSecond, votedFramesPerSecond)
            }
        if (next == effectiveFramesPerSecond) return
        effectiveFramesPerSecond = next
        framePacer = next?.let { NativeFramePacer(performanceFrequency, it) }
    }

    fun delayMillis(now: ULong): Int = framePacer?.delayMillis(now) ?: 0

    fun onFrameStarted(now: ULong) {
        framePacer?.onFrameStarted(now)
    }
}
