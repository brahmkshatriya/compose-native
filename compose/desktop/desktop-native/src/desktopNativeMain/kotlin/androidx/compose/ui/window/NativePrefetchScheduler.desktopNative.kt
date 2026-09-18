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

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import androidx.compose.ui.platform.PlatformPrefetchScheduler

@OptIn(InternalComposeUiApi::class)
internal class NativePrefetchScheduler(
    private val currentTimeNanos: () -> Long,
    private val requestHostWork: () -> Unit,
    private val budgetNanos: Long = DefaultBudgetNanos,
) : PlatformPrefetchScheduler {
    private companion object {
        const val DefaultBudgetNanos = 4_000_000L
    }

    private val highPriorityRequests = ArrayDeque<PlatformPrefetchRequest>()
    private val lowPriorityRequests = ArrayDeque<PlatformPrefetchRequest>()
    private val scope = PrefetchRequestScopeImpl()
    private var isDisposed = false

    val hasWorkScheduled: Boolean
        get() = highPriorityRequests.isNotEmpty() || lowPriorityRequests.isNotEmpty()

    init {
        require(budgetNanos > 0) { "Prefetch budget must be positive" }
    }

    override fun scheduleHighPriorityPrefetch(request: PlatformPrefetchRequest) {
        if (isDisposed) return
        highPriorityRequests.addLast(request)
        requestHostWork()
    }

    override fun scheduleLowPriorityPrefetch(request: PlatformPrefetchRequest) {
        if (isDisposed) return
        lowPriorityRequests.addLast(request)
        requestHostWork()
    }

    /**
     * Runs prefetch work for one host-loop turn. A request that reports unfinished work is resumed
     * on a later turn so event processing and rendering get a chance to run between chunks.
     *
     * @return whether more prefetch work remains queued.
     */
    fun executePending(): Boolean {
        if (isDisposed || !hasWorkScheduled) return false

        scope.deadlineNanos = currentTimeNanos() + budgetNanos
        var continueNextTurn = false
        while (hasWorkScheduled && !continueNextTurn && scope.availableTimeNanos() > 0) {
            val requestQueue =
                if (highPriorityRequests.isNotEmpty()) highPriorityRequests else lowPriorityRequests
            val hasMoreWork = with(requestQueue.first()) { scope.execute() }
            if (!hasMoreWork) {
                requestQueue.removeFirst()
            }
            continueNextTurn = hasMoreWork
        }

        if (hasWorkScheduled) requestHostWork()
        return hasWorkScheduled
    }

    fun dispose() {
        isDisposed = true
        highPriorityRequests.clear()
        lowPriorityRequests.clear()
    }

    private inner class PrefetchRequestScopeImpl : PlatformPrefetchRequestScope {
        var deadlineNanos: Long = 0L

        override fun availableTimeNanos(): Long =
            (deadlineNanos - currentTimeNanos()).coerceAtLeast(0L)
    }
}
