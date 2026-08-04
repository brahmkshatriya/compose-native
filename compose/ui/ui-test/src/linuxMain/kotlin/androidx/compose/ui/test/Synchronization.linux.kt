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

package androidx.compose.ui.test

import platform.posix.usleep

internal actual fun <T> runOnUiThread(action: () -> T): T = action()

internal actual fun isOnUiThread(): Boolean = true

internal actual fun sleep(timeMillis: Long) {
    if (timeMillis <= 0) return
    usleep((timeMillis * 1_000L).coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt())
}
