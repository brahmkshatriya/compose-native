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

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class FrameRecomposerWakeupTest {
    @Test
    fun globalWriteWakesImmediateDispatcherHost() {
        var invalidations = 0
        val recomposer = FrameRecomposer(Dispatchers.Unconfined) { invalidations++ }
        try {
            val state = mutableStateOf(0)
            val baseline = invalidations

            state.value = 1

            assertTrue(invalidations > baseline)
            assertTrue(recomposer.hasPendingWork())

            recomposer.performFrame(0L)

            assertFalse(recomposer.hasPendingWork())
        } finally {
            recomposer.close()
        }
    }
}
