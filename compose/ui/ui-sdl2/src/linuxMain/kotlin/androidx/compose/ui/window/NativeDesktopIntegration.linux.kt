@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    org.jetbrains.skiko.InternalSkikoApi::class,
)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LinuxDesktopEvent
import androidx.compose.ui.platform.LinuxNotificationAction
import androidx.compose.ui.platform.LinuxNotificationHint
import androidx.compose.ui.platform.LinuxPlatformServices
import androidx.compose.ui.platform.LinuxPlatformServicesRegistry
import androidx.compose.ui.platform.LinuxProgressUpdate
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cnames.structs.SDL_Window
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import linuxdesktop.*
import org.jetbrains.skiko.GpuPriority
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.updateLinuxSystemTheme
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.usleep
import sdl2.SDL_GLAttr
import sdl2.SDL_GL_SetAttribute
import sdl2.SDL_GetClipboardText
import sdl2.SDL_GetError
import sdl2.SDL_OpenURL
import sdl2.SDL_SetClipboardText
import sdl2.SDL_WINDOW_OPENGL
import sdl2.SDL_free

internal typealias NativeAccessibility = LinuxAtSpiAccessibility

fun TrayState.sendNotification(notification: Notification) {
    androidx.compose.ui.window.sendNotification(notification)
}

internal val NativeDesktopSelfTestContent: @Composable ApplicationScope.() -> Unit = {
    val applicationScope = this
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(320.dp, 200.dp)),
        visible = false,
        title = "Native desktop integration test",
    ) {
        LaunchedEffect(Unit) {
            check(isNotificationSupported) { "Desktop notifications are unavailable" }
            val notification =
                sendNotification(
                    NotificationRequest(
                        title = "Native Compose self-test",
                        message = "Initial notification",
                        applicationName = "Native Compose",
                        actions = listOf(NotificationAction("default", "Open")),
                        hints =
                            mapOf(
                                "transient" to NotificationHint.BooleanValue(true),
                                "desktop-entry" to NotificationHint.StringValue("ktnative"),
                                "value" to NotificationHint.IntValue(10),
                            ),
                        progress = 0.1f,
                        timeoutMillis = 3000,
                    )
                )
            val originalId = notification.id
            var closeReason: NotificationEvent.Closed.Reason? = null
            notification.addEventListener { event ->
                if (event is NotificationEvent.Closed) closeReason = event.reason
            }
            notification.update(
                NotificationRequest(
                    title = "Native Compose self-test",
                    message = "Updated in place",
                    applicationName = "Native Compose",
                    progress = 0.6f,
                    timeoutMillis = 3000,
                )
            )
            check(notification.id == originalId) { "Notification replacement changed its ID" }

            val progress =
                startProgressJob(
                    ProgressJobRequest(
                        title = "Testing native progress",
                        applicationName = "Native Compose",
                        totalBytes = 64uL * 1024uL * 1024uL,
                        cancellable = true,
                    )
                )
            repeat(5) { sample ->
                progress.update(
                    ProgressJobUpdate(
                        processedBytes = (sample + 1).toULong() * 64uL * 1024uL * 1024uL / 5uL,
                        bytesPerSecond = (40 + sample * 7).toULong() * 1024uL * 1024uL,
                        elapsedMillis = (sample * 50).toULong(),
                        message = "Testing update ${sample + 1}/5",
                    )
                )
                delay(50)
            }
            progress.complete()

            val fallbackProgress =
                startProgressJob(
                    ProgressJobRequest(
                        title = "Testing portable progress fallback",
                        applicationName = "Native Compose",
                        totalBytes = 10uL,
                    ),
                    backend = NotificationProgressJobBackend,
                )
            fallbackProgress.update(
                ProgressJobUpdate(processedBytes = 7uL, bytesPerSecond = 1024uL * 1024uL)
            )
            fallbackProgress.complete()
            notification.close()
            repeat(50) {
                if (closeReason != null) return@repeat
                delay(10)
            }
            check(closeReason == NotificationEvent.Closed.Reason.ClosedByApplication) {
                "Notification close event was not dispatched: $closeReason"
            }
            println(
                "Native desktop self-test passed: capabilities, typed hints, actions, " +
                    "replacement, close, native progress, and notification progress fallback"
            )
            applicationScope.exitApplication()
        }
    }
}

internal fun nativeGetEnvironmentVariable(name: String): String? = getenv(name)?.toKString()

internal fun nativeSleepMicroseconds(value: UInt) {
    usleep(value)
}

internal fun configureNativeSdlEnvironment() {
    if (getenv("WAYLAND_DISPLAY") != null) setenv("SDL_VIDEODRIVER", "wayland", 0)
}

internal fun configureNativeGraphics(layer: SkiaLayer) {
    if (getenv("DRI_PRIME") == null) {
        when (layer.properties.adapterPriority) {
            GpuPriority.Integrated -> setenv("DRI_PRIME", "0", 0)
            GpuPriority.Discrete -> setenv("DRI_PRIME", "1", 0)
            GpuPriority.Auto -> Unit
        }
    }
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_MAJOR_VERSION, 3)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_MINOR_VERSION, 3)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_PROFILE_MASK, 0x0001)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_DOUBLEBUFFER, 1)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_ALPHA_SIZE, 8)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_DEPTH_SIZE, 0)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_STENCIL_SIZE, 8)
}

internal fun nativeGraphicsWindowFlags(layer: SkiaLayer): ULong =
    if (layer.renderApi == org.jetbrains.skiko.GraphicsApi.OPENGL) SDL_WINDOW_OPENGL else 0uL

internal fun attachNativeSkiaLayer(
    layer: SkiaLayer,
    window: CPointer<SDL_Window>,
    transparency: Boolean,
    queryContentScale: () -> Float,
    queryFullscreen: () -> Boolean,
    updateFullscreen: (Boolean) -> Unit,
    onRenderRequested: () -> Unit,
) {
    layer.attachTo(
        SdlSkiaLayerComponent(
            window = window,
            transparency = transparency,
            queryContentScale = queryContentScale,
            queryFullscreen = queryFullscreen,
            updateFullscreen = updateFullscreen,
            onRenderRequested = onRenderRequested,
        )
    )
}

internal object NativeDesktopIntegration {
    fun install() {
        LinuxPlatformServicesRegistry.install(LinuxSdlPlatformServices)
    }

    fun close() {
        LinuxPlatformServicesRegistry.install(null)
        kld_atspi_shutdown()
        kld_shutdown()
    }

    fun pollEvents() {
        dispatchLinuxDesktopEvents()
    }

    fun pollAccessibility(): Boolean = kld_atspi_poll() != 0

    fun updateSystemTheme(dark: Boolean?) {
        updateLinuxSystemTheme(
            when (dark) {
                true -> SystemTheme.DARK
                false -> SystemTheme.LIGHT
                null -> SystemTheme.UNKNOWN
            }
        )
    }
}

private object LinuxSdlPlatformServices : LinuxPlatformServices {
    override fun getClipboardText(): String? {
        val text = SDL_GetClipboardText() ?: return null
        return try {
            text.toKString()
        } finally {
            SDL_free(text)
        }
    }

    override fun setClipboardText(text: String) {
        check(SDL_SetClipboardText(text)) {
            "Could not set clipboard text: ${SDL_GetError()?.toKString()}"
        }
    }

    override fun openUri(uri: String) {
        check(SDL_OpenURL(uri)) { "Could not open URI '$uri': ${SDL_GetError()?.toKString()}" }
    }

    override fun areNotificationsSupported(): Boolean = kld_notifications_supported() != 0

    override fun notificationCapabilities(): Set<String> = memScoped {
        if (!areNotificationsSupported()) return@memScoped emptySet()
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        val capabilities = kld_notification_capabilities(errorPointer.ptr)
        checkDesktopError(errorPointer.value, "query notification capabilities")
        if (capabilities == null) return@memScoped emptySet()
        try {
            capabilities.toKString().lineSequence().filter(String::isNotEmpty).toSet()
        } finally {
            kld_free_string(capabilities)
        }
    }

    override fun sendNotification(
        applicationName: String,
        title: String,
        message: String,
        iconName: String,
        replacesId: UInt,
        actions: List<LinuxNotificationAction>,
        hints: Map<String, LinuxNotificationHint>,
        timeoutMillis: Int,
    ): UInt = memScoped {
        val builder =
            checkNotNull(
                kld_notification_create(
                    applicationName,
                    title,
                    message,
                    iconName,
                    replacesId,
                    timeoutMillis,
                )
            ) {
                "Could not allocate a desktop notification"
            }
        try {
            actions.forEach { action ->
                check(kld_notification_add_action(builder, action.id, action.label) != 0) {
                    "Could not add notification action '${action.id}'"
                }
            }
            hints.forEach { (name, hint) ->
                val added =
                    when (hint) {
                        is LinuxNotificationHint.ByteValue ->
                            kld_notification_add_hint_byte(builder, name, hint.value)
                        is LinuxNotificationHint.IntValue ->
                            kld_notification_add_hint_int32(builder, name, hint.value)
                        is LinuxNotificationHint.UIntValue ->
                            kld_notification_add_hint_uint32(builder, name, hint.value)
                        is LinuxNotificationHint.LongValue ->
                            kld_notification_add_hint_int64(builder, name, hint.value)
                        is LinuxNotificationHint.ULongValue ->
                            kld_notification_add_hint_uint64(builder, name, hint.value)
                        is LinuxNotificationHint.DoubleValue ->
                            kld_notification_add_hint_double(builder, name, hint.value)
                        is LinuxNotificationHint.BooleanValue ->
                            kld_notification_add_hint_bool(builder, name, if (hint.value) 1 else 0)
                        is LinuxNotificationHint.StringValue ->
                            kld_notification_add_hint_string(builder, name, hint.value)
                    }
                check(added != 0) { "Could not add notification hint '$name'" }
            }
            val errorPointer = alloc<CPointerVar<ByteVar>>()
            errorPointer.value = null
            val id = kld_notification_send(builder, errorPointer.ptr)
            checkDesktopError(errorPointer.value, "send desktop notification")
            check(id != 0u) { "The notification service returned an invalid identifier" }
            id
        } finally {
            kld_notification_destroy(builder)
        }
    }

    override fun closeNotification(id: UInt) = memScoped {
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        check(kld_notification_close(id, errorPointer.ptr) != 0) {
            checkDesktopError(errorPointer.value, "close desktop notification")
            "Could not close desktop notification"
        }
        checkDesktopError(errorPointer.value, "close desktop notification")
    }

    override fun isProgressServiceSupported(): Boolean = kld_progress_supported() != 0

    override fun startProgressJob(
        applicationName: String,
        iconName: String,
        capabilities: Int,
    ): String = memScoped {
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        val path = kld_progress_start(applicationName, iconName, capabilities, errorPointer.ptr)
        checkDesktopError(errorPointer.value, "start desktop progress job")
        checkNotNull(path) { "The desktop progress service returned no object path" }
            .let {
                try {
                    it.toKString()
                } finally {
                    kld_free_string(it)
                }
            }
    }

    override fun updateProgressJob(path: String, update: LinuxProgressUpdate) = memScoped {
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        val updated =
            kld_progress_update(
                path,
                update.totalBytes,
                update.processedBytes,
                update.bytesPerSecond,
                update.elapsedMillis,
                update.percent,
                update.message,
                errorPointer.ptr,
            )
        checkDesktopError(errorPointer.value, "update desktop progress job")
        check(updated != 0) { "Could not update desktop progress job" }
    }

    override fun terminateProgressJob(path: String, errorMessage: String) = memScoped {
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        val terminated = kld_progress_terminate(path, errorMessage, errorPointer.ptr)
        checkDesktopError(errorPointer.value, "terminate desktop progress job")
        check(terminated != 0) { "Could not terminate desktop progress job" }
    }

    override fun pollDesktopEvent(): LinuxDesktopEvent? = memScoped {
        val id = alloc<UIntVar>()
        val reason = alloc<UIntVar>()
        val value = alloc<CPointerVar<ByteVar>>()
        id.value = 0u
        reason.value = 0u
        value.value = null
        val type = kld_poll_event(id.ptr, reason.ptr, value.ptr)
        val text =
            value.value
                ?.let {
                    try {
                        it.toKString()
                    } finally {
                        kld_free_string(it)
                    }
                }
                .orEmpty()
        when (type) {
            1 -> LinuxDesktopEvent.NotificationAction(id.value, text)
            2 -> LinuxDesktopEvent.NotificationClosed(id.value, reason.value)
            3 ->
                LinuxDesktopEvent.ProgressRequested(
                    text,
                    LinuxDesktopEvent.ProgressRequested.Action.Cancel,
                )
            4 ->
                LinuxDesktopEvent.ProgressRequested(
                    text,
                    LinuxDesktopEvent.ProgressRequested.Action.Suspend,
                )
            5 ->
                LinuxDesktopEvent.ProgressRequested(
                    text,
                    LinuxDesktopEvent.ProgressRequested.Action.Resume,
                )
            else -> null
        }
    }
}

private fun checkDesktopError(failure: CPointer<ByteVar>?, operation: String) {
    if (failure == null) return
    val detail = failure.toKString()
    kld_free_string(failure)
    error("Could not $operation: $detail")
}
