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

import sdl3.SDL_DisableScreenSaver
import sdl3.SDL_EnableScreenSaver
import sdl3.SDL_ScreenSaverEnabled
import sdl3.SDL_SetHint

internal class NativeScreenSaverManager(
    private val baselineEnabled: Boolean = SDL_ScreenSaverEnabled(),
    private val enableScreenSaver: () -> Unit = { SDL_EnableScreenSaver() },
    private val disableScreenSaver: () -> Unit = { SDL_DisableScreenSaver() },
) {
    private val keepScreenOnClients = mutableSetOf<Any>()
    private var screenSaverEnabled = baselineEnabled

    fun setKeepScreenOn(client: Any, enabled: Boolean) {
        val changed =
            if (enabled) {
                keepScreenOnClients.add(client)
            } else {
                keepScreenOnClients.remove(client)
            }
        if (changed) updateScreenSaverState()
    }

    fun release(client: Any) {
        if (keepScreenOnClients.remove(client)) updateScreenSaverState()
    }

    fun close() {
        keepScreenOnClients.clear()
        updateScreenSaverState()
    }

    private fun updateScreenSaverState() {
        val shouldEnable = keepScreenOnClients.isEmpty() && baselineEnabled
        if (screenSaverEnabled == shouldEnable) return
        if (shouldEnable) {
            enableScreenSaver()
        } else {
            disableScreenSaver()
        }
        screenSaverEnabled = shouldEnable
    }

    companion object {
        fun configureDefaultPolicy() {
            // SDL defaults to inhibiting the screensaver for video applications. Compose desktop
            // apps should follow normal idle policy unless content explicitly uses keepScreenOn().
            // Normal hint priority preserves an explicit SDL_VIDEO_ALLOW_SCREENSAVER environment
            // override.
            SDL_SetHint("SDL_VIDEO_ALLOW_SCREENSAVER", "1")
        }
    }
}
