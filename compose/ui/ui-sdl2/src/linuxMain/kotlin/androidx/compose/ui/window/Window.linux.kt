@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoGraphics
import androidx.compose.ui.graphics.cairo.CairoSurface
import androidx.compose.ui.graphics.cairo.CairoText
import androidx.compose.ui.graphics.cairo.runBackendSelfTests
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.isDialogAnimationEnabled
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.LinuxDesktopEvent
import androidx.compose.ui.platform.LinuxNotificationAction
import androidx.compose.ui.platform.LinuxNotificationHint
import androidx.compose.ui.platform.LinuxPlatformServices
import androidx.compose.ui.platform.LinuxPlatformServicesRegistry
import androidx.compose.ui.platform.LinuxProgressUpdate
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformDispatcherRegistry
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.platform.PlatformTextRegistry
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.GpuInteropRegistry
import androidx.compose.ui.viewinterop.LocalGpuInteropRegistry
import androidx.compose.ui.viewinterop.LocalNativeViewInvalidationDispatcher
import cairo.kc_create
import cairo.kc_destroy
import cairo.kgpu_context_begin
import cairo.kgpu_context_create
import cairo.kgpu_context_destroy
import cairo.kgpu_context_draw_compose
import cairo.kgpu_context_make_current
import cairo.kgpu_context_present
import cairo.kgpu_context_renderer
import cnames.structs.SDL_Cursor
import cnames.structs.SDL_Renderer
import cnames.structs.SDL_Texture
import cnames.structs.SDL_Window
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import linuxdesktop.*
import platform.posix.getenv
import platform.posix.setenv
import sdl2.SDLK_ESCAPE
import sdl2.SDL_BUTTON_LEFT
import sdl2.SDL_BUTTON_MIDDLE
import sdl2.SDL_BUTTON_RIGHT
import sdl2.SDL_BUTTON_X1
import sdl2.SDL_BUTTON_X2
import sdl2.SDL_CreateRenderer
import sdl2.SDL_CreateSystemCursor
import sdl2.SDL_CreateTexture
import sdl2.SDL_CreateWindow
import sdl2.SDL_DROPBEGIN
import sdl2.SDL_DROPCOMPLETE
import sdl2.SDL_DROPFILE
import sdl2.SDL_DROPTEXT
import sdl2.SDL_DestroyRenderer
import sdl2.SDL_DestroyTexture
import sdl2.SDL_DestroyWindow
import sdl2.SDL_Event
import sdl2.SDL_FALSE
import sdl2.SDL_FINGERDOWN
import sdl2.SDL_FINGERMOTION
import sdl2.SDL_FINGERUP
import sdl2.SDL_FreeCursor
import sdl2.SDL_GL_GetDrawableSize
import sdl2.SDL_GL_SetAttribute
import sdl2.SDL_GLattr
import sdl2.SDL_GetClipboardText
import sdl2.SDL_GetDisplayDPI
import sdl2.SDL_GetError
import sdl2.SDL_GetPerformanceCounter
import sdl2.SDL_GetPerformanceFrequency
import sdl2.SDL_GetRendererOutputSize
import sdl2.SDL_GetWindowDisplayIndex
import sdl2.SDL_GetWindowFlags
import sdl2.SDL_GetWindowID
import sdl2.SDL_HideWindow
import sdl2.SDL_HitTestResult
import sdl2.SDL_Init
import sdl2.SDL_KEYDOWN
import sdl2.SDL_KEYUP
import sdl2.SDL_MOUSEBUTTONDOWN
import sdl2.SDL_MOUSEBUTTONUP
import sdl2.SDL_MOUSEMOTION
import sdl2.SDL_MOUSEWHEEL
import sdl2.SDL_MaximizeWindow
import sdl2.SDL_MinimizeWindow
import sdl2.SDL_OpenURL
import sdl2.SDL_PIXELFORMAT_ARGB8888
import sdl2.SDL_Point
import sdl2.SDL_PollEvent
import sdl2.SDL_PushEvent
import sdl2.SDL_QUIT
import sdl2.SDL_RegisterEvents
import sdl2.SDL_Quit
import sdl2.SDL_RENDERER_ACCELERATED
import sdl2.SDL_RENDERER_PRESENTVSYNC
import sdl2.SDL_RENDERER_SOFTWARE
import sdl2.SDL_RaiseWindow
import sdl2.SDL_Rect
import sdl2.SDL_RenderClear
import sdl2.SDL_RenderCopy
import sdl2.SDL_RenderPresent
import sdl2.SDL_RestoreWindow
import sdl2.SDL_SetClipboardText
import sdl2.SDL_SetCursor
import sdl2.SDL_SetTextInputRect
import sdl2.SDL_SetWindowAlwaysOnTop
import sdl2.SDL_SetWindowBordered
import sdl2.SDL_SetWindowFullscreen
import sdl2.SDL_SetWindowHitTest
import sdl2.SDL_SetWindowMaximumSize
import sdl2.SDL_SetWindowMinimumSize
import sdl2.SDL_SetWindowPosition
import sdl2.SDL_SetWindowResizable
import sdl2.SDL_SetWindowSize
import sdl2.SDL_SetWindowTitle
import sdl2.SDL_ShowWindow
import sdl2.SDL_StartTextInput
import sdl2.SDL_SystemCursor
import sdl2.SDL_TEXTEDITING
import sdl2.SDL_TEXTINPUT
import sdl2.SDL_TRUE
import sdl2.SDL_TextureAccess
import sdl2.SDL_UpdateTexture
import sdl2.SDL_WaitEventTimeout
import sdl2.SDL_WINDOWEVENT
import sdl2.SDL_WINDOWPOS_CENTERED
import sdl2.SDL_WINDOW_ALLOW_HIGHDPI
import sdl2.SDL_WINDOW_BORDERLESS
import sdl2.SDL_WINDOW_FULLSCREEN_DESKTOP
import sdl2.SDL_WINDOW_HIDDEN
import sdl2.SDL_WINDOW_MAXIMIZED
import sdl2.SDL_WINDOW_OPENGL
import sdl2.SDL_WINDOW_RESIZABLE
import sdl2.SDL_WINDOW_SHOWN
import sdl2.SDL_WindowEventID
import sdl2.SDL_free

@Stable
interface ApplicationScope {
    fun exitApplication()
}

enum class WindowPlacement {
    Floating,
    Maximized,
    Fullscreen,
}

fun WindowPosition(x: Dp, y: Dp): WindowPosition = WindowPosition.Absolute(x, y)

fun WindowPosition(alignment: Alignment): WindowPosition = WindowPosition.Aligned(alignment)

@Immutable
sealed class WindowPosition {
    abstract val x: Dp
    abstract val y: Dp
    abstract val isSpecified: Boolean

    object PlatformDefault : WindowPosition() {
        override val x = Dp.Unspecified
        override val y = Dp.Unspecified
        override val isSpecified = false

        override fun toString() = "PlatformDefault"
    }

    @Immutable
    data class Aligned(val alignment: Alignment) : WindowPosition() {
        override val x = Dp.Unspecified
        override val y = Dp.Unspecified
        override val isSpecified = false
    }

    @Immutable
    data class Absolute(override val x: Dp, override val y: Dp) : WindowPosition() {
        override val isSpecified = true
    }
}

@Stable
interface WindowState {
    var placement: WindowPlacement
    var isMinimized: Boolean
    var position: WindowPosition
    var size: DpSize
}

private class WindowStateImpl(
    placement: WindowPlacement,
    isMinimized: Boolean,
    position: WindowPosition,
    size: DpSize,
) : WindowState {
    override var placement by androidx.compose.runtime.mutableStateOf(placement)
    override var isMinimized by androidx.compose.runtime.mutableStateOf(isMinimized)
    override var position by androidx.compose.runtime.mutableStateOf(position)
    override var size by androidx.compose.runtime.mutableStateOf(size)
}

fun WindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    size: DpSize = DpSize(800.dp, 600.dp),
): WindowState = WindowStateImpl(placement, isMinimized, position, size)

fun WindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    width: Dp,
    height: Dp,
): WindowState = WindowState(placement, isMinimized, position, DpSize(width, height))

@Composable
fun rememberWindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    size: DpSize = DpSize(800.dp, 600.dp),
): WindowState = remember { WindowState(placement, isMinimized, position, size) }

@Composable
fun rememberWindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    width: Dp = 800.dp,
    height: Dp = 600.dp,
): WindowState = remember { WindowState(placement, isMinimized, position, width, height) }

@Stable
interface WindowScope {
    val window: ComposeWindow
}

@Stable interface FrameWindowScope : WindowScope

open class ComposeWindow internal constructor(internal val host: NativeWindowHost) {
    var minimumSize: DpSize
        get() = host.minimumSize
        set(value) {
            host.minimumSize = value
        }

    var maximumSize: DpSize
        get() = host.maximumSize
        set(value) {
            host.maximumSize = value
        }

    val size: DpSize
        get() = host.state.size

    val isMaximized: Boolean
        get() = host.isMaximized

    var placement: WindowPlacement
        get() = host.state.placement
        set(value) {
            host.state.placement = value
        }

    var isMinimized: Boolean
        get() = host.state.isMinimized
        set(value) {
            host.state.isMinimized = value
        }

    var position: WindowPosition
        get() = host.state.position
        set(value) {
            host.state.position = value
        }

    fun minimize() = host.minimize()

    fun toggleMaximized() = host.toggleMaximized()

    fun requestFocus() = host.requestFocus()

    fun close() = host.requestClose()

    internal fun updateDraggableArea(key: Any, bounds: Rect) = host.updateDraggableArea(key, bounds)

    internal fun removeDraggableArea(key: Any) = host.removeDraggableArea(key)
}

class ComposeDialog internal constructor(host: NativeWindowHost) : ComposeWindow(host)

@Stable
interface DialogWindowScope : WindowScope {
    override val window: ComposeDialog
}

@Stable
interface DialogState {
    var position: WindowPosition
    var size: DpSize
}

private class DialogStateImpl(position: WindowPosition, size: DpSize) : DialogState {
    override var position by androidx.compose.runtime.mutableStateOf(position)
    override var size by androidx.compose.runtime.mutableStateOf(size)
}

fun DialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    size: DpSize = DpSize(400.dp, 300.dp),
): DialogState = DialogStateImpl(position, size)

fun DialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    width: Dp = 400.dp,
    height: Dp = 300.dp,
): DialogState = DialogState(position, DpSize(width, height))

@Composable
fun rememberDialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    size: DpSize = DpSize(400.dp, 300.dp),
): DialogState = remember { DialogState(position, size) }

@Composable
fun rememberDialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    width: Dp = 400.dp,
    height: Dp = 300.dp,
): DialogState = remember { DialogState(position, width, height) }

private class DialogWindowState(private val dialogState: DialogState) : WindowState {
    override var placement: WindowPlacement
        get() = WindowPlacement.Floating
        set(_) {}

    override var isMinimized: Boolean
        get() = false
        set(_) {}

    override var position: WindowPosition
        get() = dialogState.position
        set(value) {
            dialogState.position = value
        }

    override var size: DpSize
        get() = dialogState.size
        set(value) {
            dialogState.size = value
        }
}

@Composable
@ComposableOpenTarget(-1)
fun DialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    require(!transparent) { "Transparent top-level windows are not supported by the SDL2 host" }
    val windowState = remember(state) { DialogWindowState(state) }
    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        val frameScope = this
        val dialogScope =
            remember(frameScope.window) {
                object : DialogWindowScope {
                    override val window = ComposeDialog(frameScope.window.host)
                }
            }
        content(dialogScope)
    }
}

private val LocalNativeApplication =
    staticCompositionLocalOf<NativeApplication> {
        error("Window must be called inside application")
    }

@Composable
@ComposableOpenTarget(-1)
fun Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable FrameWindowScope.() -> Unit,
) {
    require(!transparent) { "Transparent top-level windows are not supported by the SDL2 host" }
    require(icon == null) { "Per-window icons are not supported by the SDL2 Wayland host" }
    val application = LocalNativeApplication.current
    val parentComposition = rememberCompositionContext()
    val currentContent = rememberUpdatedState(content)
    val currentCloseRequest = rememberUpdatedState(onCloseRequest)
    val host = remember(application, state) { NativeWindowHost(application, state) }
    val requestedSize = state.size

    DisposableEffect(host) {
        host.open(
            parentComposition = parentComposition,
            title = title,
            visible = visible,
            undecorated = undecorated,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            closeRequest = { currentCloseRequest.value() },
            content = { currentContent.value(host.scope) },
        )
        application.add(host)
        onDispose { application.remove(host) }
    }
    SideEffect {
        host.update(
            title = title,
            visible = visible,
            undecorated = undecorated,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            requestedSize = requestedSize,
        )
    }
}

internal fun configureNativeComposeUiFlags() {
    // Cairo layers replay their draw block instead of retaining a display list. A dialog's exit
    // animation outlives its LayoutNode, so replaying that block after detach crashes. Keep dialog
    // teardown immediate until this backend has retained display-list support.
    ComposeUiFlags.isDialogAnimationEnabled = false
}

fun application(
    exitProcessOnExit: Boolean = true,
    content: @Composable ApplicationScope.() -> Unit,
) {
    PlatformDispatcherRegistry.installPostDelayedDispatcher(Dispatchers.Default)
    if (getenv("KTNATIVE_INPUT_SELF_TEST") != null) {
        runNativeInputSelfTests()
        return
    }
    PlatformGraphicsRegistry.register(CairoGraphics)
    PlatformTextRegistry.register(CairoText)
    configureNativeComposeUiFlags()
    if (getenv("KTNATIVE_BACKEND_SELF_TEST") != null) {
        runBackendSelfTests()
        return
    }
    if (getenv("WAYLAND_DISPLAY") != null) setenv("SDL_VIDEODRIVER", "wayland", 0)
    check(SDL_Init(sdl2.SDL_INIT_VIDEO) == 0) {
        "SDL initialization failed: ${SDL_GetError()?.toKString()}"
    }
    LinuxPlatformServicesRegistry.install(SdlPlatformServices)
    SDL_StartTextInput()
    try {
        NativeApplication()
            .run(
                when {
                    getenv("KTNATIVE_DESKTOP_SELF_TEST") != null -> NativeDesktopSelfTestContent
                    getenv("KTNATIVE_WINDOW_SELF_TEST") != null -> NativeWindowSelfTestContent
                    else -> content
                }
            )
    } finally {
        LinuxPlatformServicesRegistry.install(null)
        kld_shutdown()
        SDL_Quit()
    }
    if (exitProcessOnExit) exitProcess(0)
}

suspend fun awaitApplication(content: @Composable ApplicationScope.() -> Unit) {
    application(exitProcessOnExit = false, content = content)
}

fun CoroutineScope.launchApplication(content: @Composable ApplicationScope.() -> Unit): Job =
    launch {
        awaitApplication(content)
    }

private object SdlPlatformServices : LinuxPlatformServices {
    override fun getClipboardText(): String? {
        val text = SDL_GetClipboardText() ?: return null
        return try {
            text.toKString()
        } finally {
            SDL_free(text)
        }
    }

    override fun setClipboardText(text: String) {
        check(SDL_SetClipboardText(text) == 0) {
            "Could not set clipboard text: ${SDL_GetError()?.toKString()}"
        }
    }

    override fun openUri(uri: String) {
        check(SDL_OpenURL(uri) == 0) { "Could not open URI '$uri': ${SDL_GetError()?.toKString()}" }
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
            capabilities.toKString().lineSequence().filter { it.isNotEmpty() }.toSet()
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

private val NativeWindowSelfTestContent: @Composable ApplicationScope.() -> Unit = {
    val applicationScope = this
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(320.dp, 200.dp)),
        visible = false,
        title = "Native window test A",
    ) {}
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(360.dp, 240.dp)),
        visible = false,
        title = "Native window test B",
    ) {
        SideEffect { applicationScope.exitApplication() }
    }
}

private val NativeDesktopSelfTestContent: @Composable ApplicationScope.() -> Unit = {
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

fun singleWindowApplication(
    state: WindowState = WindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    exitProcessOnExit: Boolean = true,
    content: @Composable FrameWindowScope.() -> Unit,
) =
    application(exitProcessOnExit) {
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            undecorated = undecorated,
            transparent = transparent,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            content = content,
        )
    }

internal class NativeApplication : ApplicationScope {
    private companion object {
        const val IdleWaitTimeoutMillis = 50
    }

    private val windows = mutableListOf<NativeWindowHost>()
    private val running = atomic(true)
    private val frameRequested = atomic(true)
    private val applicationLayoutDirty = atomic(true)
    private val hostTaskLock = SynchronizedObject()
    private val hostTasks = ArrayDeque<() -> Unit>()
    private val wakeEventType =
        SDL_RegisterEvents(1).also { eventType ->
            check(eventType != UInt.MAX_VALUE) {
                "Could not register the Compose SDL wake event: ${SDL_GetError()?.toKString()}"
            }
        }

    val frameRecomposer =
        FrameRecomposer(
            coroutineContext = Dispatchers.Unconfined,
            invalidate = ::requestFrame,
        )

    override fun exitApplication() {
        running.value = false
        requestFrame()
    }

    fun add(window: NativeWindowHost) {
        windows += window
        requestFrame()
    }

    fun remove(window: NativeWindowHost) {
        windows.remove(window)
        window.close()
        requestFrame()
    }

    fun requestFrame() {
        if (!frameRequested.compareAndSet(expect = false, update = true)) return
        memScoped {
            val event = alloc<SDL_Event>()
            event.type = wakeEventType
            SDL_PushEvent(event.ptr)
        }
    }

    fun dispatchToHost(block: () -> Unit) {
        synchronized(hostTaskLock) { hostTasks.addLast(block) }
        requestFrame()
    }

    private fun hasHostTasks(): Boolean = synchronized(hostTaskLock) { hostTasks.isNotEmpty() }

    private fun drainHostTasks() {
        while (true) {
            val task =
                synchronized(hostTaskLock) {
                    if (hostTasks.isEmpty()) null else hostTasks.removeFirst()
                } ?: return
            task()
        }
    }

    private fun requestApplicationLayout() {
        applicationLayoutDirty.value = true
        requestFrame()
    }

    private fun hasImmediateWork(
        applicationScene: androidx.compose.ui.scene.ComposeScene,
    ): Boolean =
        frameRequested.value ||
            hasHostTasks() ||
            frameRecomposer.hasPendingWork() ||
            applicationLayoutDirty.value ||
            applicationScene.hasPendingMeasureOrLayout ||
            windows.any { it.hasPendingRender }

    private fun dispatchSdlEvent(event: SDL_Event) {
        if (event.type == wakeEventType) return
        if (event.type == SDL_QUIT.toUInt()) {
            windows.toList().forEach { it.requestClose() }
        } else {
            windows.firstOrNull { it.owns(event) }?.handle(event)
        }
    }

    fun run(content: @Composable ApplicationScope.() -> Unit) {
        val applicationScene =
            CanvasLayersComposeScene(
                frameRecomposer = frameRecomposer,
                density = Density(1f),
                size = IntSize(1, 1),
                platformContext = PlatformContext.Empty(),
                invalidateLayout = ::requestApplicationLayout,
                invalidateDraw = ::requestFrame,
            )
        applicationScene.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNativeApplication provides this
            ) {
                content(this@NativeApplication)
            }
        }

        val frequency = SDL_GetPerformanceFrequency().toDouble()
        val start = SDL_GetPerformanceCounter()
        var composed = false
        memScoped {
            val event = alloc<SDL_Event>()
            while (running.value) {
                val timeout =
                    if (hasImmediateWork(applicationScene)) 0 else IdleWaitTimeoutMillis
                if (SDL_WaitEventTimeout(event.ptr, timeout) != 0) {
                    dispatchSdlEvent(event)
                }
                while (SDL_PollEvent(event.ptr) != 0) {
                    dispatchSdlEvent(event)
                }

                drainHostTasks()
                dispatchLinuxDesktopEvents()

                val requested = frameRequested.getAndSet(false)
                val recomposerPending = frameRecomposer.hasPendingWork()
                val layoutPending =
                    applicationLayoutDirty.value || applicationScene.hasPendingMeasureOrLayout
                val windowPending = windows.any { it.hasPendingRender }
                if (requested || recomposerPending || layoutPending || windowPending) {
                    if (recomposerPending) {
                        val counter = SDL_GetPerformanceCounter()
                        val nanoTime =
                            ((counter - start).toDouble() * 1_000_000_000.0 / frequency).toLong()
                        frameRecomposer.performFrame(nanoTime)
                    }
                    if (
                        applicationLayoutDirty.getAndSet(false) ||
                            applicationScene.hasPendingMeasureOrLayout
                    ) {
                        applicationScene.measureAndLayout()
                    }
                    composed = true
                    windows.toList().filter { it.hasPendingRender }.forEach { it.render() }
                }

                if (composed && windows.isEmpty()) running.value = false
                if (hasImmediateWork(applicationScene)) requestFrame()
            }
        }

        applicationScene.close()
        windows.toList().forEach(::remove)
        frameRecomposer.close()
    }
}

private data class RenderMetrics(
    val windowWidth: Int,
    val windowHeight: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val density: Float,
) {
    val inputScaleX: Float = pixelWidth.toFloat() / windowWidth.toFloat()
    val inputScaleY: Float = pixelHeight.toFloat() / windowHeight.toFloat()
}

private class SdlWindowInfo : WindowInfo {
    override var isWindowFocused by androidx.compose.runtime.mutableStateOf(false)
    override var keyboardModifiers by
        androidx.compose.runtime.mutableStateOf(PointerKeyboardModifiers())
    override var containerSize by androidx.compose.runtime.mutableStateOf(IntSize.Zero)
    override var containerDpSize by androidx.compose.runtime.mutableStateOf(DpSize.Zero)
}

private class SdlPlatformContext : PlatformContext by PlatformContext.Empty() {
    override val windowInfo = SdlWindowInfo()
    private val windowLifecycle = LinuxWindowLifecycle()
    override val architectureComponentsOwner: PlatformArchitectureComponentsOwner
        get() = windowLifecycle.owner

    private val cursors = mutableMapOf<SDL_SystemCursor, CPointer<SDL_Cursor>>()
    private var textInputRequest: PlatformTextInputMethodRequest? = null

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        val systemCursor =
            when (pointerIcon) {
                PointerIcon.Crosshair -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_CROSSHAIR
                PointerIcon.Text -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_IBEAM
                PointerIcon.Hand -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_HAND
                else -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_ARROW
            }
        val cursor =
            cursors.getOrPut(systemCursor) {
                SDL_CreateSystemCursor(systemCursor)
                    ?: error("Could not create cursor: ${SDL_GetError()?.toKString()}")
            }
        SDL_SetCursor(cursor)
    }

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        textInputRequest = request
        try {
            awaitCancellation()
        } finally {
            if (textInputRequest === request) textInputRequest = null
        }
    }

    fun commitText(text: String): Boolean {
        val request = textInputRequest ?: return false
        request.onEditCommand(listOf(CommitTextCommand(text, 1)))
        return true
    }

    fun updateComposingText(text: String): Boolean {
        val request = textInputRequest ?: return false
        request.onEditCommand(
            listOf(
                if (text.isEmpty()) {
                    FinishComposingTextCommand()
                } else {
                    SetComposingTextCommand(text, 1)
                }
            )
        )
        return true
    }

    fun updateTextInputRect(metrics: RenderMetrics) {
        val focused = textInputRequest?.focusedRectInRoot?.invoke() ?: return
        memScoped {
            val rect = alloc<SDL_Rect>()
            rect.x = (focused.left / metrics.inputScaleX).roundToInt()
            rect.y = (focused.top / metrics.inputScaleY).roundToInt()
            rect.w = (focused.width / metrics.inputScaleX).roundToInt().coerceAtLeast(1)
            rect.h = (focused.height / metrics.inputScaleY).roundToInt().coerceAtLeast(1)
            SDL_SetTextInputRect(rect.ptr)
        }
    }

    fun updateLifecycle(isVisible: Boolean, isMinimized: Boolean) {
        windowLifecycle.update(
            isVisible = isVisible,
            isMinimized = isMinimized,
            isFocused = windowInfo.isWindowFocused,
        )
    }

    fun close() {
        windowLifecycle.destroy()
        cursors.values.forEach(::SDL_FreeCursor)
        cursors.clear()
    }
}

private class Framebuffer(renderer: CPointer<SDL_Renderer>?, width: Int, height: Int) {
    val surface = CairoSurface(width, height)
    private val context = checkNotNull(kc_create(surface.handle))
    val canvas = CairoCanvas(context)
    val texture: CPointer<SDL_Texture>? =
        renderer?.let {
            SDL_CreateTexture(
                it,
                SDL_PIXELFORMAT_ARGB8888,
                SDL_TextureAccess.SDL_TEXTUREACCESS_STREAMING.value.toInt(),
                width,
                height,
            ) ?: error("Could not create frame texture: ${SDL_GetError()?.toKString()}")
        }

    fun close() {
        texture?.let(::SDL_DestroyTexture)
        kc_destroy(context)
        surface.close()
    }
}

internal class NativeWindowHost(
    private val application: NativeApplication,
    val state: WindowState,
) {
    private var nativeWindow: CPointer<SDL_Window>? = null
    private var renderer: CPointer<SDL_Renderer>? = null
    private var gpuContext: COpaquePointer? = null
    private val gpuInteropRegistry = GpuInteropRegistry()
    private var scene: androidx.compose.ui.scene.ComposeScene? = null
    private var framebuffer: Framebuffer? = null
    private var metrics: RenderMetrics? = null
    private var windowWidth = 1
    private var windowHeight = 1
    private var windowId = 0u
    private var primaryPressed = false
    private var secondaryPressed = false
    private var tertiaryPressed = false
    private var backPressed = false
    private var forwardPressed = false
    private var pointerX = 0
    private var pointerY = 0

    private data class TouchPoint(val position: Offset, val pressure: Float, val pressed: Boolean)

    private val touchPoints = mutableMapOf<Long, TouchPoint>()
    private val droppedFiles = mutableListOf<String>()
    private var droppedText: String? = null
    private var dropAccepted = false
    private val platformContext = SdlPlatformContext()
    private var closeRequest: () -> Unit = {}
    private var currentTitle = ""
    private var currentVisible = false
    private var windowShown = false
    private var currentUndecorated = false
    private var currentResizable = true
    private var currentEnabled = true
    private var currentFocusable = true
    private var currentAlwaysOnTop = false
    private var currentPlacement = WindowPlacement.Floating
    private var currentMinimized = false
    private var currentPosition: WindowPosition = WindowPosition.PlatformDefault
    private var onPreviewKeyEvent: (KeyEvent) -> Boolean = { false }
    private var onKeyEvent: (KeyEvent) -> Boolean = { false }
    private var closed = false
    private val renderScheduled = atomic(true)
    private var hitTestReference: StableRef<NativeWindowHost>? = null
    private val draggableAreas = mutableMapOf<Any, Rect>()

    var isMaximized by androidx.compose.runtime.mutableStateOf(false)
        private set

    private val isRenderable: Boolean
        get() = currentVisible && windowShown && !currentMinimized

    val hasPendingRender: Boolean
        get() = isRenderable && (renderScheduled.value || scene?.hasInvalidations() == true)

    fun requestRender() {
        renderScheduled.value = true
        application.requestFrame()
    }

    val scope: FrameWindowScope =
        object : FrameWindowScope {
            override val window = ComposeWindow(this@NativeWindowHost)
        }

    var minimumSize: DpSize = DpSize(1.dp, 1.dp)
        set(value) {
            field = value
            applyMinimumSize()
        }

    var maximumSize: DpSize = DpSize.Unspecified
        set(value) {
            field = value
            applyMaximumSize()
        }

    fun open(
        parentComposition: androidx.compose.runtime.CompositionContext,
        title: String,
        visible: Boolean,
        undecorated: Boolean,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        closeRequest: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        this.closeRequest = closeRequest
        val initialSize = state.size
        windowWidth =
            if (initialSize.width.isSpecified) initialSize.width.value.roundToInt().coerceAtLeast(1)
            else 800
        windowHeight =
            if (initialSize.height.isSpecified)
                initialSize.height.value.roundToInt().coerceAtLeast(1)
            else 600
        val useGpu = getenv("SDL_VIDEODRIVER")?.toKString() != "dummy"
        if (useGpu) {
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MAJOR_VERSION, 2)
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MINOR_VERSION, 1)
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_PROFILE_MASK, 0)
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_DOUBLEBUFFER, 1)
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_ALPHA_SIZE, 8)
            SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_DEPTH_SIZE, 0)
        }
        val flags =
            SDL_WINDOW_ALLOW_HIGHDPI or
                (if (useGpu) SDL_WINDOW_OPENGL else 0u) or
                (if (visible) SDL_WINDOW_SHOWN else SDL_WINDOW_HIDDEN) or
                (if (undecorated) SDL_WINDOW_BORDERLESS else 0u) or
                (if (resizable) SDL_WINDOW_RESIZABLE else 0u)
        val initialX =
            (state.position as? WindowPosition.Absolute)?.x?.value?.roundToInt()
                ?: SDL_WINDOWPOS_CENTERED.toInt()
        val initialY =
            (state.position as? WindowPosition.Absolute)?.y?.value?.roundToInt()
                ?: SDL_WINDOWPOS_CENTERED.toInt()
        val window =
            SDL_CreateWindow(title, initialX, initialY, windowWidth, windowHeight, flags)
                ?: error("Could not create window: ${SDL_GetError()?.toKString()}")
        nativeWindow = window
        windowId = SDL_GetWindowID(window)
        val nativeRenderer =
            if (useGpu) {
                null
            } else {
                SDL_CreateRenderer(
                    window,
                    -1,
                    SDL_RENDERER_ACCELERATED or SDL_RENDERER_PRESENTVSYNC,
                )
                    ?: SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE)
                    ?: error("Could not create renderer: ${SDL_GetError()?.toKString()}")
            }
        renderer = nativeRenderer
        if (useGpu) {
            gpuContext =
                checkNotNull(kgpu_context_create(window)) {
                    "Could not create OpenGL context: ${SDL_GetError()?.toKString()}"
                }
            gpuInteropRegistry.context = gpuContext
        }
        currentTitle = title
        currentVisible = visible
        windowShown = SDL_GetWindowFlags(window) and SDL_WINDOW_HIDDEN == 0u
        currentUndecorated = undecorated
        currentResizable = resizable
        currentEnabled = enabled
        currentFocusable = focusable
        currentAlwaysOnTop = alwaysOnTop
        currentPlacement = state.placement
        currentMinimized = state.isMinimized
        currentPosition = state.position
        this.onPreviewKeyEvent = onPreviewKeyEvent
        this.onKeyEvent = onKeyEvent
        SDL_SetWindowAlwaysOnTop(window, if (alwaysOnTop) SDL_TRUE else SDL_FALSE)
        applyPlacement()
        if (state.isMinimized) SDL_MinimizeWindow(window)
        isMaximized = SDL_GetWindowFlags(window) and SDL_WINDOW_MAXIMIZED != 0u
        hitTestReference = StableRef.create(this)
        configureHitTest()
        applyMinimumSize()
        applyMaximumSize()

        val initialMetrics = queryMetrics(window, nativeRenderer)
        metrics = initialMetrics
        val nativeScene =
            CanvasLayersComposeScene(
                frameRecomposer = application.frameRecomposer,
                density = Density(initialMetrics.density),
                size = IntSize(initialMetrics.pixelWidth, initialMetrics.pixelHeight),
                platformContext = platformContext,
                invalidateLayout = ::requestRender,
                invalidateDraw = ::requestRender,
            )
        platformContext.windowInfo.isWindowFocused =
            focusable && SDL_GetWindowFlags(window) and sdl2.SDL_WINDOW_INPUT_FOCUS != 0u
        platformContext.updateLifecycle(
            isVisible = currentVisible && windowShown,
            isMinimized = currentMinimized,
        )
        updateWindowInfo(initialMetrics)
        scene = nativeScene
        nativeScene.setContent(parentComposition) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalGpuInteropRegistry provides gpuInteropRegistry,
                LocalNativeViewInvalidationDispatcher provides { block ->
                    application.dispatchToHost {
                        block()
                        requestRender()
                    }
                },
                content = content,
            )
        }
        framebuffer =
            Framebuffer(nativeRenderer, initialMetrics.pixelWidth, initialMetrics.pixelHeight)
        println("$title: ${initialMetrics.description()}")
        gpuContext?.let {
            println(
                "$title: OpenGL ${kgpu_context_renderer(it)?.toKString() ?: "unknown renderer"}"
            )
        }
    }

    fun update(
        title: String,
        visible: Boolean,
        undecorated: Boolean,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        requestedSize: DpSize,
    ) {
        val window = nativeWindow ?: return
        if (title != currentTitle) {
            SDL_SetWindowTitle(window, title)
            currentTitle = title
        }
        if (visible != currentVisible) {
            if (visible) SDL_ShowWindow(window) else SDL_HideWindow(window)
            currentVisible = visible
            windowShown = visible
            if (visible) requestRender()
        }
        if (undecorated != currentUndecorated) {
            SDL_SetWindowBordered(window, if (undecorated) SDL_FALSE else SDL_TRUE)
            currentUndecorated = undecorated
            configureHitTest()
            requestRender()
        }
        if (resizable != currentResizable) {
            SDL_SetWindowResizable(window, if (resizable) SDL_TRUE else SDL_FALSE)
            currentResizable = resizable
            configureHitTest()
            requestRender()
        }
        if (enabled != currentEnabled) {
            currentEnabled = enabled
            requestRender()
        }
        if (focusable != currentFocusable) {
            currentFocusable = focusable
            requestRender()
        }
        platformContext.windowInfo.isWindowFocused =
            focusable && SDL_GetWindowFlags(window) and sdl2.SDL_WINDOW_INPUT_FOCUS != 0u
        this.onPreviewKeyEvent = onPreviewKeyEvent
        this.onKeyEvent = onKeyEvent
        if (alwaysOnTop != currentAlwaysOnTop) {
            SDL_SetWindowAlwaysOnTop(window, if (alwaysOnTop) SDL_TRUE else SDL_FALSE)
            currentAlwaysOnTop = alwaysOnTop
        }
        if (state.placement != currentPlacement) {
            currentPlacement = state.placement
            applyPlacement()
            requestRender()
        }
        if (state.isMinimized != currentMinimized) {
            if (state.isMinimized) SDL_MinimizeWindow(window) else SDL_RestoreWindow(window)
            currentMinimized = state.isMinimized
            if (!currentMinimized) requestRender()
        }
        platformContext.updateLifecycle(
            isVisible = currentVisible && windowShown,
            isMinimized = currentMinimized,
        )
        if (state.position != currentPosition && state.position is WindowPosition.Absolute) {
            val position = state.position as WindowPosition.Absolute
            SDL_SetWindowPosition(
                window,
                position.x.value.roundToInt(),
                position.y.value.roundToInt(),
            )
            currentPosition = position
            requestRender()
        }
        val requestedWidth =
            if (requestedSize.width.isSpecified)
                requestedSize.width.value.roundToInt().coerceAtLeast(1)
            else windowWidth
        val requestedHeight =
            if (requestedSize.height.isSpecified)
                requestedSize.height.value.roundToInt().coerceAtLeast(1)
            else windowHeight
        if (requestedWidth != windowWidth || requestedHeight != windowHeight) {
            windowWidth = requestedWidth
            windowHeight = requestedHeight
            SDL_SetWindowSize(window, requestedWidth, requestedHeight)
            requestRender()
        }
    }

    fun owns(event: SDL_Event): Boolean =
        when (event.type) {
            SDL_WINDOWEVENT.toUInt() -> event.window.windowID == windowId
            SDL_KEYDOWN.toUInt(),
            SDL_KEYUP.toUInt() -> event.key.windowID == windowId
            SDL_TEXTINPUT.toUInt() -> event.text.windowID == windowId
            SDL_TEXTEDITING.toUInt() -> event.edit.windowID == windowId
            SDL_MOUSEMOTION.toUInt() -> event.motion.windowID == windowId
            SDL_MOUSEWHEEL.toUInt() -> event.wheel.windowID == windowId
            SDL_MOUSEBUTTONDOWN.toUInt(),
            SDL_MOUSEBUTTONUP.toUInt() -> event.button.windowID == windowId
            SDL_FINGERDOWN.toUInt(),
            SDL_FINGERMOTION.toUInt(),
            SDL_FINGERUP.toUInt() -> event.tfinger.windowID == windowId
            SDL_DROPBEGIN.toUInt(),
            SDL_DROPCOMPLETE.toUInt(),
            SDL_DROPFILE.toUInt(),
            SDL_DROPTEXT.toUInt() -> event.drop.windowID == windowId
            else -> false
        }

    fun handle(event: SDL_Event) {
        when (event.type) {
            SDL_WINDOWEVENT.toUInt() ->
                when (event.window.event) {
                    SDL_WindowEventID.SDL_WINDOWEVENT_CLOSE.value.toUByte() -> requestClose()
                    SDL_WindowEventID.SDL_WINDOWEVENT_SHOWN.value.toUByte() -> {
                        windowShown = true
                        updateLifecycle()
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_EXPOSED.value.toUByte() -> requestRender()
                    SDL_WindowEventID.SDL_WINDOWEVENT_HIDDEN.value.toUByte() -> {
                        windowShown = false
                        updateLifecycle()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_MAXIMIZED.value.toUByte() -> {
                        isMaximized = true
                        currentPlacement = WindowPlacement.Maximized
                        state.placement = WindowPlacement.Maximized
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_MINIMIZED.value.toUByte() -> {
                        currentMinimized = true
                        state.isMinimized = true
                        updateLifecycle()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_RESTORED.value.toUByte() -> {
                        isMaximized = false
                        currentMinimized = false
                        if (state.placement != WindowPlacement.Fullscreen) {
                            currentPlacement = WindowPlacement.Floating
                            state.placement = WindowPlacement.Floating
                        }
                        state.isMinimized = false
                        updateLifecycle()
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_MOVED.value.toUByte() -> {
                        currentPosition =
                            WindowPosition(event.window.data1.dp, event.window.data2.dp)
                        state.position = currentPosition
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_FOCUS_GAINED.value.toUByte() -> {
                        platformContext.windowInfo.isWindowFocused = currentFocusable
                        updateLifecycle()
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_FOCUS_LOST.value.toUByte() -> {
                        platformContext.windowInfo.isWindowFocused = false
                        updateLifecycle()
                        clearPointerButtons()
                        touchPoints.clear()
                        scene?.cancelPointerInput()
                        requestRender()
                    }
                    SDL_WindowEventID.SDL_WINDOWEVENT_ENTER.value.toUByte() ->
                        pointer(PointerEventType.Enter, pointerX, pointerY)
                    SDL_WindowEventID.SDL_WINDOWEVENT_LEAVE.value.toUByte() ->
                        pointer(PointerEventType.Exit, pointerX, pointerY)
                    SDL_WindowEventID.SDL_WINDOWEVENT_SIZE_CHANGED.value.toUByte() -> {
                        windowWidth = event.window.data1.coerceAtLeast(1)
                        windowHeight = event.window.data2.coerceAtLeast(1)
                        state.size = DpSize(windowWidth.dp, windowHeight.dp)
                        requestRender()
                    }
                }
            SDL_KEYDOWN.toUInt(),
            SDL_KEYUP.toUInt() -> {
                if (!currentEnabled || !currentFocusable) return
                key(
                    key = composeKeyForSdlScancode(event.key.keysym.scancode.toInt()),
                    type =
                        if (event.type == SDL_KEYDOWN.toUInt()) {
                            KeyEventType.KeyDown
                        } else {
                            KeyEventType.KeyUp
                        },
                    modifiers = event.key.keysym.mod.toInt(),
                )
                if (
                    event.type == SDL_KEYDOWN.toUInt() &&
                        event.key.keysym.sym == SDLK_ESCAPE.toInt()
                ) {
                    requestClose()
                }
            }
            SDL_TEXTINPUT.toUInt() ->
                if (currentEnabled && currentFocusable) textInput(event.text.text.toKString())
            SDL_TEXTEDITING.toUInt() ->
                if (currentEnabled && currentFocusable) {
                    platformContext.updateComposingText(event.edit.text.toKString())
                }
            SDL_MOUSEMOTION.toUInt() -> {
                if (!currentEnabled) return
                if (event.motion.which == UInt.MAX_VALUE) return
                pointerX = event.motion.x
                pointerY = event.motion.y
                pointer(PointerEventType.Move, pointerX, pointerY)
                if (dropAccepted) scene?.rootDragAndDropNode?.onMoved(currentDropEvent())
            }
            SDL_MOUSEWHEEL.toUInt() -> {
                if (!currentEnabled) return
                val direction = if (event.wheel.direction == 1u) 1f else -1f
                val step = 40f * (metrics?.density ?: 1f)
                pointer(
                    PointerEventType.Scroll,
                    pointerX,
                    pointerY,
                    scrollDelta =
                        Offset(event.wheel.x * step * direction, event.wheel.y * step * direction),
                )
            }
            SDL_MOUSEBUTTONDOWN.toUInt(),
            SDL_MOUSEBUTTONUP.toUInt() -> {
                if (!currentEnabled) return
                if (event.button.which == UInt.MAX_VALUE) return
                pointerX = event.button.x
                pointerY = event.button.y
                val button = pointerButton(event.button.button) ?: return
                setButtonPressed(button, event.type == SDL_MOUSEBUTTONDOWN.toUInt())
                pointer(
                    if (event.type == SDL_MOUSEBUTTONDOWN.toUInt()) {
                        PointerEventType.Press
                    } else {
                        PointerEventType.Release
                    },
                    pointerX,
                    pointerY,
                    button = button,
                )
            }
            SDL_FINGERDOWN.toUInt(),
            SDL_FINGERMOTION.toUInt(),
            SDL_FINGERUP.toUInt() -> {
                if (!currentEnabled) return
                touch(event)
            }
            SDL_DROPBEGIN.toUInt() -> beginDrop()
            SDL_DROPFILE.toUInt() -> receiveDropItem(event, isFile = true)
            SDL_DROPTEXT.toUInt() -> receiveDropItem(event, isFile = false)
            SDL_DROPCOMPLETE.toUInt() -> completeDrop()
        }
    }

    fun requestClose() = closeRequest()

    fun minimize() {
        nativeWindow?.let { SDL_MinimizeWindow(it) }
    }

    fun toggleMaximized() {
        nativeWindow?.let { if (isMaximized) SDL_RestoreWindow(it) else SDL_MaximizeWindow(it) }
    }

    fun requestFocus() {
        nativeWindow?.let { SDL_RaiseWindow(it) }
    }

    fun updateDraggableArea(key: Any, bounds: Rect) {
        draggableAreas[key] = bounds
    }

    fun removeDraggableArea(key: Any) {
        draggableAreas.remove(key)
    }

    fun hitTest(x: Int, y: Int): SDL_HitTestResult {
        val currentMetrics = metrics ?: return SDL_HitTestResult.SDL_HITTEST_NORMAL
        val point = Offset(x * currentMetrics.inputScaleX, y * currentMetrics.inputScaleY)
        val border = 6f * currentMetrics.density
        if (currentResizable) {
            val left = point.x < border
            val right = point.x >= currentMetrics.pixelWidth - border
            val top = point.y < border
            val bottom = point.y >= currentMetrics.pixelHeight - border
            if (left && top) return SDL_HitTestResult.SDL_HITTEST_RESIZE_TOPLEFT
            if (right && top) return SDL_HitTestResult.SDL_HITTEST_RESIZE_TOPRIGHT
            if (left && bottom) return SDL_HitTestResult.SDL_HITTEST_RESIZE_BOTTOMLEFT
            if (right && bottom) return SDL_HitTestResult.SDL_HITTEST_RESIZE_BOTTOMRIGHT
            if (left) return SDL_HitTestResult.SDL_HITTEST_RESIZE_LEFT
            if (right) return SDL_HitTestResult.SDL_HITTEST_RESIZE_RIGHT
            if (top) return SDL_HitTestResult.SDL_HITTEST_RESIZE_TOP
            if (bottom) return SDL_HitTestResult.SDL_HITTEST_RESIZE_BOTTOM
        }
        return if (draggableAreas.values.any { it.contains(point) }) {
            SDL_HitTestResult.SDL_HITTEST_DRAGGABLE
        } else {
            SDL_HitTestResult.SDL_HITTEST_NORMAL
        }
    }

    private fun pointer(
        type: PointerEventType,
        x: Int,
        y: Int,
        button: PointerButton? = null,
        scrollDelta: Offset = Offset.Zero,
    ) {
        val currentMetrics = metrics ?: return
        scene?.sendPointerEvent(
            eventType = type,
            position = Offset(x * currentMetrics.inputScaleX, y * currentMetrics.inputScaleY),
            scrollDelta = scrollDelta,
            buttons = pointerButtons(),
            keyboardModifiers = platformContext.windowInfo.keyboardModifiers,
            button = button,
        )
    }

    private fun pointerButtons() =
        PointerButtons(
            isPrimaryPressed = primaryPressed,
            isSecondaryPressed = secondaryPressed,
            isTertiaryPressed = tertiaryPressed,
            isBackPressed = backPressed,
            isForwardPressed = forwardPressed,
        )

    private fun pointerButton(button: UByte): PointerButton? =
        when (button) {
            SDL_BUTTON_LEFT.toUByte() -> PointerButton.Primary
            SDL_BUTTON_RIGHT.toUByte() -> PointerButton.Secondary
            SDL_BUTTON_MIDDLE.toUByte() -> PointerButton.Tertiary
            SDL_BUTTON_X1.toUByte() -> PointerButton.Back
            SDL_BUTTON_X2.toUByte() -> PointerButton.Forward
            else -> null
        }

    private fun setButtonPressed(button: PointerButton, pressed: Boolean) {
        when (button) {
            PointerButton.Primary -> primaryPressed = pressed
            PointerButton.Secondary -> secondaryPressed = pressed
            PointerButton.Tertiary -> tertiaryPressed = pressed
            PointerButton.Back -> backPressed = pressed
            PointerButton.Forward -> forwardPressed = pressed
        }
    }

    private fun clearPointerButtons() {
        primaryPressed = false
        secondaryPressed = false
        tertiaryPressed = false
        backPressed = false
        forwardPressed = false
    }

    private fun touch(event: SDL_Event) {
        val currentMetrics = metrics ?: return
        val id = event.tfinger.fingerId
        val released = event.type == SDL_FINGERUP.toUInt()
        touchPoints[id] =
            TouchPoint(
                position =
                    Offset(
                        event.tfinger.x * currentMetrics.pixelWidth,
                        event.tfinger.y * currentMetrics.pixelHeight,
                    ),
                pressure = event.tfinger.pressure,
                pressed = !released,
            )
        val type =
            when (event.type) {
                SDL_FINGERDOWN.toUInt() -> PointerEventType.Press
                SDL_FINGERUP.toUInt() -> PointerEventType.Release
                else -> PointerEventType.Move
            }
        scene?.sendPointerEvent(
            eventType = type,
            pointers =
                touchPoints.map { (pointerId, point) ->
                    ComposeScenePointer(
                        id = PointerId(pointerId),
                        position = point.position,
                        pressed = point.pressed,
                        type = PointerType.Touch,
                        pressure = point.pressure,
                    )
                },
            keyboardModifiers = platformContext.windowInfo.keyboardModifiers,
            timeMillis = event.tfinger.timestamp.toLong(),
        )
        if (released) touchPoints.remove(id)
    }

    private fun beginDrop() {
        droppedFiles.clear()
        droppedText = null
        dropAccepted = false
    }

    private fun receiveDropItem(event: SDL_Event, isFile: Boolean) {
        val nativeData = event.drop.file ?: return
        val value = nativeData.toKString()
        SDL_free(nativeData)
        if (isFile) droppedFiles += value else droppedText = value
        if (!dropAccepted) {
            val dragEvent = currentDropEvent()
            dropAccepted = scene?.rootDragAndDropNode?.acceptDragAndDropTransfer(dragEvent) == true
            if (dropAccepted) scene?.rootDragAndDropNode?.onEntered(dragEvent)
        }
    }

    private fun completeDrop() {
        if (dropAccepted) {
            val dragEvent = currentDropEvent()
            scene?.rootDragAndDropNode?.onDrop(dragEvent)
            scene?.rootDragAndDropNode?.onEnded(dragEvent)
        }
        beginDrop()
    }

    private fun currentDropEvent(): DragAndDropEvent {
        val currentMetrics = metrics
        val position =
            if (currentMetrics == null) {
                Offset.Zero
            } else {
                Offset(pointerX * currentMetrics.inputScaleX, pointerY * currentMetrics.inputScaleY)
            }
        return DragAndDropEvent(
            offset = position,
            transferData =
                DragAndDropTransferData(files = droppedFiles.toList(), text = droppedText),
        )
    }

    private fun key(key: Key, type: KeyEventType, modifiers: Int) {
        platformContext.windowInfo.keyboardModifiers = pointerKeyboardModifiers(modifiers)
        val event =
            KeyEvent(
                key = key,
                type = type,
                isCtrlPressed = modifiers and SdlCtrlMask != 0,
                isMetaPressed = modifiers and SdlMetaMask != 0,
                isAltPressed = modifiers and SdlAltMask != 0,
                isShiftPressed = modifiers and SdlShiftMask != 0,
            )
        if (onPreviewKeyEvent(event)) return
        if (scene?.sendKeyEvent(event) != true) onKeyEvent(event)
    }

    private fun textInput(text: String) {
        if (platformContext.commitText(text)) return
        text.forEachCodePoint { codePoint ->
            // SDL_TEXTINPUT is already layout/IME translated. Keep shortcut modifiers off so
            // AltGr and composed input are treated as committed text rather than key commands.
            scene?.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyDown, codePoint = codePoint))
            scene?.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyUp, codePoint = codePoint))
        }
    }

    fun render() {
        if (!isRenderable) return
        val window = nativeWindow ?: return
        val nativeRenderer = renderer
        val nativeGpuContext = gpuContext
        if (nativeRenderer == null && nativeGpuContext == null) return
        val nativeScene = scene ?: return
        renderScheduled.value = false
        nativeGpuContext?.let(::kgpu_context_make_current)
        val nextMetrics = queryMetrics(window, nativeRenderer)
        if (nextMetrics != metrics) {
            val densityChanged = nextMetrics.density != metrics?.density
            metrics = nextMetrics
            nativeScene.density = Density(nextMetrics.density)
            nativeScene.size = IntSize(nextMetrics.pixelWidth, nextMetrics.pixelHeight)
            framebuffer?.close()
            framebuffer =
                Framebuffer(nativeRenderer, nextMetrics.pixelWidth, nextMetrics.pixelHeight)
            clearPointerButtons()
            updateWindowInfo(nextMetrics)
            if (densityChanged) println("$currentTitle: ${nextMetrics.description()}")
        }
        platformContext.updateTextInputRect(nextMetrics)
        val target = framebuffer ?: return
        target.surface.clear()
        nativeGpuContext?.let {
            kgpu_context_begin(it, nextMetrics.pixelWidth, nextMetrics.pixelHeight)
        }
        if (nativeScene.hasPendingMeasureOrLayout) nativeScene.measureAndLayout()
        gpuInteropRegistry.rootCanvas = if (nativeGpuContext != null) target.canvas else null
        try {
            nativeScene.draw(target.canvas)
        } finally {
            gpuInteropRegistry.rootCanvas = null
        }
        target.surface.flush()
        if (nativeGpuContext != null) {
            gpuInteropRegistry.draw()
            kgpu_context_draw_compose(
                nativeGpuContext,
                target.surface.data.reinterpret(),
                nextMetrics.pixelWidth,
                nextMetrics.pixelHeight,
                target.surface.stride,
            )
            kgpu_context_present(nativeGpuContext)
        } else {
            checkNotNull(nativeRenderer)
            val texture = checkNotNull(target.texture)
            check(SDL_UpdateTexture(texture, null, target.surface.data, target.surface.stride) == 0)
            SDL_RenderClear(nativeRenderer)
            SDL_RenderCopy(nativeRenderer, texture, null, null)
            SDL_RenderPresent(nativeRenderer)
        }
        if (nativeScene.hasInvalidations()) requestRender()
    }

    private fun updateWindowInfo(metrics: RenderMetrics) {
        platformContext.windowInfo.containerSize = IntSize(metrics.pixelWidth, metrics.pixelHeight)
        platformContext.windowInfo.containerDpSize =
            DpSize(
                (metrics.pixelWidth / metrics.density).dp,
                (metrics.pixelHeight / metrics.density).dp,
            )
    }

    private fun queryMetrics(
        window: CPointer<SDL_Window>,
        renderer: CPointer<SDL_Renderer>?,
    ): RenderMetrics = memScoped {
        val pixelWidth = alloc<IntVar>()
        val pixelHeight = alloc<IntVar>()
        if (renderer != null) {
            check(SDL_GetRendererOutputSize(renderer, pixelWidth.ptr, pixelHeight.ptr) == 0) {
                "Could not query renderer size: ${SDL_GetError()?.toKString()}"
            }
        } else {
            SDL_GL_GetDrawableSize(window, pixelWidth.ptr, pixelHeight.ptr)
        }
        val displayDpi = alloc<FloatVar>()
        val displayIndex = SDL_GetWindowDisplayIndex(window)
        val density =
            if (
                displayIndex >= 0 &&
                    SDL_GetDisplayDPI(displayIndex, displayDpi.ptr, null, null) == 0
            ) {
                (displayDpi.value / 96f).coerceIn(0.75f, 4f)
            } else {
                1f
            }
        RenderMetrics(
            windowWidth = windowWidth.coerceAtLeast(1),
            windowHeight = windowHeight.coerceAtLeast(1),
            pixelWidth = pixelWidth.value.coerceAtLeast(1),
            pixelHeight = pixelHeight.value.coerceAtLeast(1),
            density = density,
        )
    }

    private fun applyMinimumSize() {
        val window = nativeWindow ?: return
        val width =
            if (minimumSize.width.isSpecified) minimumSize.width.value.roundToInt().coerceAtLeast(1)
            else 1
        val height =
            if (minimumSize.height.isSpecified)
                minimumSize.height.value.roundToInt().coerceAtLeast(1)
            else 1
        SDL_SetWindowMinimumSize(window, width, height)
    }

    private fun applyMaximumSize() {
        val window = nativeWindow ?: return
        val width =
            if (maximumSize.width.isSpecified) maximumSize.width.value.roundToInt().coerceAtLeast(1)
            else 0
        val height =
            if (maximumSize.height.isSpecified)
                maximumSize.height.value.roundToInt().coerceAtLeast(1)
            else 0
        SDL_SetWindowMaximumSize(window, width, height)
    }

    private fun applyPlacement() {
        val window = nativeWindow ?: return
        when (currentPlacement) {
            WindowPlacement.Fullscreen ->
                check(SDL_SetWindowFullscreen(window, SDL_WINDOW_FULLSCREEN_DESKTOP) == 0) {
                    "Could not enter fullscreen: ${SDL_GetError()?.toKString()}"
                }
            WindowPlacement.Maximized -> {
                SDL_SetWindowFullscreen(window, 0u)
                SDL_MaximizeWindow(window)
            }
            WindowPlacement.Floating -> {
                SDL_SetWindowFullscreen(window, 0u)
                SDL_RestoreWindow(window)
            }
        }
    }

    private fun configureHitTest() {
        val window = nativeWindow ?: return
        if (currentUndecorated) {
            val reference = checkNotNull(hitTestReference)
            check(SDL_SetWindowHitTest(window, NativeWindowHitTest, reference.asCPointer()) == 0) {
                "Window hit testing is unavailable: ${SDL_GetError()?.toKString()}"
            }
        } else {
            SDL_SetWindowHitTest(window, null, null)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        gpuContext?.let(::kgpu_context_make_current)
        framebuffer?.close()
        framebuffer = null
        platformContext.close()
        scene?.close()
        scene = null
        nativeWindow?.let { SDL_SetWindowHitTest(it, null, null) }
        hitTestReference?.dispose()
        hitTestReference = null
        renderer?.let { SDL_DestroyRenderer(it) }
        renderer = null
        gpuInteropRegistry.context = null
        gpuContext?.let(::kgpu_context_destroy)
        gpuContext = null
        nativeWindow?.let { SDL_DestroyWindow(it) }
        nativeWindow = null
    }

    private fun updateLifecycle() {
        platformContext.updateLifecycle(
            isVisible = currentVisible && windowShown,
            isMinimized = currentMinimized,
        )
    }
}

private val NativeWindowHitTest =
    staticCFunction { _: CPointer<SDL_Window>?, point: CPointer<SDL_Point>?, data: COpaquePointer?
        ->
        if (point == null || data == null) {
            SDL_HitTestResult.SDL_HITTEST_NORMAL
        } else {
            data.asStableRef<NativeWindowHost>().get().hitTest(point.pointed.x, point.pointed.y)
        }
    }

private fun RenderMetrics.description(): String =
    "SDL density $density " +
        "(${(pixelWidth / density).toInt()}x${(pixelHeight / density).toInt()} dp, " +
        "${pixelWidth}x${pixelHeight} px)"

private const val SdlShiftMask = 0x0003
private const val SdlCtrlMask = 0x00c0
private const val SdlAltMask = 0x0300
private const val SdlMetaMask = 0x0c00
private const val SdlNumLockMask = 0x1000
private const val SdlCapsLockMask = 0x2000
private const val SdlAltGraphMask = 0x4000
private const val SdlScrollLockMask = 0x8000

private fun pointerKeyboardModifiers(modifiers: Int) =
    PointerKeyboardModifiers(
        isCtrlPressed = modifiers and SdlCtrlMask != 0,
        isMetaPressed = modifiers and SdlMetaMask != 0,
        isAltPressed = modifiers and SdlAltMask != 0,
        isShiftPressed = modifiers and SdlShiftMask != 0,
        isAltGraphPressed = modifiers and SdlAltGraphMask != 0,
        isCapsLockOn = modifiers and SdlCapsLockMask != 0,
        isScrollLockOn = modifiers and SdlScrollLockMask != 0,
        isNumLockOn = modifiers and SdlNumLockMask != 0,
    )

/** Maps SDL's USB/HID scancode values to Compose's platform-independent key identities. */
internal fun composeKeyForSdlScancode(scancode: Int): Key =
    when (scancode) {
        4 -> Key.A
        5 -> Key.B
        6 -> Key.C
        7 -> Key.D
        8 -> Key.E
        9 -> Key.F
        10 -> Key.G
        11 -> Key.H
        12 -> Key.I
        13 -> Key.J
        14 -> Key.K
        15 -> Key.L
        16 -> Key.M
        17 -> Key.N
        18 -> Key.O
        19 -> Key.P
        20 -> Key.Q
        21 -> Key.R
        22 -> Key.S
        23 -> Key.T
        24 -> Key.U
        25 -> Key.V
        26 -> Key.W
        27 -> Key.X
        28 -> Key.Y
        29 -> Key.Z
        30 -> Key.One
        31 -> Key.Two
        32 -> Key.Three
        33 -> Key.Four
        34 -> Key.Five
        35 -> Key.Six
        36 -> Key.Seven
        37 -> Key.Eight
        38 -> Key.Nine
        39 -> Key.Zero
        40 -> Key.Enter
        41 -> Key.Escape
        42 -> Key.Backspace
        43 -> Key.Tab
        44 -> Key.Spacebar
        45 -> Key.Minus
        46 -> Key.Equals
        47 -> Key.LeftBracket
        48 -> Key.RightBracket
        49,
        100 -> Key.Backslash
        51 -> Key.Semicolon
        52 -> Key.Apostrophe
        53 -> Key.Grave
        54 -> Key.Comma
        55 -> Key.Period
        56 -> Key.Slash
        57 -> Key.CapsLock
        58 -> Key.F1
        59 -> Key.F2
        60 -> Key.F3
        61 -> Key.F4
        62 -> Key.F5
        63 -> Key.F6
        64 -> Key.F7
        65 -> Key.F8
        66 -> Key.F9
        67 -> Key.F10
        68 -> Key.F11
        69 -> Key.F12
        70 -> Key.PrintScreen
        71 -> Key.ScrollLock
        72 -> Key.Break
        73 -> Key.Insert
        74 -> Key.MoveHome
        75 -> Key.PageUp
        76 -> Key.Delete
        77 -> Key.MoveEnd
        78 -> Key.PageDown
        79 -> Key.DirectionRight
        80 -> Key.DirectionLeft
        81 -> Key.DirectionDown
        82 -> Key.DirectionUp
        83 -> Key.NumLock
        84 -> Key.NumPadDivide
        85 -> Key.NumPadMultiply
        86 -> Key.NumPadSubtract
        87 -> Key.NumPadAdd
        88 -> Key.NumPadEnter
        89 -> Key.NumPad1
        90 -> Key.NumPad2
        91 -> Key.NumPad3
        92 -> Key.NumPad4
        93 -> Key.NumPad5
        94 -> Key.NumPad6
        95 -> Key.NumPad7
        96 -> Key.NumPad8
        97 -> Key.NumPad9
        98 -> Key.NumPad0
        99 -> Key.NumPadDot
        101 -> Key.Menu
        103 -> Key.NumPadEquals
        123 -> Key.Cut
        124 -> Key.Copy
        125 -> Key.Paste
        127 -> Key.VolumeMute
        128 -> Key.VolumeUp
        129 -> Key.VolumeDown
        133 -> Key.NumPadComma
        224 -> Key.CtrlLeft
        225 -> Key.ShiftLeft
        226 -> Key.AltLeft
        227 -> Key.MetaLeft
        228 -> Key.CtrlRight
        229 -> Key.ShiftRight
        230 -> Key.AltRight
        231 -> Key.MetaRight
        else -> Key.Unknown
    }

internal inline fun String.forEachCodePoint(block: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val first = this[index++].code
        if (first in 0xd800..0xdbff && index < length) {
            val second = this[index].code
            if (second in 0xdc00..0xdfff) {
                index++
                block(0x10000 + ((first - 0xd800) shl 10) + (second - 0xdc00))
                continue
            }
        }
        block(first)
    }
}

internal fun runNativeInputSelfTests() {
    check(composeKeyForSdlScancode(6) == Key.C)
    check(composeKeyForSdlScancode(40) == Key.Enter)
    check(composeKeyForSdlScancode(80) == Key.DirectionLeft)
    check(composeKeyForSdlScancode(124) == Key.Copy)
    check(composeKeyForSdlScancode(Int.MAX_VALUE) == Key.Unknown)

    val codePoints = mutableListOf<Int>()
    "Aé😀".forEachCodePoint(codePoints::add)
    check(codePoints == listOf(0x41, 0xe9, 0x1f600))
    println("Native input self-test passed: SDL scancodes, shortcuts, and UTF-8 text")
}
