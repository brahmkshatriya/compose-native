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
    /** Returns the current clipboard text, or `null` when text is unavailable. */
    fun getClipboardText(): String?

    /** Replaces the clipboard contents with [text]. */
    fun setClipboardText(text: String)

    /** Opens [uri] with the desktop's default handler. */
    fun openUri(uri: String)

    /** Returns protocol capability names reported by the desktop notification service. */
    fun notificationCapabilities(): Set<String>

    /** Whether the current desktop can show system notifications. */
    fun areNotificationsSupported(): Boolean

    /** Sends or replaces a desktop notification and returns its platform identifier. */
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

    /** Requests that the notification identified by [id] close. */
    fun closeNotification(id: UInt)

    /** Whether the current desktop exposes a native progress-job service. */
    fun isProgressServiceSupported(): Boolean

    /** Starts a native progress job and returns its platform path or identifier. */
    fun startProgressJob(applicationName: String, iconName: String, capabilities: Int): String

    /** Updates an active native progress job. */
    fun updateProgressJob(path: String, update: NativeProgressUpdate)

    /** Finishes an active native progress job, optionally reporting [errorMessage]. */
    fun terminateProgressJob(path: String, errorMessage: String)

    /** Returns the next pending desktop event, or `null` when the queue is empty. */
    fun pollDesktopEvent(): NativeDesktopEvent?
}

/** Action passed from Compose to a native desktop notification service. */
@InternalComposeUiApi
data class NativeNotificationAction(val id: String, val label: String)

/** Typed value accepted by a native desktop notification-hints dictionary. */
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

/** Progress values passed from Compose to the native desktop host. */
@InternalComposeUiApi
data class NativeProgressUpdate(
    val totalBytes: ULong,
    val processedBytes: ULong,
    val bytesPerSecond: ULong,
    val elapsedMillis: ULong,
    val percent: UInt,
    val message: String,
)

/** Event delivered from the native desktop host back to Compose. */
@InternalComposeUiApi
sealed interface NativeDesktopEvent {
    /** The user invoked a notification action. */
    data class NotificationAction(val notificationId: UInt, val actionId: String) :
        NativeDesktopEvent

    /** A notification was closed by the desktop service. */
    data class NotificationClosed(val notificationId: UInt, val reason: UInt) : NativeDesktopEvent

    /** The user requested an action for a native progress job. */
    data class ProgressRequested(val path: String, val action: Action) : NativeDesktopEvent {
        /** Action requested by the desktop progress UI. */
        enum class Action {
            /** Request cancellation of the operation. */
            Cancel,

            /** Request suspension of the operation. */
            Suspend,

            /** Request resumption of a suspended operation. */
            Resume,
        }
    }
}

/** Installs native desktop services without coupling Compose UI to a window implementation. */
@InternalComposeUiApi
object NativeDesktopPlatformServicesRegistry {
    private var services: NativeDesktopPlatformServices? = null

    /** Installs [services], or clears the active services when `null`. */
    fun install(services: NativeDesktopPlatformServices?) {
        this.services = services
    }

    internal fun current(): NativeDesktopPlatformServices? = services
}
