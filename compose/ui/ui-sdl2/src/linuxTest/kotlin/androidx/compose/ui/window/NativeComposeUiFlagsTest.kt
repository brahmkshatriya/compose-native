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

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package androidx.compose.ui.window

import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.isDialogAnimationEnabled
import kotlin.test.Test
import kotlin.test.assertFalse

class NativeComposeUiFlagsTest {
    @Test
    fun disablesDetachedDialogExitAnimation() {
        val previous = ComposeUiFlags.isDialogAnimationEnabled
        try {
            ComposeUiFlags.isDialogAnimationEnabled = true
            configureNativeComposeUiFlags()

            assertFalse(ComposeUiFlags.isDialogAnimationEnabled)
        } finally {
            ComposeUiFlags.isDialogAnimationEnabled = previous
        }
    }
}
