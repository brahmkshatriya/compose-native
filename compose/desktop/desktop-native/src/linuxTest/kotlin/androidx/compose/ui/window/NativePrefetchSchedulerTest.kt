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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class NativePrefetchSchedulerTest {
    @Test
    fun highPriorityRunsBeforeEarlierLowPriorityRequest() {
        var now = 0L
        val order = mutableListOf<String>()
        val scheduler = NativePrefetchScheduler(
            currentTimeNanos = { now },
            requestHostWork = {},
            budgetNanos = 100,
        )

        scheduler.scheduleLowPriorityPrefetch(
            request {
                order += "low"
                false
            }
        )
        scheduler.scheduleHighPriorityPrefetch(
            request {
                order += "high"
                false
            }
        )

        assertFalse(scheduler.executePending())
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun unfinishedRequestIsResumedOnAnotherHostTurn() {
        var now = 0L
        var wakeups = 0
        var executions = 0
        val availableBudgets = mutableListOf<Long>()
        val scheduler = NativePrefetchScheduler(
            currentTimeNanos = { now },
            requestHostWork = { wakeups++ },
            budgetNanos = 10,
        )
        scheduler.scheduleHighPriorityPrefetch(
            request {
                availableBudgets += availableTimeNanos()
                executions++
                now += if (executions == 1) 10 else 1
                executions == 1
            }
        )

        assertTrue(scheduler.executePending())
        assertEquals(1, executions)
        assertEquals(2, wakeups)

        assertFalse(scheduler.executePending())
        assertEquals(2, executions)
        assertEquals(listOf(10L, 10L), availableBudgets)
    }

    @Test
    fun exhaustedBudgetDefersFollowingRequest() {
        var now = 0L
        val order = mutableListOf<String>()
        val scheduler = NativePrefetchScheduler(
            currentTimeNanos = { now },
            requestHostWork = {},
            budgetNanos = 10,
        )
        scheduler.scheduleHighPriorityPrefetch(
            request {
                order += "first"
                now += 10
                false
            }
        )
        scheduler.scheduleHighPriorityPrefetch(
            request {
                order += "second"
                false
            }
        )

        assertTrue(scheduler.executePending())
        assertEquals(listOf("first"), order)

        assertFalse(scheduler.executePending())
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun disposeDropsQueuedRequestsAndRejectsNewOnes() {
        var executions = 0
        var wakeups = 0
        val scheduler = NativePrefetchScheduler(
            currentTimeNanos = { 0L },
            requestHostWork = { wakeups++ },
            budgetNanos = 10,
        )
        val request = request {
            executions++
            false
        }
        scheduler.scheduleLowPriorityPrefetch(request)

        scheduler.dispose()
        scheduler.scheduleHighPriorityPrefetch(request)

        assertFalse(scheduler.hasWorkScheduled)
        assertFalse(scheduler.executePending())
        assertEquals(0, executions)
        assertEquals(1, wakeups)
    }

    private fun request(
        block: PlatformPrefetchRequestScope.() -> Boolean
    ): PlatformPrefetchRequest =
        object : PlatformPrefetchRequest {
            override fun PlatformPrefetchRequestScope.execute(): Boolean = block()
        }
}
