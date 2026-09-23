@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.NativeDesktopEvent
import androidx.compose.ui.platform.NativeDesktopPlatformServices
import androidx.compose.ui.platform.NativeDesktopPlatformServicesRegistry
import androidx.compose.ui.platform.NativeNotificationAction
import androidx.compose.ui.platform.NativeNotificationHint
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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import nativedesktop.kld_free_string
import nativedesktop.kld_notification_add_action
import nativedesktop.kld_notification_add_hint_bool
import nativedesktop.kld_notification_add_hint_byte
import nativedesktop.kld_notification_add_hint_double
import nativedesktop.kld_notification_add_hint_int32
import nativedesktop.kld_notification_add_hint_int64
import nativedesktop.kld_notification_add_hint_string
import nativedesktop.kld_notification_add_hint_uint32
import nativedesktop.kld_notification_add_hint_uint64
import nativedesktop.kld_notification_capabilities
import nativedesktop.kld_notification_close
import nativedesktop.kld_notification_create
import nativedesktop.kld_notification_destroy
import nativedesktop.kld_notification_send
import nativedesktop.kld_notifications_supported
import nativedesktop.kld_poll_event
import nativedesktop.kld_shutdown
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.posix.getenv
import platform.posix.usleep
import sdl3.SDL_GLAttr
import sdl3.SDL_GL_SetAttribute
import sdl3.SDL_GetClipboardText
import sdl3.SDL_GetError
import sdl3.SDL_OpenURL
import sdl3.SDL_SetClipboardText
import sdl3.SDL_WINDOW_METAL
import sdl3.SDL_WINDOW_OPENGL
import sdl3.SDL_free

internal actual val NativeDesktopSelfTestContent: @Composable ApplicationScope.() -> Unit = {
    val applicationScope = this
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(320.dp, 200.dp)),
        visible = false,
        title = "Native macOS SDL integration test",
    ) {
        SideEffect {
            println(
                "Native macOS SDL self-test passed: window, graphics host, clipboard, and URI services"
            )
            applicationScope.exitApplication()
        }
    }
}

internal actual fun nativeGetEnvironmentVariable(name: String): String? = getenv(name)?.toKString()

internal actual fun nativeSleepMicroseconds(value: UInt) {
    usleep(value)
}

internal actual fun configureNativeSdlEnvironment() = Unit

internal actual fun configureNativeGraphics(layer: SkiaLayer) {
    val requestedApi = nativeGetEnvironmentVariable("COMPOSE_MACOS_RENDER_API")?.uppercase()
    layer.renderApi =
        when (requestedApi) {
            null,
            "",
            "AUTO" ->
                if (MTLCreateSystemDefaultDevice() == null) GraphicsApi.OPENGL
                else GraphicsApi.METAL
            "OPENGL" -> GraphicsApi.OPENGL
            "METAL" -> GraphicsApi.METAL
            else ->
                error(
                    "Unsupported COMPOSE_MACOS_RENDER_API='$requestedApi'. Expected AUTO, METAL, or OPENGL."
                )
        }
    if (layer.renderApi == GraphicsApi.OPENGL) {
        configureMacosOpenGlAttributes()
    }
    println("Compose Native macOS renderer: ${layer.renderApi}")
}

internal actual fun fallbackNativeGraphics(layer: SkiaLayer): Boolean {
    val requestedApi = nativeGetEnvironmentVariable("COMPOSE_MACOS_RENDER_API")?.uppercase()
    val automatic = requestedApi.isNullOrEmpty() || requestedApi == "AUTO"
    if (!automatic || layer.renderApi != GraphicsApi.METAL) return false

    layer.renderApi = GraphicsApi.OPENGL
    configureMacosOpenGlAttributes()
    println("Compose Native macOS renderer: falling back to ${layer.renderApi}")
    return true
}

internal actual fun nativeGraphicsWindowFlags(layer: SkiaLayer): ULong =
    when (layer.renderApi) {
        GraphicsApi.OPENGL -> SDL_WINDOW_OPENGL
        GraphicsApi.METAL -> SDL_WINDOW_METAL
        else -> 0uL
    }

private fun configureMacosOpenGlAttributes() {
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_MAJOR_VERSION, 3)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_MINOR_VERSION, 2)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_CONTEXT_PROFILE_MASK, 0x0001)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_DOUBLEBUFFER, 1)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_ALPHA_SIZE, 8)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_DEPTH_SIZE, 0)
    SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_STENCIL_SIZE, 8)
}

internal actual fun attachNativeSkiaLayer(
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
            queryContentScale = queryContentScale,
            queryFullscreen = queryFullscreen,
            updateFullscreen = updateFullscreen,
            onRenderRequested = onRenderRequested,
        )
    )
}

internal actual object NativeDesktopIntegration {
    actual fun install() {
        NativeDesktopPlatformServicesRegistry.install(MacosSdlPlatformServices)
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

private object MacosSdlPlatformServices : NativeDesktopPlatformServices {
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
                "Could not allocate a macOS notification"
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
            checkDesktopError(errorPointer.value, "send macOS notification")
            check(id != 0u) { "The macOS notification service returned an invalid identifier" }
            id
        } finally {
            kld_notification_destroy(builder)
        }
    }

    override fun closeNotification(id: UInt) = memScoped {
        val errorPointer = alloc<CPointerVar<ByteVar>>()
        errorPointer.value = null
        check(kld_notification_close(id, errorPointer.ptr) != 0) {
            checkDesktopError(errorPointer.value, "close macOS notification")
            "Could not close macOS notification"
        }
        checkDesktopError(errorPointer.value, "close macOS notification")
    }

    override fun isProgressServiceSupported(): Boolean = false

    override fun startProgressJob(
        applicationName: String,
        iconName: String,
        capabilities: Int,
    ): String = error("Native launcher progress is not implemented for the macOS SDL backend")

    override fun updateProgressJob(path: String, update: NativeProgressUpdate) = Unit

    override fun terminateProgressJob(path: String, errorMessage: String) = Unit

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
