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

    fun notificationCapabilities(): Set<String>

    fun areNotificationsSupported(): Boolean

    fun sendNotification(
        applicationName: String,
        title: String,
        message: String,
        iconName: String,
        replacesId: UInt,
        actions: List<NativeNotificationAction>,
        hints: Map<String, NativeNotificationHint>,
        timeoutMillis: Int,
    ): UInt

    fun closeNotification(id: UInt)

    fun isProgressServiceSupported(): Boolean

    fun startProgressJob(applicationName: String, iconName: String, capabilities: Int): String

    fun updateProgressJob(path: String, update: NativeProgressUpdate)

    fun terminateProgressJob(path: String, errorMessage: String)

    fun pollDesktopEvent(): NativeDesktopEvent?
}

@InternalComposeUiApi data class NativeNotificationAction(val id: String, val label: String)

@InternalComposeUiApi
sealed interface NativeNotificationHint {
    data class ByteValue(val value: UByte) : NativeNotificationHint

    data class IntValue(val value: Int) : NativeNotificationHint

    data class UIntValue(val value: UInt) : NativeNotificationHint

    data class LongValue(val value: Long) : NativeNotificationHint

    data class ULongValue(val value: ULong) : NativeNotificationHint

    data class DoubleValue(val value: Double) : NativeNotificationHint

    data class BooleanValue(val value: Boolean) : NativeNotificationHint

    data class StringValue(val value: String) : NativeNotificationHint
}

@InternalComposeUiApi
data class NativeProgressUpdate(
    val totalBytes: ULong,
    val processedBytes: ULong,
    val bytesPerSecond: ULong,
    val elapsedMillis: ULong,
    val percent: UInt,
    val message: String,
)

@InternalComposeUiApi
sealed interface NativeDesktopEvent {
    data class NotificationAction(val notificationId: UInt, val actionId: String) :
        NativeDesktopEvent

    data class NotificationClosed(val notificationId: UInt, val reason: UInt) : NativeDesktopEvent

    data class ProgressRequested(val path: String, val action: Action) : NativeDesktopEvent {
        enum class Action {
            Cancel,
            Suspend,
            Resume,
        }
    }
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
