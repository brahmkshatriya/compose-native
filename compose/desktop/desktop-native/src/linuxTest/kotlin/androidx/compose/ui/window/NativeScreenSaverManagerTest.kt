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

class NativeScreenSaverManagerTest {
    @Test
    fun firstKeepScreenOnRequestDisablesAndLastReleaseEnablesScreenSaver() {
        val calls = mutableListOf<String>()
        val manager =
            NativeScreenSaverManager(
                baselineEnabled = true,
                enableScreenSaver = { calls += "enable" },
                disableScreenSaver = { calls += "disable" },
            )
        val first = Any()
        val second = Any()

        manager.setKeepScreenOn(first, true)
        manager.setKeepScreenOn(first, true)
        manager.setKeepScreenOn(second, true)
        manager.setKeepScreenOn(first, false)
        manager.setKeepScreenOn(second, false)

        assertEquals(listOf("disable", "enable"), calls)
    }

    @Test
    fun disabledBaselineIsRestoredAfterKeepScreenOnRequest() {
        val calls = mutableListOf<String>()
        val manager =
            NativeScreenSaverManager(
                baselineEnabled = false,
                enableScreenSaver = { calls += "enable" },
                disableScreenSaver = { calls += "disable" },
            )
        val client = Any()

        manager.setKeepScreenOn(client, true)
        manager.setKeepScreenOn(client, false)

        assertEquals(emptyList(), calls)
    }

    @Test
    fun releaseAndCloseRestoreEnabledBaselineOnlyOnce() {
        val calls = mutableListOf<String>()
        val manager =
            NativeScreenSaverManager(
                baselineEnabled = true,
                enableScreenSaver = { calls += "enable" },
                disableScreenSaver = { calls += "disable" },
            )
        val client = Any()

        manager.setKeepScreenOn(client, true)
        manager.release(client)
        manager.close()

        assertEquals(listOf("disable", "enable"), calls)
    }
}
