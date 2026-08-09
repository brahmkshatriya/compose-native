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

package androidx.compose.ui.platform

import androidx.compose.ui.InternalComposeUiApi

/** Desktop services supplied by the active Kotlin/Native window host. */
@InternalComposeUiApi
interface NativeDesktopPlatformServices {
    fun getClipboardText(): String?

    fun setClipboardText(text: String)

    fun openUri(uri: String)
}

/** Installs native desktop services without coupling Compose UI to a window implementation. */
@InternalComposeUiApi
object NativeDesktopPlatformServicesRegistry {
    private var services: NativeDesktopPlatformServices? = null

    fun install(services: NativeDesktopPlatformServices?) {
        this.services = services
    }

    internal fun current(): NativeDesktopPlatformServices? = services
}
