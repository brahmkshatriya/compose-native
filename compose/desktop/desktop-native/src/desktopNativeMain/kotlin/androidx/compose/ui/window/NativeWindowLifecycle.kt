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
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles

@OptIn(InternalComposeUiApi::class)
internal class NativeWindowLifecycle {
    val owner =
        DefaultArchitectureComponentsOwner(enforceMainThread = false).apply {
            enableSavedStateHandles()
        }

    private var isDestroyed = false

    fun update(isVisible: Boolean, isMinimized: Boolean, isFocused: Boolean) {
        if (isDestroyed) return
        owner.setLifecycleState(
            when {
                !isVisible || isMinimized -> Lifecycle.State.CREATED
                isFocused -> Lifecycle.State.RESUMED
                else -> Lifecycle.State.STARTED
            }
        )
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        if (owner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            owner.setLifecycleState(Lifecycle.State.CREATED)
        }
        owner.setLifecycleState(Lifecycle.State.DESTROYED)
    }
}
