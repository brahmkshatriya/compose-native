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

/** Linux desktop services supplied by the active native window host. */
@InternalComposeUiApi
interface LinuxPlatformServices {
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
        actions: List<LinuxNotificationAction>,
        hints: Map<String, LinuxNotificationHint>,
        timeoutMillis: Int,
    ): UInt

    fun closeNotification(id: UInt)

    fun isProgressServiceSupported(): Boolean

    fun startProgressJob(
        applicationName: String,
        iconName: String,
        capabilities: Int,
    ): String

    fun updateProgressJob(path: String, update: LinuxProgressUpdate)

    fun terminateProgressJob(path: String, errorMessage: String)

    fun pollDesktopEvent(): LinuxDesktopEvent?
}

@InternalComposeUiApi
data class LinuxNotificationAction(val id: String, val label: String)

@InternalComposeUiApi
sealed interface LinuxNotificationHint {
    data class ByteValue(val value: UByte) : LinuxNotificationHint
    data class IntValue(val value: Int) : LinuxNotificationHint
    data class UIntValue(val value: UInt) : LinuxNotificationHint
    data class LongValue(val value: Long) : LinuxNotificationHint
    data class ULongValue(val value: ULong) : LinuxNotificationHint
    data class DoubleValue(val value: Double) : LinuxNotificationHint
    data class BooleanValue(val value: Boolean) : LinuxNotificationHint
    data class StringValue(val value: String) : LinuxNotificationHint
}

@InternalComposeUiApi
data class LinuxProgressUpdate(
    val totalBytes: ULong,
    val processedBytes: ULong,
    val bytesPerSecond: ULong,
    val elapsedMillis: ULong,
    val percent: UInt,
    val message: String,
)

@InternalComposeUiApi
sealed interface LinuxDesktopEvent {
    data class NotificationAction(val notificationId: UInt, val actionId: String) :
        LinuxDesktopEvent

    data class NotificationClosed(val notificationId: UInt, val reason: UInt) : LinuxDesktopEvent

    data class ProgressRequested(val path: String, val action: Action) : LinuxDesktopEvent {
        enum class Action { Cancel, Suspend, Resume }
    }
}

/** Installs desktop services without making Compose UI depend on a particular Linux host. */
@InternalComposeUiApi
object LinuxPlatformServicesRegistry {
    private var services: LinuxPlatformServices? = null

    fun install(services: LinuxPlatformServices?) {
        this.services = services
    }

    internal fun current(): LinuxPlatformServices? = services
}
