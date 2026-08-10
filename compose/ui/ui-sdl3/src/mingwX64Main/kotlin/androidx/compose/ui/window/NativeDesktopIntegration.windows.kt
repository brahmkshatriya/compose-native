@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.NativeDesktopEvent
import androidx.compose.ui.platform.NativeNotificationAction
import androidx.compose.ui.platform.NativeNotificationHint
import androidx.compose.ui.platform.NativeDesktopPlatformServices
import androidx.compose.ui.platform.NativeDesktopPlatformServicesRegistry
import androidx.compose.ui.platform.NativeProgressUpdate
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
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import nativedesktop.*
import org.jetbrains.skiko.SkiaLayer
import sdl3.SDL_DelayNS
import sdl3.SDL_GetClipboardText
import sdl3.SDL_GetError
import sdl3.SDL_GetPointerProperty
import sdl3.SDL_GetWindowProperties
import sdl3.SDL_OpenURL
import sdl3.SDL_PROP_WINDOW_WIN32_HWND_POINTER
import sdl3.SDL_SetClipboardText
import sdl3.SDL_free
import sdl3.SDL_getenv
import sdl3.SDL_WINDOW_OPENGL

fun TrayState.sendNotification(notification: Notification) {
    androidx.compose.ui.window.sendNotification(notification)
}

internal actual val NativeDesktopSelfTestContent: @Composable ApplicationScope.() -> Unit = {
    val applicationScope = this
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(320.dp, 200.dp)),
        visible = false,
        title = "Native Windows desktop integration test",
    ) {
        SideEffect {
            println("Native Windows desktop self-test passed: clipboard, URI, theme, and HWND host")
            applicationScope.exitApplication()
        }
    }
}

internal actual fun nativeGetEnvironmentVariable(name: String): String? =
    SDL_getenv(name)?.toKString()

internal actual fun nativeSleepMicroseconds(value: UInt) {
    SDL_DelayNS(value.toULong() * 1_000uL)
}

internal actual fun configureNativeSdlEnvironment() = Unit

internal actual fun configureNativeGraphics(layer: SkiaLayer) = Unit

internal actual fun nativeGraphicsWindowFlags(layer: SkiaLayer): ULong =
    if (layer.renderApi == org.jetbrains.skiko.GraphicsApi.OPENGL) SDL_WINDOW_OPENGL else 0uL

internal actual fun attachNativeSkiaLayer(
    layer: SkiaLayer,
    window: CPointer<SDL_Window>,
    transparency: Boolean,
    queryContentScale: () -> Float,
    queryFullscreen: () -> Boolean,
    updateFullscreen: (Boolean) -> Unit,
    onRenderRequested: () -> Unit,
) {
    val properties = SDL_GetWindowProperties(window)
    check(properties != 0u) { "Could not query SDL window properties" }
    val hwnd =
        checkNotNull(SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_WIN32_HWND_POINTER, null)) {
            "SDL did not expose a Win32 HWND: ${SDL_GetError()?.toKString()}"
        }
    layer.transparency = transparency
    layer.attachTo(hwnd.rawValue.toLong())
}

internal actual object NativeDesktopIntegration {
    actual fun install() {
        NativeDesktopPlatformServicesRegistry.install(WindowsSdlPlatformServices)
    }

    actual fun close() {
        NativeDesktopPlatformServicesRegistry.install(null)
        kld_shutdown()
    }

    actual fun pollEvents() {
        dispatchNativeDesktopEvents()
    }

    actual fun pollAccessibility(): Boolean = false

    actual fun updateSystemTheme(dark: Boolean?) = Unit
}

private object WindowsSdlPlatformServices : NativeDesktopPlatformServices {
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
        actions: List<NativeNotificationAction>,
        hints: Map<String, NativeNotificationHint>,
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
                        is NativeNotificationHint.ByteValue ->
                            kld_notification_add_hint_byte(builder, name, hint.value)
                        is NativeNotificationHint.IntValue ->
                            kld_notification_add_hint_int32(builder, name, hint.value)
                        is NativeNotificationHint.UIntValue ->
                            kld_notification_add_hint_uint32(builder, name, hint.value)
                        is NativeNotificationHint.LongValue ->
                            kld_notification_add_hint_int64(builder, name, hint.value)
                        is NativeNotificationHint.ULongValue ->
                            kld_notification_add_hint_uint64(builder, name, hint.value)
                        is NativeNotificationHint.DoubleValue ->
                            kld_notification_add_hint_double(builder, name, hint.value)
                        is NativeNotificationHint.BooleanValue ->
                            kld_notification_add_hint_bool(builder, name, if (hint.value) 1 else 0)
                        is NativeNotificationHint.StringValue ->
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

    override fun isProgressServiceSupported(): Boolean = false

    override fun startProgressJob(
        applicationName: String,
        iconName: String,
        capabilities: Int,
    ): String = error("Windows does not expose a native launcher progress service")

    override fun updateProgressJob(path: String, update: NativeProgressUpdate) {
        error("Windows does not expose a native launcher progress service")
    }

    override fun terminateProgressJob(path: String, errorMessage: String) {
        error("Windows does not expose a native launcher progress service")
    }

    override fun pollDesktopEvent(): NativeDesktopEvent? = memScoped {
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
            1 -> NativeDesktopEvent.NotificationAction(id.value, text)
            2 -> NativeDesktopEvent.NotificationClosed(id.value, reason.value)
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
