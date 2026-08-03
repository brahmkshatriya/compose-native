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
import androidx.compose.ui.platform.LinuxDesktopEvent
import androidx.compose.ui.platform.LinuxNotificationAction
import androidx.compose.ui.platform.LinuxNotificationHint
import androidx.compose.ui.platform.LinuxPlatformServicesRegistry
import androidx.compose.ui.platform.LinuxProgressUpdate
import kotlin.math.roundToInt

/** Creates and remembers a desktop [Notification]. */
@Composable
fun rememberNotification(
    title: String,
    message: String,
    type: Notification.Type = Notification.Type.None,
): Notification = remember(title, message, type) { Notification(title, message, type) }

/** A compact notification model compatible with the Compose Desktop API. */
class Notification(
    val title: String,
    val message: String,
    val type: Type = Type.None,
) {
    fun copy(
        title: String = this.title,
        message: String = this.message,
        type: Type = this.type,
    ) = Notification(title, message, type)

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

    enum class Type { None, Info, Warning, Error }
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

sealed interface NotificationEvent {
    data class ActionInvoked(val actionId: String) : NotificationEvent
    data class Closed(val reason: Reason) : NotificationEvent {
        enum class Reason { Expired, DismissedByUser, ClosedByApplication, Undefined }
    }
}

fun interface NotificationEventSubscription {
    fun dispose()
}

interface NotificationHandle {
    val id: UInt
    fun update(request: NotificationRequest)
    fun close()
    fun addEventListener(listener: (NotificationEvent) -> Unit): NotificationEventSubscription
}

/** Service-provider interface for custom, test, or desktop-specific notification pipelines. */
interface NotificationBackend {
    val isSupported: Boolean
    val capabilities: Set<NotificationCapability>
    fun show(request: NotificationRequest): NotificationHandle
}

/** Default freedesktop.org notification backend supplied by the active Linux window host. */
object PlatformNotificationBackend : NotificationBackend {
    private val handles = mutableMapOf<UInt, PlatformNotificationHandle>()

    override val isSupported: Boolean
        get() = LinuxPlatformServicesRegistry.current()?.areNotificationsSupported() == true

    override val capabilities: Set<NotificationCapability>
        get() {
            val names = LinuxPlatformServicesRegistry.current()?.notificationCapabilities().orEmpty()
            return NotificationCapability.entries.filterTo(mutableSetOf()) { it.protocolName in names }
        }

    override fun show(request: NotificationRequest): NotificationHandle {
        val services = requireLinuxDesktopServices()
        check(services.areNotificationsSupported()) {
            "The current Linux desktop session does not provide org.freedesktop.Notifications"
        }
        val handle = PlatformNotificationHandle(request)
        handle.send(request)
        handles[handle.id] = handle
        return handle
    }

    internal fun dispatch(event: LinuxDesktopEvent) {
        when (event) {
            is LinuxDesktopEvent.NotificationAction ->
                handles[event.notificationId]?.dispatch(NotificationEvent.ActionInvoked(event.actionId))
            is LinuxDesktopEvent.NotificationClosed -> {
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
            val services = requireLinuxDesktopServices()
            val protocolHints = mutableMapOf<String, LinuxNotificationHint>()
            protocolHints["urgency"] =
                LinuxNotificationHint.ByteValue(
                    when (request.type) {
                        Notification.Type.None, Notification.Type.Info -> 0u
                        Notification.Type.Warning -> 1u
                        Notification.Type.Error -> 2u
                    }
                )
            request.progress?.let {
                protocolHints["value"] =
                    LinuxNotificationHint.IntValue((it.coerceIn(0f, 1f) * 100f).roundToInt())
            }
            request.hints.forEach { (name, hint) -> protocolHints[name] = hint.toPlatformHint() }
            id =
                services.sendNotification(
                    applicationName = request.applicationName,
                    title = request.title,
                    message = request.message,
                    iconName = request.iconName.ifEmpty { request.type.defaultIconName },
                    replacesId = if (id != 0u) id else request.replacesId,
                    actions = request.actions.map { LinuxNotificationAction(it.id, it.label) },
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
            requireLinuxDesktopServices().closeNotification(id)
        }

        override fun addEventListener(
            listener: (NotificationEvent) -> Unit,
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

val isNotificationSupported: Boolean
    get() = PlatformNotificationBackend.isSupported

fun sendNotification(
    request: NotificationRequest,
    backend: NotificationBackend = PlatformNotificationBackend,
): NotificationHandle = backend.show(request)

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

data class ProgressJobRequest(
    val title: String,
    val applicationName: String = "Compose",
    val iconName: String = "folder-copy",
    val totalBytes: ULong,
    val cancellable: Boolean = false,
    val suspendable: Boolean = false,
)

data class ProgressJobUpdate(
    val processedBytes: ULong,
    val bytesPerSecond: ULong = 0u,
    val elapsedMillis: ULong = 0u,
    val message: String = "",
)

sealed interface ProgressJobEvent {
    data object CancelRequested : ProgressJobEvent
    data object SuspendRequested : ProgressJobEvent
    data object ResumeRequested : ProgressJobEvent
}

fun interface ProgressJobEventSubscription {
    fun dispose()
}

interface ProgressJobHandle {
    fun update(update: ProgressJobUpdate)
    fun complete()
    fun fail(message: String)
    fun addEventListener(listener: (ProgressJobEvent) -> Unit): ProgressJobEventSubscription
}

interface ProgressJobBackend {
    val isSupported: Boolean
    fun start(request: ProgressJobRequest): ProgressJobHandle
}

/** Uses Plasma JobView when available and a replaceable notification everywhere else. */
object PlatformProgressJobBackend : ProgressJobBackend {
    private val handles = mutableMapOf<String, PlatformProgressJobHandle>()

    override val isSupported: Boolean
        get() {
            val services = LinuxPlatformServicesRegistry.current() ?: return false
            return services.isProgressServiceSupported() || services.areNotificationsSupported()
        }

    override fun start(request: ProgressJobRequest): ProgressJobHandle {
        val services = requireLinuxDesktopServices()
        if (!services.isProgressServiceSupported()) return NotificationProgressJobBackend.start(request)
        val capabilities = (if (request.cancellable) 1 else 0) or (if (request.suspendable) 2 else 0)
        val path = services.startProgressJob(request.applicationName, request.iconName, capabilities)
        val handle = PlatformProgressJobHandle(path, request)
        handles[path] = handle
        handle.update(ProgressJobUpdate(0u))
        return handle
    }

    internal fun dispatch(event: LinuxDesktopEvent.ProgressRequested) {
        val handle = handles[event.path] ?: return
        handle.dispatch(
            when (event.action) {
                LinuxDesktopEvent.ProgressRequested.Action.Cancel -> ProgressJobEvent.CancelRequested
                LinuxDesktopEvent.ProgressRequested.Action.Suspend -> ProgressJobEvent.SuspendRequested
                LinuxDesktopEvent.ProgressRequested.Action.Resume -> ProgressJobEvent.ResumeRequested
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
                else ((update.processedBytes.toDouble() / request.totalBytes.toDouble()) * 100.0)
                    .roundToInt().coerceIn(0, 100).toUInt()
            requireLinuxDesktopServices().updateProgressJob(
                path,
                LinuxProgressUpdate(
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
            requireLinuxDesktopServices().terminateProgressJob(path, errorMessage)
        }

        override fun addEventListener(
            listener: (ProgressJobEvent) -> Unit,
        ): ProgressJobEventSubscription {
            listeners += listener
            return ProgressJobEventSubscription { listeners -= listener }
        }

        fun dispatch(event: ProgressJobEvent) {
            listeners.toList().forEach { it(event) }
        }
    }

    private class NotificationProgressJobHandle(
        private val request: ProgressJobRequest,
    ) : ProgressJobHandle {
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
                buildNotification(lastUpdate).copy(
                    message = "Completed",
                    progress = 1f,
                    actions = emptyList(),
                    timeoutMillis = 3000,
                )
            )
        }

        override fun fail(message: String) {
            notification.update(
                buildNotification(lastUpdate).copy(
                    message = message,
                    type = Notification.Type.Error,
                    progress = null,
                    actions = emptyList(),
                )
            )
        }

        override fun addEventListener(
            listener: (ProgressJobEvent) -> Unit,
        ): ProgressJobEventSubscription {
            listeners += listener
            return ProgressJobEventSubscription { listeners -= listener }
        }

        private fun buildNotification(update: ProgressJobUpdate): NotificationRequest {
            val progress =
                if (request.totalBytes == 0uL) null
                else (update.processedBytes.toDouble() / request.totalBytes.toDouble())
                    .toFloat().coerceIn(0f, 1f)
            val percent = progress?.let { "${(it * 100f).roundToInt()}%" }.orEmpty()
            val speed = update.bytesPerSecond.takeIf { it > 0u }?.let { formatByteRate(it) }.orEmpty()
            val status = listOf(update.message, percent, speed).filter { it.isNotEmpty() }.joinToString(" · ")
            return NotificationRequest(
                title = request.title,
                message = status,
                applicationName = request.applicationName,
                iconName = request.iconName,
                actions = if (request.cancellable) listOf(NotificationAction("cancel", "Cancel")) else emptyList(),
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

fun startProgressJob(
    request: ProgressJobRequest,
    backend: ProgressJobBackend = PlatformProgressJobBackend,
): ProgressJobHandle = backend.start(request)

@InternalComposeUiApi
fun dispatchLinuxDesktopEvents() {
    val services = LinuxPlatformServicesRegistry.current() ?: return
    while (true) {
        when (val event = services.pollDesktopEvent() ?: return) {
            is LinuxDesktopEvent.ProgressRequested -> PlatformProgressJobBackend.dispatch(event)
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

private fun NotificationHint.toPlatformHint(): LinuxNotificationHint =
    when (this) {
        is NotificationHint.ByteValue -> LinuxNotificationHint.ByteValue(value)
        is NotificationHint.IntValue -> LinuxNotificationHint.IntValue(value)
        is NotificationHint.UIntValue -> LinuxNotificationHint.UIntValue(value)
        is NotificationHint.LongValue -> LinuxNotificationHint.LongValue(value)
        is NotificationHint.ULongValue -> LinuxNotificationHint.ULongValue(value)
        is NotificationHint.DoubleValue -> LinuxNotificationHint.DoubleValue(value)
        is NotificationHint.BooleanValue -> LinuxNotificationHint.BooleanValue(value)
        is NotificationHint.StringValue -> LinuxNotificationHint.StringValue(value)
        else -> error(
            "PlatformNotificationBackend does not understand ${this::class}; " +
                "pass this hint to a custom NotificationBackend"
        )
    }

private fun requireLinuxDesktopServices() =
    checkNotNull(LinuxPlatformServicesRegistry.current()) {
        "Linux desktop services are only available inside application { ... }"
    }

private fun formatByteRate(bytesPerSecond: ULong): String {
    val units = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val shown = if (value >= 10.0 || unit == 0) value.roundToInt().toString() else ((value * 10).roundToInt() / 10.0).toString()
    return "$shown ${units[unit]}"
}
