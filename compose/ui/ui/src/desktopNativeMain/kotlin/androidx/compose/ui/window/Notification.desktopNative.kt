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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.NativeDesktopEvent
import androidx.compose.ui.platform.NativeDesktopPlatformServicesRegistry
import androidx.compose.ui.platform.NativeNotificationAction
import androidx.compose.ui.platform.NativeNotificationHint
import androidx.compose.ui.platform.NativeProgressUpdate
import kotlin.math.roundToInt

/**
 * Creates a desktop [Notification] that is remembered across compositions.
 *
 * @param title title of the notification
 * @param message main text of the notification
 * @param type type used by the platform to choose presentation such as icon and urgency
 */
@Composable
fun rememberNotification(
    title: String,
    message: String,
    type: Notification.Type = Notification.Type.None,
): Notification = remember(title, message, type) { Notification(title, message, type) }

/**
 * Notification that can be sent to the desktop and shown to the user.
 *
 * When creating a notification from a composable, prefer [rememberNotification] to avoid creating
 * a new instance on every recomposition.
 *
 * @param title title of the notification
 * @param message main text of the notification
 * @param type type used by the platform to choose presentation such as icon and urgency
 */
class Notification(val title: String, val message: String, val type: Type = Type.None) {
    /** Returns a copy of this notification, optionally replacing its fields. */
    fun copy(title: String = this.title, message: String = this.message, type: Type = this.type) =
        Notification(title, message, type)

    override fun toString() = "Notification(title=$title, message=$message, type=$type)"

    override fun equals(other: Any?): Boolean =
        other is Notification &&
            title == other.title &&
            message == other.message &&
            type == other.type

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    /** Describes the presentation type of a notification. */
    enum class Type {
        /** Notification without a specific semantic type. */
        None,

        /** Informational notification. */
        Info,

        /** Warning notification. */
        Warning,

        /** Error notification. */
        Error,
    }
}

/** A user-visible action attached to a notification. */
data class NotificationAction(val id: String, val label: String)

/** Typed values accepted by the D-Bus `a{sv}` notification-hints dictionary. */
interface NotificationHint {
    data class ByteValue(val value: UByte) : NotificationHint

    data class IntValue(val value: Int) : NotificationHint

    data class UIntValue(val value: UInt) : NotificationHint

    data class LongValue(val value: Long) : NotificationHint

    data class ULongValue(val value: ULong) : NotificationHint

    data class DoubleValue(val value: Double) : NotificationHint

    data class BooleanValue(val value: Boolean) : NotificationHint

    data class StringValue(val value: String) : NotificationHint
}

/** Optional features reported by a desktop notification server. */
enum class NotificationCapability(internal val protocolName: String) {
    Actions("actions"),
    ActionIcons("action-icons"),
    Body("body"),
    BodyHyperlinks("body-hyperlinks"),
    BodyImages("body-images"),
    BodyMarkup("body-markup"),
    IconStatic("icon-static"),
    IconMulti("icon-multi"),
    Persistence("persistence"),
    Sound("sound"),
}

/** Full extensible request passed to a [NotificationBackend]. */
data class NotificationRequest(
    val title: String,
    val message: String = "",
    val applicationName: String = "Compose",
    val type: Notification.Type = Notification.Type.None,
    val iconName: String = "",
    val actions: List<NotificationAction> = emptyList(),
    val hints: Map<String, NotificationHint> = emptyMap(),
    val progress: Float? = null,
    val timeoutMillis: Int = -1,
    val replacesId: UInt = 0u,
)

/** Event reported by an active [NotificationHandle]. */
sealed interface NotificationEvent {
    /** The user invoked the notification action identified by [actionId]. */
    data class ActionInvoked(val actionId: String) : NotificationEvent

    /** The notification was closed by the desktop notification service. */
    data class Closed(val reason: Reason) : NotificationEvent {
        /** Reason reported by the desktop for closing a notification. */
        enum class Reason {
            /** The notification expired according to its timeout. */
            Expired,

            /** The user dismissed the notification. */
            DismissedByUser,

            /** The application requested that the notification close. */
            ClosedByApplication,

            /** The desktop did not provide a recognized reason. */
            Undefined,
        }
    }
}

/** Subscription returned by [NotificationHandle.addEventListener]. */
fun interface NotificationEventSubscription {
    /** Stops delivering events to the associated listener. */
    fun dispose()
}

/**
 * Handle to a notification currently managed by a [NotificationBackend].
 *
 * A handle can update or close the notification and observe user actions until the notification is
 * closed by the desktop.
 */
interface NotificationHandle {
    /** Identifier assigned by the backend, or `0u` after the notification is no longer active. */
    val id: UInt

    /** Replaces the current notification contents with [request]. */
    fun update(request: NotificationRequest)

    /** Requests that the notification close. */
    fun close()

    /** Adds an event listener and returns a subscription that removes it. */
    fun addEventListener(listener: (NotificationEvent) -> Unit): NotificationEventSubscription
}

/** Service-provider interface for custom, test, or desktop-specific notification pipelines. */
interface NotificationBackend {
    /** Whether this backend can currently show notifications. */
    val isSupported: Boolean

    /** Optional notification features supported by this backend. */
    val capabilities: Set<NotificationCapability>

    /** Shows [request] and returns a handle used to manage the resulting notification. */
    fun show(request: NotificationRequest): NotificationHandle
}

/** Default notification backend supplied by the active native desktop window host. */
object PlatformNotificationBackend : NotificationBackend {
    private val handles = mutableMapOf<UInt, PlatformNotificationHandle>()

    override val isSupported: Boolean
        get() = NativeDesktopPlatformServicesRegistry.current()?.areNotificationsSupported() == true

    override val capabilities: Set<NotificationCapability>
        get() {
            val names =
                NativeDesktopPlatformServicesRegistry.current()
                    ?.notificationCapabilities()
                    .orEmpty()
            return NotificationCapability.entries.filterTo(mutableSetOf()) {
                it.protocolName in names
            }
        }

    override fun show(request: NotificationRequest): NotificationHandle {
        val services = requireNativeDesktopServices()
        check(services.areNotificationsSupported()) {
            "The current native desktop host does not provide system notifications"
        }
        val handle = PlatformNotificationHandle(request)
        handle.send(request)
        handles[handle.id] = handle
        return handle
    }

    internal fun dispatch(event: NativeDesktopEvent) {
        when (event) {
            is NativeDesktopEvent.NotificationAction ->
                handles[event.notificationId]?.dispatch(
                    NotificationEvent.ActionInvoked(event.actionId)
                )
            is NativeDesktopEvent.NotificationClosed -> {
                val handle = handles.remove(event.notificationId) ?: return
                val reason =
                    when (event.reason) {
                        1u -> NotificationEvent.Closed.Reason.Expired
                        2u -> NotificationEvent.Closed.Reason.DismissedByUser
                        3u -> NotificationEvent.Closed.Reason.ClosedByApplication
                        else -> NotificationEvent.Closed.Reason.Undefined
                    }
                handle.dispatch(NotificationEvent.Closed(reason))
            }
            else -> Unit
        }
    }

    private class PlatformNotificationHandle(initialRequest: NotificationRequest) :
        NotificationHandle {
        private val listeners = mutableListOf<(NotificationEvent) -> Unit>()
        private var lastRequest = initialRequest
        override var id: UInt = 0u
            private set

        fun send(request: NotificationRequest) {
            val services = requireNativeDesktopServices()
            val protocolHints = mutableMapOf<String, NativeNotificationHint>()
            protocolHints["urgency"] =
                NativeNotificationHint.ByteValue(
                    when (request.type) {
                        Notification.Type.None,
                        Notification.Type.Info -> 0u
                        Notification.Type.Warning -> 1u
                        Notification.Type.Error -> 2u
                    }
                )
            request.progress?.let {
                protocolHints["value"] =
                    NativeNotificationHint.IntValue((it.coerceIn(0f, 1f) * 100f).roundToInt())
            }
            request.hints.forEach { (name, hint) -> protocolHints[name] = hint.toPlatformHint() }
            id =
                services.sendNotification(
                    applicationName = request.applicationName,
                    title = request.title,
                    message = request.message,
                    iconName = request.iconName.ifEmpty { request.type.defaultIconName },
                    replacesId = if (id != 0u) id else request.replacesId,
                    actions = request.actions.map { NativeNotificationAction(it.id, it.label) },
                    hints = protocolHints,
                    timeoutMillis = request.timeoutMillis,
                )
            lastRequest = request
        }

        override fun update(request: NotificationRequest) {
            check(id != 0u) { "This notification is no longer active" }
            val previousId = id
            send(request.copy(replacesId = previousId))
            if (id != previousId) {
                handles.remove(previousId)
                handles[id] = this
            }
        }

        override fun close() {
            if (id == 0u) return
            requireNativeDesktopServices().closeNotification(id)
        }

        override fun addEventListener(
            listener: (NotificationEvent) -> Unit
        ): NotificationEventSubscription {
            listeners += listener
            return NotificationEventSubscription { listeners -= listener }
        }

        fun dispatch(event: NotificationEvent) {
            listeners.toList().forEach { it(event) }
            if (event is NotificationEvent.Closed) id = 0u
        }
    }
}

/** `true` when the active native desktop host can show system notifications. */
val isNotificationSupported: Boolean
    get() = PlatformNotificationBackend.isSupported

/**
 * Shows [request] using [backend].
 *
 * @return a handle that can update, close, and observe the notification
 */
fun sendNotification(
    request: NotificationRequest,
    backend: NotificationBackend = PlatformNotificationBackend,
): NotificationHandle = backend.show(request)

/**
 * Shows a Compose Desktop-compatible [notification] using [backend].
 *
 * @param notification notification contents to show
 * @param applicationName application name reported to the desktop notification service
 * @param timeoutMillis requested timeout in milliseconds, or `-1` to use the server default
 * @param backend notification backend to use
 * @return a handle that can update, close, and observe the notification
 */
fun sendNotification(
    notification: Notification,
    applicationName: String = "Compose",
    timeoutMillis: Int = -1,
    backend: NotificationBackend = PlatformNotificationBackend,
): NotificationHandle =
    backend.show(
        NotificationRequest(
            title = notification.title,
            message = notification.message,
            applicationName = applicationName,
            type = notification.type,
            timeoutMillis = timeoutMillis,
        )
    )

/**
 * Describes a long-running operation that can be exposed through desktop progress UI.
 *
 * @param title user-visible title of the operation
 * @param applicationName application name reported to the desktop service
 * @param iconName platform icon name associated with the operation
 * @param totalBytes total amount of work in bytes, or `0uL` when the total is unknown
 * @param cancellable whether the desktop may offer a cancel action
 * @param suspendable whether the desktop may offer suspend and resume actions
 */
data class ProgressJobRequest(
    val title: String,
    val applicationName: String = "Compose",
    val iconName: String = "folder-copy",
    val totalBytes: ULong,
    val cancellable: Boolean = false,
    val suspendable: Boolean = false,
)

/**
 * Current progress of a [ProgressJobHandle].
 *
 * @param processedBytes amount of work completed so far
 * @param bytesPerSecond current transfer or processing rate, when known
 * @param elapsedMillis elapsed operation time in milliseconds, when known
 * @param message optional user-visible status text
 */
data class ProgressJobUpdate(
    val processedBytes: ULong,
    val bytesPerSecond: ULong = 0u,
    val elapsedMillis: ULong = 0u,
    val message: String = "",
)

/** User action requested through desktop progress UI. */
sealed interface ProgressJobEvent {
    /** The user requested cancellation. */
    data object CancelRequested : ProgressJobEvent

    /** The user requested that the operation be suspended. */
    data object SuspendRequested : ProgressJobEvent

    /** The user requested that a suspended operation resume. */
    data object ResumeRequested : ProgressJobEvent
}

/** Subscription returned by [ProgressJobHandle.addEventListener]. */
fun interface ProgressJobEventSubscription {
    /** Stops delivering events to the associated listener. */
    fun dispose()
}

/** Handle to a progress job currently managed by a [ProgressJobBackend]. */
interface ProgressJobHandle {
    /** Updates the progress shown by the desktop. */
    fun update(update: ProgressJobUpdate)

    /** Marks the operation as completed successfully. */
    fun complete()

    /** Marks the operation as failed and presents [message] when supported. */
    fun fail(message: String)

    /** Adds an event listener and returns a subscription that removes it. */
    fun addEventListener(listener: (ProgressJobEvent) -> Unit): ProgressJobEventSubscription
}

/** Service-provider interface for desktop progress-job implementations. */
interface ProgressJobBackend {
    /** Whether this backend can currently present progress jobs. */
    val isSupported: Boolean

    /** Starts [request] and returns a handle used to update and finish the operation. */
    fun start(request: ProgressJobRequest): ProgressJobHandle
}

/** Uses Plasma JobView when available and a replaceable notification everywhere else. */
object PlatformProgressJobBackend : ProgressJobBackend {
    private val handles = mutableMapOf<String, PlatformProgressJobHandle>()

    override val isSupported: Boolean
        get() {
            val services = NativeDesktopPlatformServicesRegistry.current() ?: return false
            return services.isProgressServiceSupported() || services.areNotificationsSupported()
        }

    override fun start(request: ProgressJobRequest): ProgressJobHandle {
        val services = requireNativeDesktopServices()
        if (!services.isProgressServiceSupported())
            return NotificationProgressJobBackend.start(request)
        val capabilities =
            (if (request.cancellable) 1 else 0) or (if (request.suspendable) 2 else 0)
        val path =
            services.startProgressJob(request.applicationName, request.iconName, capabilities)
        val handle = PlatformProgressJobHandle(path, request)
        handles[path] = handle
        handle.update(ProgressJobUpdate(0u))
        return handle
    }

    internal fun dispatch(event: NativeDesktopEvent.ProgressRequested) {
        val handle = handles[event.path] ?: return
        handle.dispatch(
            when (event.action) {
                NativeDesktopEvent.ProgressRequested.Action.Cancel ->
                    ProgressJobEvent.CancelRequested
                NativeDesktopEvent.ProgressRequested.Action.Suspend ->
                    ProgressJobEvent.SuspendRequested
                NativeDesktopEvent.ProgressRequested.Action.Resume ->
                    ProgressJobEvent.ResumeRequested
            }
        )
    }

    internal fun startNotificationFallback(request: ProgressJobRequest): ProgressJobHandle =
        NotificationProgressJobHandle(request)

    private class PlatformProgressJobHandle(
        private val path: String,
        private val request: ProgressJobRequest,
    ) : ProgressJobHandle {
        private val listeners = mutableListOf<(ProgressJobEvent) -> Unit>()
        private var active = true

        override fun update(update: ProgressJobUpdate) {
            check(active) { "This progress job has finished" }
            val percent =
                if (request.totalBytes == 0uL) 0u
                else
                    ((update.processedBytes.toDouble() / request.totalBytes.toDouble()) * 100.0)
                        .roundToInt()
                        .coerceIn(0, 100)
                        .toUInt()
            requireNativeDesktopServices()
                .updateProgressJob(
                    path,
                    NativeProgressUpdate(
                        totalBytes = request.totalBytes,
                        processedBytes = update.processedBytes,
                        bytesPerSecond = update.bytesPerSecond,
                        elapsedMillis = update.elapsedMillis,
                        percent = percent,
                        message = update.message.ifEmpty { request.title },
                    ),
                )
        }

        override fun complete() = terminate("")

        override fun fail(message: String) = terminate(message)

        private fun terminate(errorMessage: String) {
            if (!active) return
            active = false
            handles.remove(path)
            requireNativeDesktopServices().terminateProgressJob(path, errorMessage)
        }

        override fun addEventListener(
            listener: (ProgressJobEvent) -> Unit
        ): ProgressJobEventSubscription {
            listeners += listener
            return ProgressJobEventSubscription { listeners -= listener }
        }

        fun dispatch(event: ProgressJobEvent) {
            listeners.toList().forEach { it(event) }
        }
    }

    private class NotificationProgressJobHandle(private val request: ProgressJobRequest) :
        ProgressJobHandle {
        private val listeners = mutableListOf<(ProgressJobEvent) -> Unit>()
        private var lastUpdate = ProgressJobUpdate(0u)
        private val notification =
            sendNotification(buildNotification(lastUpdate)).also { handle ->
                handle.addEventListener { event ->
                    if (event is NotificationEvent.ActionInvoked && event.actionId == "cancel") {
                        listeners.toList().forEach { it(ProgressJobEvent.CancelRequested) }
                    }
                }
            }

        override fun update(update: ProgressJobUpdate) {
            lastUpdate = update
            notification.update(buildNotification(update))
        }

        override fun complete() {
            notification.update(
                buildNotification(lastUpdate)
                    .copy(
                        message = "Completed",
                        progress = 1f,
                        actions = emptyList(),
                        timeoutMillis = 3000,
                    )
            )
        }

        override fun fail(message: String) {
            notification.update(
                buildNotification(lastUpdate)
                    .copy(
                        message = message,
                        type = Notification.Type.Error,
                        progress = null,
                        actions = emptyList(),
                    )
            )
        }

        override fun addEventListener(
            listener: (ProgressJobEvent) -> Unit
        ): ProgressJobEventSubscription {
            listeners += listener
            return ProgressJobEventSubscription { listeners -= listener }
        }

        private fun buildNotification(update: ProgressJobUpdate): NotificationRequest {
            val progress =
                if (request.totalBytes == 0uL) null
                else
                    (update.processedBytes.toDouble() / request.totalBytes.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
            val percent = progress?.let { "${(it * 100f).roundToInt()}%" }.orEmpty()
            val speed =
                update.bytesPerSecond.takeIf { it > 0u }?.let { formatByteRate(it) }.orEmpty()
            val status =
                listOf(update.message, percent, speed)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
            return NotificationRequest(
                title = request.title,
                message = status,
                applicationName = request.applicationName,
                iconName = request.iconName,
                actions =
                    if (request.cancellable) listOf(NotificationAction("cancel", "Cancel"))
                    else emptyList(),
                progress = progress,
                timeoutMillis = 0,
            )
        }
    }
}

/** Explicit portable fallback that represents progress with replaceable desktop notifications. */
object NotificationProgressJobBackend : ProgressJobBackend {
    override val isSupported: Boolean
        get() = PlatformNotificationBackend.isSupported

    override fun start(request: ProgressJobRequest): ProgressJobHandle {
        check(isSupported) { "Desktop notifications are unavailable" }
        return PlatformProgressJobBackend.startNotificationFallback(request)
    }
}

/**
 * Starts a desktop progress job using [backend].
 *
 * @return a handle used to update, finish, and observe the operation
 */
fun startProgressJob(
    request: ProgressJobRequest,
    backend: ProgressJobBackend = PlatformProgressJobBackend,
): ProgressJobHandle = backend.start(request)

/**
 * Delivers pending native desktop events to active notification and progress-job handles.
 *
 * This is called by the native desktop host as part of its event loop.
 */
@InternalComposeUiApi
fun dispatchNativeDesktopEvents() {
    val services = NativeDesktopPlatformServicesRegistry.current() ?: return
    while (true) {
        when (val event = services.pollDesktopEvent() ?: return) {
            is NativeDesktopEvent.ProgressRequested -> PlatformProgressJobBackend.dispatch(event)
            else -> PlatformNotificationBackend.dispatch(event)
        }
    }
}

private val Notification.Type.defaultIconName: String
    get() =
        when (this) {
            Notification.Type.None -> ""
            Notification.Type.Info -> "dialog-information"
            Notification.Type.Warning -> "dialog-warning"
            Notification.Type.Error -> "dialog-error"
        }

private fun NotificationHint.toPlatformHint(): NativeNotificationHint =
    when (this) {
        is NotificationHint.ByteValue -> NativeNotificationHint.ByteValue(value)
        is NotificationHint.IntValue -> NativeNotificationHint.IntValue(value)
        is NotificationHint.UIntValue -> NativeNotificationHint.UIntValue(value)
        is NotificationHint.LongValue -> NativeNotificationHint.LongValue(value)
        is NotificationHint.ULongValue -> NativeNotificationHint.ULongValue(value)
        is NotificationHint.DoubleValue -> NativeNotificationHint.DoubleValue(value)
        is NotificationHint.BooleanValue -> NativeNotificationHint.BooleanValue(value)
        is NotificationHint.StringValue -> NativeNotificationHint.StringValue(value)
        else ->
            error(
                "PlatformNotificationBackend does not understand ${this::class}; " +
                    "pass this hint to a custom NotificationBackend"
            )
    }

private fun requireNativeDesktopServices() =
    checkNotNull(NativeDesktopPlatformServicesRegistry.current()) {
        "Native desktop services are only available inside application { ... }"
    }

private fun formatByteRate(bytesPerSecond: ULong): String {
    val units = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val shown =
        if (value >= 10.0 || unit == 0) value.roundToInt().toString()
        else ((value * 10).roundToInt() / 10.0).toString()
    return "$shown ${units[unit]}"
}
