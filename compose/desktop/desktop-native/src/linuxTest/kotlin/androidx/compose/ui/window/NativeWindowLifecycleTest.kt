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
import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalComposeUiApi::class)
class NativeWindowLifecycleTest {
    @Test
    fun followsWindowState() {
        val lifecycle = NativeWindowLifecycle()

        lifecycle.update(isVisible = true, isMinimized = false, isFocused = true)
        assertEquals(Lifecycle.State.RESUMED, lifecycle.owner.lifecycle.currentState)

        lifecycle.update(isVisible = true, isMinimized = false, isFocused = false)
        assertEquals(Lifecycle.State.STARTED, lifecycle.owner.lifecycle.currentState)

        lifecycle.update(isVisible = true, isMinimized = true, isFocused = false)
        assertEquals(Lifecycle.State.CREATED, lifecycle.owner.lifecycle.currentState)

        lifecycle.update(isVisible = false, isMinimized = false, isFocused = false)
        assertEquals(Lifecycle.State.CREATED, lifecycle.owner.lifecycle.currentState)

        lifecycle.destroy()
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.owner.lifecycle.currentState)
    }

    @Test
    fun cannotRestartAfterDestruction() {
        val lifecycle = NativeWindowLifecycle()

        lifecycle.destroy()
        lifecycle.update(isVisible = true, isMinimized = false, isFocused = true)

        assertEquals(Lifecycle.State.DESTROYED, lifecycle.owner.lifecycle.currentState)
    }
}
