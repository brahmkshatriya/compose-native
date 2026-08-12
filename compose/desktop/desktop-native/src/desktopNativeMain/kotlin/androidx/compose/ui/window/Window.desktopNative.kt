@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    org.jetbrains.skiko.InternalSkikoApi::class,
)

package androidx.compose.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.ProvideSystemTheme
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SkiaGraphicsContext
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.platform.PlatformGraphicsContext
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
import androidx.compose.ui.platform.LocalPlatformAccentColor
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformDispatcherRegistry
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformRootForTest
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.clearSkikoComposeImplementation
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.GpuInteropRegistry
import androidx.compose.ui.viewinterop.LocalGpuInteropRegistry
import androidx.compose.ui.viewinterop.LocalNativeViewInvalidationDispatcher
import cnames.structs.SDL_Cursor
import cnames.structs.SDL_Window
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.system.exitProcess
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import nativedesktop.*
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import sdl3.SDLK_ESCAPE
import sdl3.SDL_BUTTON_LEFT
import sdl3.SDL_BUTTON_MIDDLE
import sdl3.SDL_BUTTON_RIGHT
import sdl3.SDL_BUTTON_X1
import sdl3.SDL_BUTTON_X2
import sdl3.SDL_CreateSurfaceFrom
import sdl3.SDL_CreateSystemCursor
import sdl3.SDL_CreateWindow
import sdl3.SDL_DestroyCursor
import sdl3.SDL_DestroySurface
import sdl3.SDL_DestroyWindow
import sdl3.SDL_EVENT_DROP_BEGIN
import sdl3.SDL_EVENT_DROP_COMPLETE
import sdl3.SDL_EVENT_DROP_FILE
import sdl3.SDL_EVENT_DROP_TEXT
import sdl3.SDL_EVENT_FINGER_DOWN
import sdl3.SDL_EVENT_FINGER_MOTION
import sdl3.SDL_EVENT_FINGER_UP
import sdl3.SDL_EVENT_KEY_DOWN
import sdl3.SDL_EVENT_KEY_UP
import sdl3.SDL_EVENT_MOUSE_BUTTON_DOWN
import sdl3.SDL_EVENT_MOUSE_BUTTON_UP
import sdl3.SDL_EVENT_MOUSE_MOTION
import sdl3.SDL_EVENT_MOUSE_WHEEL
import sdl3.SDL_EVENT_QUIT
import sdl3.SDL_EVENT_TEXT_EDITING
import sdl3.SDL_EVENT_TEXT_INPUT
import sdl3.SDL_EVENT_WINDOW_CLOSE_REQUESTED
import sdl3.SDL_EVENT_WINDOW_EXPOSED
import sdl3.SDL_EVENT_WINDOW_FOCUS_GAINED
import sdl3.SDL_EVENT_WINDOW_FOCUS_LOST
import sdl3.SDL_EVENT_WINDOW_HIDDEN
import sdl3.SDL_EVENT_WINDOW_MAXIMIZED
import sdl3.SDL_EVENT_WINDOW_MINIMIZED
import sdl3.SDL_EVENT_WINDOW_MOUSE_ENTER
import sdl3.SDL_EVENT_WINDOW_MOUSE_LEAVE
import sdl3.SDL_EVENT_WINDOW_MOVED
import sdl3.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED
import sdl3.SDL_EVENT_WINDOW_RESIZED
import sdl3.SDL_EVENT_WINDOW_RESTORED
import sdl3.SDL_EVENT_WINDOW_SHOWN
import sdl3.SDL_Event
import sdl3.SDL_GetError
import sdl3.SDL_GetModState
import sdl3.SDL_GetMouseState
import sdl3.SDL_GetPerformanceCounter
import sdl3.SDL_GetPerformanceFrequency
import sdl3.SDL_GetWindowDisplayScale
import sdl3.SDL_GetWindowFlags
import sdl3.SDL_GetWindowID
import sdl3.SDL_GetWindowPosition
import sdl3.SDL_GetWindowSizeInPixels
import sdl3.SDL_HideWindow
import sdl3.SDL_HitTestResult
import sdl3.SDL_Init
import sdl3.SDL_MaximizeWindow
import sdl3.SDL_MinimizeWindow
import sdl3.SDL_PIXELFORMAT_ARGB8888
import sdl3.SDL_Point
import sdl3.SDL_PollEvent
import sdl3.SDL_PushEvent
import sdl3.SDL_Quit
import sdl3.SDL_RaiseWindow
import sdl3.SDL_Rect
import sdl3.SDL_RegisterEvents
import sdl3.SDL_RestoreWindow
import sdl3.SDL_SetCursor
import sdl3.SDL_SetTextInputArea
import sdl3.SDL_SetWindowAlwaysOnTop
import sdl3.SDL_SetWindowBordered
import sdl3.SDL_SetWindowFullscreen
import sdl3.SDL_SetWindowHitTest
import sdl3.SDL_SetWindowIcon
import sdl3.SDL_SetWindowMaximumSize
import sdl3.SDL_SetWindowMinimumSize
import sdl3.SDL_SetWindowModal
import sdl3.SDL_SetWindowParent
import sdl3.SDL_SetWindowPosition
import sdl3.SDL_SetWindowResizable
import sdl3.SDL_SetWindowSize
import sdl3.SDL_SetWindowTitle
import sdl3.SDL_ShowWindow
import sdl3.SDL_StartTextInput
import sdl3.SDL_SystemCursor
import sdl3.SDL_WINDOWPOS_CENTERED
import sdl3.SDL_WINDOW_BORDERLESS
import sdl3.SDL_WINDOW_HIDDEN
import sdl3.SDL_WINDOW_HIGH_PIXEL_DENSITY
import sdl3.SDL_WINDOW_MAXIMIZED
import sdl3.SDL_WINDOW_RESIZABLE
import sdl3.SDL_WINDOW_TRANSPARENT
import sdl3.SDL_WaitEventTimeout

@Stable
interface ApplicationScope {
    fun exitApplication()
}

enum class WindowPlacement {
    Floating,
    Maximized,
    Fullscreen,
}

internal fun WindowPlacement.persistsFloatingGeometry(): Boolean = this == WindowPlacement.Floating

/** Defines which application windows are blocked while a dialog is visible. */
class DialogModalityType private constructor(val name: String) {
    override fun toString(): String = name

    companion object {
        val Modeless = DialogModalityType("Modeless")
        val DocumentModal = DialogModalityType("Document")
        val ApplicationModal = DialogModalityType("Application")
    }
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

    /** Installs the root listener used by Compose UI test hosts. */
    @InternalComposeUiApi
    var rootForTestListener: PlatformContext.RootForTestListener?
        get() = host.applicationRootForTestListener
        set(value) {
            host.applicationRootForTestListener = value
        }

    /** Captures the window that owns [root], cropped to [boundsInWindow]. */
    @InternalComposeUiApi
    fun captureToImage(root: PlatformRootForTest, boundsInWindow: Rect? = null): ImageBitmap =
        host.captureRootForTest(root, boundsInWindow)

    /** Runs [action] synchronously on the SDL application thread. */
    @InternalComposeUiApi fun <T> runOnUiThread(action: () -> T): T = host.runOnUiThread(action)

    /** Returns whether the caller is the SDL application thread. */
    @InternalComposeUiApi
    val isUiThread: Boolean
        get() = host.isUiThread

    /** Passive test-host query for pending composition, layout, or draw work. */
    @InternalComposeUiApi
    val hasPendingTestWork: Boolean
        get() = host.hasPendingTestWork

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

private val LocalNativeWindowHost = staticCompositionLocalOf<NativeWindowHost?> { null }

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
    modalityType: DialogModalityType = DialogModalityType.DocumentModal,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    val windowState = remember(state) { DialogWindowState(state) }
    val owner = LocalNativeWindowHost.current
    WindowImpl(
        onCloseRequest = onCloseRequest,
        state = windowState,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        transparent = transparent,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        owner = owner,
        modalityType = modalityType,
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
) =
    WindowImpl(
        onCloseRequest = onCloseRequest,
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
        owner = null,
        modalityType = DialogModalityType.Modeless,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )

@Composable
@ComposableOpenTarget(-1)
private fun WindowImpl(
    onCloseRequest: () -> Unit,
    state: WindowState,
    visible: Boolean,
    title: String,
    icon: Painter?,
    undecorated: Boolean,
    transparent: Boolean,
    resizable: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    owner: NativeWindowHost?,
    modalityType: DialogModalityType,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    require(!transparent || undecorated) { "Transparent top-level windows must be undecorated" }
    val application = LocalNativeApplication.current
    val parentComposition = rememberCompositionContext()
    val currentContent = rememberUpdatedState(content)
    val currentCloseRequest = rememberUpdatedState(onCloseRequest)
    val host =
        remember(application, state, owner, modalityType, transparent) {
            NativeWindowHost(application, state, owner, modalityType)
        }
    val requestedSize = state.size

    DisposableEffect(host) {
        host.open(
            parentComposition = parentComposition,
            title = title,
            icon = icon,
            visible = visible,
            transparent = transparent,
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
            icon = icon,
            visible = visible,
            transparent = transparent,
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

internal class RasterizedWindowIcon(private val delegate: ImageBitmap) :
    ImageBitmap by delegate, AutoCloseable {
    override fun close() {
        delegate.asSkiaBitmap().close()
    }
}

internal fun rasterizeWindowIcon(painter: Painter, size: Int = 64): RasterizedWindowIcon {
    require(size > 0) { "Window icon size must be positive" }
    val image = ImageBitmap(size, size)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(image),
        size = Size(size.toFloat(), size.toFloat()),
    ) {
        with(painter) { draw(Size(size.toFloat(), size.toFloat())) }
    }
    return RasterizedWindowIcon(image)
}

private fun applyWindowIcon(window: CPointer<SDL_Window>, painter: Painter?) {
    if (painter == null) {
        SDL_SetWindowIcon(window, null)
        return
    }
    rasterizeWindowIcon(painter).use { image ->
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        pixels.usePinned { pinned ->
            val surface =
                SDL_CreateSurfaceFrom(
                    image.width,
                    image.height,
                    SDL_PIXELFORMAT_ARGB8888,
                    pinned.addressOf(0),
                    image.width * 4,
                ) ?: error("Could not create SDL window icon: ${SDL_GetError()?.toKString()}")
            try {
                SDL_SetWindowIcon(window, surface)
            } finally {
                SDL_DestroySurface(surface)
            }
        }
    }
}

internal fun modalityBlocksInput(
    modalityType: DialogModalityType,
    modalActive: Boolean,
    sameDocument: Boolean,
    targetIsModalOrDescendant: Boolean,
): Boolean {
    if (!modalActive || targetIsModalOrDescendant) return false
    return when (modalityType) {
        DialogModalityType.ApplicationModal -> true
        DialogModalityType.DocumentModal -> sameDocument
        else -> false
    }
}

private fun SDL_Event.isUserInputEvent(): Boolean =
    when (type) {
        SDL_EVENT_KEY_DOWN.toUInt(),
        SDL_EVENT_KEY_UP.toUInt(),
        SDL_EVENT_TEXT_INPUT.toUInt(),
        SDL_EVENT_TEXT_EDITING.toUInt(),
        SDL_EVENT_MOUSE_MOTION.toUInt(),
        SDL_EVENT_MOUSE_WHEEL.toUInt(),
        SDL_EVENT_MOUSE_BUTTON_DOWN.toUInt(),
        SDL_EVENT_MOUSE_BUTTON_UP.toUInt(),
        SDL_EVENT_FINGER_DOWN.toUInt(),
        SDL_EVENT_FINGER_MOTION.toUInt(),
        SDL_EVENT_FINGER_UP.toUInt(),
        SDL_EVENT_DROP_BEGIN.toUInt(),
        SDL_EVENT_DROP_COMPLETE.toUInt(),
        SDL_EVENT_DROP_FILE.toUInt(),
        SDL_EVENT_DROP_TEXT.toUInt() -> true
        else -> false
    }

internal fun configureNativeComposeUiFlags() {
    ComposeUiFlags.isDialogAnimationEnabled = true
}

fun application(
    exitProcessOnExit: Boolean = true,
    content: @Composable ApplicationScope.() -> Unit,
) {
    PlatformDispatcherRegistry.installPostDelayedDispatcher(Dispatchers.Default)
    if (nativeGetEnvironmentVariable("KTNATIVE_INPUT_SELF_TEST") != null) {
        runNativeInputSelfTests()
        return
    }
    registerSkikoComposeImplementation()
    configureNativeComposeUiFlags()
    configureNativeSdlEnvironment()
    check(SDL_Init(sdl3.SDL_INIT_VIDEO)) {
        "SDL initialization failed: ${SDL_GetError()?.toKString()}"
    }
    NativeDesktopIntegration.install()
    val isWindowSelfTest = nativeGetEnvironmentVariable("KTNATIVE_WINDOW_SELF_TEST") != null
    if (isWindowSelfTest) NativeWindowSelfTestRootListener.reset()
    val nativeApplication = NativeApplication()
    try {
        nativeApplication.run(
            when {
                nativeGetEnvironmentVariable("KTNATIVE_DESKTOP_SELF_TEST") != null ->
                    NativeDesktopSelfTestContent
                isWindowSelfTest -> NativeWindowSelfTestContent
                else -> content
            }
        )
        if (isWindowSelfTest) NativeWindowSelfTestRootListener.checkDisposed()
    } finally {
        nativeApplication.close()
        NativeDesktopIntegration.close()
        SDL_Quit()
        clearSkikoComposeImplementation()
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

private object NativeWindowSelfTestRootListener : PlatformContext.RootForTestListener {
    private val activeRoots = mutableSetOf<PlatformRootForTest>()
    private var createdCount = 0
    private var disposedCount = 0

    fun reset() {
        activeRoots.clear()
        createdCount = 0
        disposedCount = 0
    }

    override fun onRootForTestCreated(root: PlatformRootForTest) {
        check(activeRoots.add(root)) { "Compose test root was registered more than once" }
        createdCount += 1
    }

    override fun onRootForTestDisposed(root: PlatformRootForTest) {
        check(activeRoots.remove(root)) { "Unknown Compose test root was disposed" }
        disposedCount += 1
    }

    fun checkRegistered() {
        check(createdCount > 0 && activeRoots.isNotEmpty()) {
            "The SDL window did not expose a Compose root to the test listener"
        }
    }

    fun checkDisposed() {
        check(createdCount > 0) { "No Compose test root was registered" }
        check(activeRoots.isEmpty() && disposedCount == createdCount) {
            "Compose test roots leaked: created=$createdCount disposed=$disposedCount " +
                "active=${activeRoots.size}"
        }
        println("Native window test root lifecycle passed: $createdCount root(s)")
    }
}

private val NativeWindowSelfTestContent: @Composable ApplicationScope.() -> Unit = {
    val applicationScope = this
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(320.dp, 200.dp)),
        visible = false,
        title = "Native window test A",
        undecorated = true,
        transparent = true,
    ) {}
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(360.dp, 240.dp)),
        visible = false,
        title = "Native window test B",
    ) {
        val composeWindow = window
        SideEffect {
            composeWindow.rootForTestListener = NativeWindowSelfTestRootListener
            NativeWindowSelfTestRootListener.checkRegistered()
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

internal fun portalColorSchemePrefersDark(value: Int): Boolean? =
    when (value) {
        1 -> true
        2 -> false
        else -> null
    }

internal fun portalAccentColor(value: Int): Color? {
    if (value and 0x01000000 == 0) return null
    return Color(0xFF000000.toInt() or (value and 0x00FFFFFF))
}

private class NativeSystemThemeObserver(colorSchemeEventType: UInt, accentColorEventType: UInt) :
    AutoCloseable {
    private var handle =
        kld_system_theme_observer_create(colorSchemeEventType, accentColorEventType)

    val current: Boolean?
        get() = portalColorSchemePrefersDark(kld_system_theme_observer_current(handle))

    val accent: Color?
        get() = portalAccentColor(kld_system_theme_observer_accent(handle).toInt())

    override fun close() {
        val observer = handle ?: return
        handle = null
        kld_system_theme_observer_destroy(observer)
    }
}

internal class NativeApplication : ApplicationScope {
    private companion object {
        const val IdleWaitTimeoutMillis = 50
        const val MaximumConfiguredFramesPerSecond = 240
    }

    @OptIn(ObsoleteWorkersApi::class) private val hostWorkerId = Worker.current.id
    private val windows = mutableListOf<NativeWindowHost>()
    private var testRootListener: PlatformContext.RootForTestListener? = null
    private val running = atomic(true)
    private val frameRequested = atomic(true)
    private val applicationLayoutDirty = atomic(true)
    private val hostTaskLock = SynchronizedObject()
    private val hostTasks = ArrayDeque<() -> Unit>()
    private val eventWatchReference = StableRef.create(this)
    private var eventWatchHandle: COpaquePointer? = null
    private val wakeEventType =
        SDL_RegisterEvents(1).also { eventType ->
            check(eventType != 0u) {
                "Could not register the Compose SDL wake event: ${SDL_GetError()?.toKString()}"
            }
        }
    private val systemThemeEventType =
        SDL_RegisterEvents(1).also { eventType ->
            check(eventType != 0u) {
                "Could not register the Compose system-theme event: ${SDL_GetError()?.toKString()}"
            }
        }
    private val systemAccentEventType =
        SDL_RegisterEvents(1).also { eventType ->
            check(eventType != 0u) {
                "Could not register the Compose system-accent event: ${SDL_GetError()?.toKString()}"
            }
        }
    private val systemThemeObserver =
        NativeSystemThemeObserver(systemThemeEventType, systemAccentEventType)
    internal val systemDarkThemeState = mutableStateOf(systemThemeObserver.current)
    internal val systemAccentColorState = mutableStateOf(systemThemeObserver.accent)

    val frameRecomposer =
        FrameRecomposer(coroutineContext = Dispatchers.Unconfined, invalidate = ::requestFrame)

    init {
        NativeDesktopIntegration.updateSystemTheme(systemDarkThemeState.value)
        eventWatchHandle =
            checkNotNull(
                kgl_event_watch_add(NativeApplicationEventWatch, eventWatchReference.asCPointer())
            ) {
                "Could not register the SDL live-resize event watch"
            }
    }

    override fun exitApplication() {
        running.value = false
        requestFrame()
    }

    fun close() {
        eventWatchHandle?.let(::kgl_event_watch_remove)
        eventWatchHandle = null
        eventWatchReference.dispose()
        systemThemeObserver.close()
        NativeDesktopIntegration.updateSystemTheme(null)
    }

    fun add(window: NativeWindowHost) {
        window.rootForTestListener = testRootListener
        windows += window
        requestFrame()
    }

    fun remove(window: NativeWindowHost) {
        windows.remove(window)
        window.close()
        requestFrame()
    }

    var rootForTestListener: PlatformContext.RootForTestListener?
        get() = testRootListener
        set(value) {
            if (testRootListener === value) return
            testRootListener = value
            windows.forEach { it.rootForTestListener = value }
        }

    fun captureRootForTest(root: PlatformRootForTest, boundsInWindow: Rect?): ImageBitmap {
        val window =
            windows.firstOrNull { it.ownsRootForTest(root) }
                ?: error("The requested Compose test root is not attached to an SDL window")
        return window.captureCurrentFrame(boundsInWindow)
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

    @OptIn(ObsoleteWorkersApi::class)
    fun isHostThread(): Boolean = Worker.current.id == hostWorkerId

    fun <T> dispatchToHostBlocking(block: () -> T): T {
        if (isHostThread()) return block()
        val result = BlockingHostTaskResult<T>()
        dispatchToHost {
            try {
                result.value = block()
            } catch (failure: Throwable) {
                result.failure = failure
            } finally {
                result.completed.value = true
            }
        }
        while (!result.completed.value) nativeSleepMicroseconds(1_000u)
        result.failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.value as T
    }

    fun hasPendingTestWork(host: NativeWindowHost): Boolean = host.hasPendingHostWork

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
        applicationScene: androidx.compose.ui.scene.ComposeScene
    ): Boolean =
        frameRequested.value ||
            hasHostTasks() ||
            frameRecomposer.hasPendingWork() ||
            applicationLayoutDirty.value ||
            applicationScene.hasPendingMeasureOrLayout ||
            windows.any { it.hasPendingRender }

    internal fun handleWatchedEvent(event: CPointer<SDL_Event>) {
        if (!isHostThread()) return
        val value = event.pointed
        if (value.type != SDL_EVENT_WINDOW_EXPOSED.toUInt()) return
        windows.firstOrNull { it.owns(value) }?.renderExposedFrame(0, 0)
    }

    private fun dispatchSdlEvent(event: SDL_Event) {
        if (event.type == wakeEventType) return
        if (event.type == systemThemeEventType) {
            val next = portalColorSchemePrefersDark(event.user.code)
            if (systemDarkThemeState.value != next) systemDarkThemeState.value = next
            NativeDesktopIntegration.updateSystemTheme(next)
            requestFrame()
            return
        }
        if (event.type == systemAccentEventType) {
            val next = portalAccentColor(event.user.code)
            if (systemAccentColorState.value != next) systemAccentColorState.value = next
            requestFrame()
            return
        }
        if (event.type == SDL_EVENT_QUIT.toUInt()) {
            windows.toList().forEach { it.requestClose() }
            return
        }
        val target = windows.firstOrNull { it.owns(event) } ?: return
        if (event.isUserInputEvent()) {
            val blocker = windows.asReversed().firstOrNull { it.blocksInputTo(target) }
            if (blocker != null) {
                blocker.requestFocus()
                return
            }
        }
        target.handle(event)
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
            ProvideSystemTheme(systemDarkThemeState.value) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPlatformAccentColor provides systemAccentColorState.value,
                    LocalNativeApplication provides this,
                ) {
                    content(this@NativeApplication)
                }
            }
        }

        val performanceFrequency = SDL_GetPerformanceFrequency().coerceAtLeast(1uL)
        val frequency = performanceFrequency.toDouble()
        val start = SDL_GetPerformanceCounter()
        val configuredFramesPerSecond =
            nativeGetEnvironmentVariable("KTNATIVE_MAX_FPS")
                ?.toIntOrNull()
                ?.coerceIn(1, MaximumConfiguredFramesPerSecond)
        val framePacer =
            configuredFramesPerSecond?.let { NativeFramePacer(performanceFrequency, it) }
        var composed = false
        memScoped {
            val event = alloc<SDL_Event>()
            while (running.value) {
                val immediateWork = hasImmediateWork(applicationScene)
                val frameDelay =
                    if (immediateWork) {
                        framePacer?.delayMillis(SDL_GetPerformanceCounter()) ?: 0
                    } else {
                        0
                    }
                val timeout =
                    when {
                        !immediateWork -> IdleWaitTimeoutMillis
                        frameDelay > 0 -> frameDelay
                        else -> 0
                    }
                if (SDL_WaitEventTimeout(event.ptr, timeout)) {
                    dispatchSdlEvent(event)
                }
                while (SDL_PollEvent(event.ptr)) {
                    dispatchSdlEvent(event)
                }

                drainHostTasks()
                NativeDesktopIntegration.pollEvents()
                NativeTrayRegistry.poll()
                if (NativeDesktopIntegration.pollAccessibility()) {
                    windows.toList().forEach { it.onAccessibilityBusConnected() }
                }

                if (
                    hasImmediateWork(applicationScene) &&
                        (framePacer?.delayMillis(SDL_GetPerformanceCounter()) ?: 0) > 0
                ) {
                    continue
                }

                val requested = frameRequested.getAndSet(false)
                val recomposerPending = frameRecomposer.hasPendingWork()
                val layoutPending =
                    applicationLayoutDirty.value || applicationScene.hasPendingMeasureOrLayout
                val windowPending = windows.any { it.hasPendingRender }
                if (requested || recomposerPending || layoutPending || windowPending) {
                    val counter = SDL_GetPerformanceCounter()
                    framePacer?.onFrameStarted(counter)
                    if (recomposerPending) {
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

                if (composed && windows.isEmpty() && !NativeTrayRegistry.hasRegistrations) {
                    running.value = false
                }
                if (hasImmediateWork(applicationScene)) requestFrame()
            }
        }

        applicationScene.close()
        windows.toList().forEach(::remove)
        NativeTrayRegistry.closeAll()
        frameRecomposer.close()
    }
}

private val NativeApplicationEventWatch =
    staticCFunction { data: COpaquePointer?, event: COpaquePointer? ->
        if (data != null && event != null) {
            data.asStableRef<NativeApplication>().get().handleWatchedEvent(event.reinterpret())
        }
        1
    }

private class BlockingHostTaskResult<T> {
    val completed = atomic(false)
    var value: T? = null
    var failure: Throwable? = null
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

private class SdlRootForTestListener : PlatformContext.RootForTestListener {
    private val roots = mutableSetOf<PlatformRootForTest>()

    var externalListener: PlatformContext.RootForTestListener? = null
        set(value) {
            if (field === value) return
            field = value
            roots.forEach { value?.onRootForTestCreated(it) }
        }

    override fun onRootForTestCreated(root: PlatformRootForTest) {
        roots += root
        externalListener?.onRootForTestCreated(root)
    }

    override fun onRootForTestDisposed(root: PlatformRootForTest) {
        roots -= root
        externalListener?.onRootForTestDisposed(root)
    }

    fun contains(root: PlatformRootForTest): Boolean = root in roots
}

private class SdlPlatformContext(
    private val accessibility: NativeAccessibility,
    private val damageTracker: FrameDamageTracker,
    private val graphicsContextFactory: () -> PlatformGraphicsContext,
) : PlatformContext by PlatformContext.Empty() {
    var window: CPointer<SDL_Window>? = null
    private val testRootListener = SdlRootForTestListener()
    internal val nativeDragAndDropManager = SdlDragAndDropManager()

    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener
        get() = accessibility

    override val rootForTestListener: PlatformContext.RootForTestListener
        get() = testRootListener

    override val windowInfo = SdlWindowInfo()
    override val dragAndDropManager: PlatformDragAndDropManager
        get() = nativeDragAndDropManager

    override fun onDrawDamage(boundsInRoot: Rect) {
        damageTracker.add(boundsInRoot)
    }

    override fun onFullDrawDamage() {
        damageTracker.requireFullFrame()
    }

    override fun createGraphicsContext(): PlatformGraphicsContext = graphicsContextFactory()

    fun ownsRootForTest(root: PlatformRootForTest): Boolean = testRootListener.contains(root)

    var externalRootForTestListener: PlatformContext.RootForTestListener?
        get() = testRootListener.externalListener
        set(value) {
            testRootListener.externalListener = value
        }

    private val windowLifecycle = NativeWindowLifecycle()
    override val architectureComponentsOwner: PlatformArchitectureComponentsOwner
        get() = windowLifecycle.owner

    private val cursors = mutableMapOf<SDL_SystemCursor, CPointer<SDL_Cursor>>()
    private var textInputRequest: PlatformTextInputMethodRequest? = null

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        val systemCursor =
            when (pointerIcon) {
                PointerIcon.Crosshair -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_CROSSHAIR
                PointerIcon.Text -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_TEXT
                PointerIcon.Hand -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_POINTER
                else -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_DEFAULT
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
            window?.let { SDL_SetTextInputArea(it, rect.ptr, 0) }
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
        cursors.values.forEach(::SDL_DestroyCursor)
        cursors.clear()
    }
}

internal class FrameDamageTracker {
    private companion object {
        // Covers antialiasing fringes and fractional transforms at the edge of reported bounds.
        const val AntialiasPadding = 2f
    }

    private var pending: Rect? = null
    private var fullFrameRequired = false

    fun add(bounds: Rect) {
        if (fullFrameRequired || !bounds.isFinite || bounds.isEmpty) return
        // Draw nodes report their own visual outsets. The host adds only a small physical-pixel
        // fringe for antialiasing and fractional transforms.
        val expanded = bounds.inflate(AntialiasPadding)
        val current = pending
        pending =
            if (current == null) {
                expanded
            } else {
                Rect(
                    min(current.left, expanded.left),
                    min(current.top, expanded.top),
                    max(current.right, expanded.right),
                    max(current.bottom, expanded.bottom),
                )
            }
    }

    fun requireFullFrame() {
        pending = null
        fullFrameRequired = true
    }

    fun clear() {
        pending = null
        fullFrameRequired = false
    }

    fun takeFullFrameRequest(): Boolean {
        if (!fullFrameRequired) return false
        fullFrameRequired = false
        pending = null
        return true
    }

    fun take(width: Int, height: Int): FrameDamage? {
        val bounds = pending ?: return null
        pending = null
        val clipped = bounds.intersect(Rect(0f, 0f, width.toFloat(), height.toFloat()))
        if (clipped.isEmpty) return null
        val left = floor(clipped.left).toInt().coerceIn(0, width)
        val top = floor(clipped.top).toInt().coerceIn(0, height)
        val right = ceil(clipped.right).toInt().coerceIn(left, width)
        val bottom = ceil(clipped.bottom).toInt().coerceIn(top, height)
        if (left >= right || top >= bottom) return null
        return FrameDamage(left, top, right - left, bottom - top)
    }
}

internal data class FrameDamage(val x: Int, val y: Int, val width: Int, val height: Int) {
    val pixelCount: Int
        get() = width * height

    val rect: Rect
        get() = Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
}

internal class NativeWindowHost(
    private val application: NativeApplication,
    val state: WindowState,
    private val owner: NativeWindowHost? = null,
    private val modalityType: DialogModalityType = DialogModalityType.Modeless,
) {
    private var sdlWindow: CPointer<SDL_Window>? = null
    private var skiaLayer: SkiaLayer? = null
    private var gpuInteropRegistry: GpuInteropRegistry? = null
    private var scene: androidx.compose.ui.scene.ComposeScene? = null
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
    private val accessibility = createNativeAccessibility(application::dispatchToHost)
    private val damageTracker = FrameDamageTracker()
    private val platformContext =
        SdlPlatformContext(accessibility, damageTracker) { SkiaGraphicsContext() }

    var rootForTestListener: PlatformContext.RootForTestListener?
        get() = platformContext.externalRootForTestListener
        set(value) {
            platformContext.externalRootForTestListener = value
        }

    var applicationRootForTestListener: PlatformContext.RootForTestListener?
        get() = application.rootForTestListener
        set(value) {
            application.rootForTestListener = value
        }

    fun ownsRootForTest(root: PlatformRootForTest): Boolean = platformContext.ownsRootForTest(root)

    fun captureRootForTest(root: PlatformRootForTest, boundsInWindow: Rect?): ImageBitmap =
        application.dispatchToHostBlocking { application.captureRootForTest(root, boundsInWindow) }

    fun <T> runOnUiThread(action: () -> T): T = application.dispatchToHostBlocking(action)

    val isUiThread: Boolean
        get() = application.isHostThread()

    val hasPendingTestWork: Boolean
        get() = application.dispatchToHostBlocking { application.hasPendingTestWork(this) }

    val hasPendingHostWork: Boolean
        get() =
            hasPendingRender ||
                scene?.hasPendingMeasureOrLayout == true ||
                scene?.hasInvalidations() == true

    fun captureCurrentFrame(boundsInWindow: Rect?): ImageBitmap {
        val currentMetrics = checkNotNull(metrics) { "The SDL window has no render metrics" }
        val nativeLayer = checkNotNull(skiaLayer) { "The SDL window has no Skia layer" }
        val fullImage =
            nativeLayer
                .snapshot(currentMetrics.pixelWidth, currentMetrics.pixelHeight)
                .asComposeImageBitmap()
        val bounds = boundsInWindow ?: return fullImage
        val left = floor(bounds.left).toInt().coerceIn(0, fullImage.width)
        val top = floor(bounds.top).toInt().coerceIn(0, fullImage.height)
        val right = ceil(bounds.right).toInt().coerceIn(left, fullImage.width)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top, fullImage.height)
        check(right > left && bottom > top) { "Screenshot bounds are empty: $bounds" }
        val width = right - left
        val height = bottom - top
        val cropped = ImageBitmap(width, height)
        Canvas(cropped)
            .drawImageRect(
                image = fullImage,
                srcOffset = IntOffset(left, top),
                srcSize = IntSize(width, height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(width, height),
                paint = Paint(),
            )
        fullImage.asSkiaBitmap().close()
        return cropped
    }

    private var closeRequest: () -> Unit = {}
    private var currentTitle = ""
    private var currentIcon: Painter? = null
    private var currentVisible = false
    private var currentTransparent = false
    private var windowShown = false
    private var currentUndecorated = false
    private var currentResizable = true
    private var currentEnabled = true
    private var currentFocusable = true
    private var currentAlwaysOnTop = false
    private var menuBarModel: NativeMenuModel = NativeMenuModel.Empty
    private var menuBarRevision by androidx.compose.runtime.mutableIntStateOf(0)
    private var currentPlacement = WindowPlacement.Floating
    private var currentMinimized = false
    private var currentPosition: WindowPosition = WindowPosition.PlatformDefault
    private var onPreviewKeyEvent: (KeyEvent) -> Boolean = { false }
    private var onKeyEvent: (KeyEvent) -> Boolean = { false }
    private var closed = false
    private val renderScheduled = atomic(true)
    private val forcedRenderScheduled = atomic(false)
    private val exposedFrameRendering = atomic(false)
    private val rendering = atomic(false)
    private val renderStats = nativeGetEnvironmentVariable("KTNATIVE_RENDER_STATS") != null
    private var hitTestReference: StableRef<NativeWindowHost>? = null
    private val draggableAreas = mutableMapOf<Any, Rect>()

    var isMaximized by androidx.compose.runtime.mutableStateOf(false)
        private set

    private val isRenderable: Boolean
        get() = currentVisible && windowShown && !currentMinimized

    val hasPendingRender: Boolean
        get() =
            isRenderable &&
                (renderScheduled.value ||
                    forcedRenderScheduled.value ||
                    scene?.hasInvalidations() == true)

    fun renderExposedFrame(widthHint: Int, heightHint: Int) {
        if (!exposedFrameRendering.compareAndSet(expect = false, update = true)) return
        try {
            val window = sdlWindow ?: return
            if (widthHint > 1 && heightHint > 1) {
                windowWidth = widthHint
                windowHeight = heightHint
            } else {
                memScoped {
                    val width = alloc<IntVar>()
                    val height = alloc<IntVar>()
                    kgl_get_window_size(window, width.ptr, height.ptr)
                    windowWidth = width.value.coerceAtLeast(1)
                    windowHeight = height.value.coerceAtLeast(1)
                }
            }
            renderScheduled.value = true
            // Wayland uses expose callbacks to request a buffer commit while an interactive
            // resize is in progress. The published dimensions may still match the previous
            // frame here, but presenting is required for the compositor to advance the resize.
            render(forceDraw = true)
            // SDL's Wayland backend applies pending configure acknowledgements from a frame
            // callback. Present once more from the normal event-loop turn after that callback.
            forcedRenderScheduled.value = true
            renderScheduled.value = true
            application.requestFrame()
        } finally {
            exposedFrameRendering.value = false
        }
    }

    fun requestRender() {
        renderScheduled.value = true
        skiaLayer?.needRender()
        application.requestFrame()
    }

    fun onAccessibilityBusConnected() {
        accessibility.onAccessibilityBusConnected()
    }

    fun updateMenuBar(model: NativeMenuModel) {
        val presentationChanged = menuBarModel.presentationSignature != model.presentationSignature
        menuBarModel = model
        if (presentationChanged) {
            menuBarRevision += 1
            requestRender()
        }
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
        icon: Painter?,
        visible: Boolean,
        transparent: Boolean,
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
        val nativeLayer = SkiaLayer()
        configureNativeGraphics(nativeLayer)
        val baseFlags =
            SDL_WINDOW_HIGH_PIXEL_DENSITY or
                (if (visible) 0uL else SDL_WINDOW_HIDDEN) or
                (if (undecorated) SDL_WINDOW_BORDERLESS else 0uL) or
                (if (resizable) SDL_WINDOW_RESIZABLE else 0uL) or
                (if (transparent) SDL_WINDOW_TRANSPARENT else 0uL)
        val initialX =
            (state.position as? WindowPosition.Absolute)?.x?.value?.roundToInt()
                ?: SDL_WINDOWPOS_CENTERED.toInt()
        val initialY =
            (state.position as? WindowPosition.Absolute)?.y?.value?.roundToInt()
                ?: SDL_WINDOWPOS_CENTERED.toInt()
        var candidateWindow =
            SDL_CreateWindow(
                title,
                windowWidth,
                windowHeight,
                baseFlags or nativeGraphicsWindowFlags(nativeLayer),
            )
        if (candidateWindow == null && nativeGraphicsWindowFlags(nativeLayer) != 0uL) {
            nativeLayer.renderApi = org.jetbrains.skiko.GraphicsApi.SOFTWARE_FAST
            candidateWindow = SDL_CreateWindow(title, windowWidth, windowHeight, baseFlags)
        }
        val window =
            candidateWindow ?: error("Could not create window: ${SDL_GetError()?.toKString()}")
        sdlWindow = window
        platformContext.window = window
        windowId = SDL_GetWindowID(window)
        SDL_SetWindowPosition(window, initialX, initialY)
        if (transparent) {
            check(kplatform_window_set_transparent(window, 1) != 0) {
                "Per-pixel transparency is not supported by the active SDL backend"
            }
        }
        platformContext.nativeDragAndDropManager.attach(window)
        applyWindowIcon(window, icon)
        if (modalityType != DialogModalityType.Modeless) {
            owner?.sdlWindow?.let { parent ->
                // Some SDL backends (notably the headless test driver) do not implement this hint.
                // Application-level input routing below remains authoritative.
                SDL_SetWindowParent(window, parent)
                SDL_SetWindowModal(window, true)
            }
        }
        SDL_StartTextInput(window)
        attachNativeSkiaLayer(
            layer = nativeLayer,
            window = window,
            transparency = transparent,
            queryContentScale = { queryMetrics(window).density },
            queryFullscreen = { currentPlacement == WindowPlacement.Fullscreen },
            updateFullscreen = { fullscreen ->
                val placement =
                    if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                currentPlacement = placement
                state.placement = placement
                applyPlacement()
            },
            onRenderRequested = application::requestFrame,
        )
        skiaLayer = nativeLayer
        gpuInteropRegistry =
            if (nativeLayer.renderApi == org.jetbrains.skiko.GraphicsApi.OPENGL) {
                GpuInteropRegistry(nativeLayer)
            } else {
                null
            }
        currentTitle = title
        currentIcon = icon
        currentVisible = visible
        currentTransparent = transparent
        windowShown = SDL_GetWindowFlags(window) and SDL_WINDOW_HIDDEN == 0uL
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
        SDL_SetWindowAlwaysOnTop(window, alwaysOnTop)
        applyPlacement()
        if (state.isMinimized) SDL_MinimizeWindow(window)
        isMaximized = SDL_GetWindowFlags(window) and SDL_WINDOW_MAXIMIZED != 0uL
        hitTestReference = StableRef.create(this)
        configureHitTest()
        applyMinimumSize()
        applyMaximumSize()

        val initialMetrics = queryMetrics(window)
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
            focusable && SDL_GetWindowFlags(window) and sdl3.SDL_WINDOW_INPUT_FOCUS != 0uL
        platformContext.updateLifecycle(
            isVisible = currentVisible && windowShown,
            isMinimized = currentMinimized,
        )
        updateWindowInfo(initialMetrics)
        accessibility.open(title)
        updateAccessibility(initialMetrics)
        scene = nativeScene
        nativeLayer.renderDelegate = SkikoRenderDelegate { canvas, _, _, _ ->
            nativeScene.draw(canvas.asComposeCanvas())
        }
        nativeScene.setContent(parentComposition) {
            ProvideSystemTheme(application.systemDarkThemeState.value) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPlatformAccentColor provides application.systemAccentColorState.value,
                    LocalNativeWindowHost provides this@NativeWindowHost,
                    LocalGpuInteropRegistry provides gpuInteropRegistry,
                    LocalNativeViewInvalidationDispatcher provides
                        { block ->
                            application.dispatchToHost {
                                block()
                                requestRender()
                            }
                        },
                ) {
                    @Suppress("UNUSED_VARIABLE") val revision = menuBarRevision
                    Column(Modifier.fillMaxSize()) {
                        NativeWindowMenuBar(menuBarModel)
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
        }
        println("$title: ${initialMetrics.description()}")
        println("$title: ${nativeLayer.rendererDescription}")
        val diagnostics = nativeLayer.diagnostics
        println(
            "$title: transparentBuffer=${diagnostics.hasTransparentWindowBuffer} " +
                "(requested=${diagnostics.transparencyRequested}), " +
                "frameBuffers=${diagnostics.effectiveFrameBufferCount ?: "unknown"} " +
                "(requested=${diagnostics.frameBuffering})"
        )
    }

    fun update(
        title: String,
        icon: Painter?,
        visible: Boolean,
        transparent: Boolean,
        undecorated: Boolean,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        requestedSize: DpSize,
    ) {
        val window = sdlWindow ?: return
        if (title != currentTitle) {
            SDL_SetWindowTitle(window, title)
            currentTitle = title
        }
        // A stable Painter may depend on mutable drawing state, so rerasterize it on every
        // successful composition update instead of relying on object identity.
        if (icon != null || currentIcon != null) {
            applyWindowIcon(window, icon)
            currentIcon = icon
        }
        check(transparent == currentTransparent)
        if (visible != currentVisible) {
            if (visible) SDL_ShowWindow(window) else SDL_HideWindow(window)
            currentVisible = visible
            windowShown = visible
            if (visible) requestRender()
        }
        if (undecorated != currentUndecorated) {
            SDL_SetWindowBordered(window, !undecorated)
            currentUndecorated = undecorated
            configureHitTest()
            requestRender()
        }
        if (resizable != currentResizable) {
            SDL_SetWindowResizable(window, resizable)
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
            focusable && SDL_GetWindowFlags(window) and sdl3.SDL_WINDOW_INPUT_FOCUS != 0uL
        this.onPreviewKeyEvent = onPreviewKeyEvent
        this.onKeyEvent = onKeyEvent
        if (alwaysOnTop != currentAlwaysOnTop) {
            SDL_SetWindowAlwaysOnTop(window, alwaysOnTop)
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
        if (
            currentPlacement.persistsFloatingGeometry() &&
                state.position != currentPosition &&
                state.position is WindowPosition.Absolute
        ) {
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
        if (
            currentPlacement.persistsFloatingGeometry() &&
                (requestedWidth != windowWidth || requestedHeight != windowHeight)
        ) {
            windowWidth = requestedWidth
            windowHeight = requestedHeight
            SDL_SetWindowSize(window, requestedWidth, requestedHeight)
            requestRender()
        }
        updateAccessibility()
    }

    internal val nativeWindow: CPointer<SDL_Window>?
        get() = sdlWindow

    private val documentRoot: NativeWindowHost
        get() = owner?.documentRoot ?: this

    private fun isDescendantOf(candidate: NativeWindowHost): Boolean {
        var current = owner
        while (current != null) {
            if (current === candidate) return true
            current = current.owner
        }
        return false
    }

    fun blocksInputTo(target: NativeWindowHost): Boolean =
        modalityBlocksInput(
            modalityType = modalityType,
            modalActive = isRenderable,
            sameDocument = owner != null && target.documentRoot === owner.documentRoot,
            targetIsModalOrDescendant = target === this || target.isDescendantOf(this),
        )

    fun owns(event: SDL_Event): Boolean =
        when (event.type) {
            SDL_EVENT_WINDOW_CLOSE_REQUESTED.toUInt(),
            SDL_EVENT_WINDOW_SHOWN.toUInt(),
            SDL_EVENT_WINDOW_EXPOSED.toUInt(),
            SDL_EVENT_WINDOW_HIDDEN.toUInt(),
            SDL_EVENT_WINDOW_MAXIMIZED.toUInt(),
            SDL_EVENT_WINDOW_MINIMIZED.toUInt(),
            SDL_EVENT_WINDOW_RESTORED.toUInt(),
            SDL_EVENT_WINDOW_MOVED.toUInt(),
            SDL_EVENT_WINDOW_FOCUS_GAINED.toUInt(),
            SDL_EVENT_WINDOW_FOCUS_LOST.toUInt(),
            SDL_EVENT_WINDOW_MOUSE_ENTER.toUInt(),
            SDL_EVENT_WINDOW_MOUSE_LEAVE.toUInt(),
            SDL_EVENT_WINDOW_RESIZED.toUInt(),
            SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED.toUInt() -> event.window.windowID == windowId
            SDL_EVENT_KEY_DOWN.toUInt(),
            SDL_EVENT_KEY_UP.toUInt() -> event.key.windowID == windowId
            SDL_EVENT_TEXT_INPUT.toUInt() -> event.text.windowID == windowId
            SDL_EVENT_TEXT_EDITING.toUInt() -> event.edit.windowID == windowId
            SDL_EVENT_MOUSE_MOTION.toUInt() -> event.motion.windowID == windowId
            SDL_EVENT_MOUSE_WHEEL.toUInt() -> event.wheel.windowID == windowId
            SDL_EVENT_MOUSE_BUTTON_DOWN.toUInt(),
            SDL_EVENT_MOUSE_BUTTON_UP.toUInt() -> event.button.windowID == windowId
            SDL_EVENT_FINGER_DOWN.toUInt(),
            SDL_EVENT_FINGER_MOTION.toUInt(),
            SDL_EVENT_FINGER_UP.toUInt() -> event.tfinger.windowID == windowId
            SDL_EVENT_DROP_BEGIN.toUInt(),
            SDL_EVENT_DROP_COMPLETE.toUInt(),
            SDL_EVENT_DROP_FILE.toUInt(),
            SDL_EVENT_DROP_TEXT.toUInt() -> event.drop.windowID == windowId
            else -> false
        }

    fun handle(event: SDL_Event) {
        when (event.type) {
            SDL_EVENT_WINDOW_CLOSE_REQUESTED.toUInt() -> requestClose()
            SDL_EVENT_WINDOW_SHOWN.toUInt() -> {
                windowShown = true
                updateLifecycle()
                requestRender()
            }
            SDL_EVENT_WINDOW_EXPOSED.toUInt() -> requestRender()
            SDL_EVENT_WINDOW_HIDDEN.toUInt() -> {
                windowShown = false
                updateLifecycle()
            }
            SDL_EVENT_WINDOW_MAXIMIZED.toUInt() -> {
                isMaximized = true
                currentPlacement = WindowPlacement.Maximized
                state.placement = WindowPlacement.Maximized
                requestRender()
            }
            SDL_EVENT_WINDOW_MINIMIZED.toUInt() -> {
                currentMinimized = true
                state.isMinimized = true
                updateLifecycle()
            }
            SDL_EVENT_WINDOW_RESTORED.toUInt() -> {
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
            SDL_EVENT_WINDOW_MOVED.toUInt() -> {
                val nextPosition = WindowPosition(event.window.data1.dp, event.window.data2.dp)
                currentPosition = nextPosition
                if (currentPlacement.persistsFloatingGeometry()) state.position = nextPosition
                requestRender()
            }
            SDL_EVENT_WINDOW_FOCUS_GAINED.toUInt() -> {
                platformContext.windowInfo.isWindowFocused = currentFocusable
                updateLifecycle()
                requestRender()
            }
            SDL_EVENT_WINDOW_FOCUS_LOST.toUInt() -> {
                platformContext.windowInfo.isWindowFocused = false
                updateLifecycle()
                clearPointerButtons()
                touchPoints.clear()
                scene?.cancelPointerInput()
                requestRender()
            }
            SDL_EVENT_WINDOW_MOUSE_ENTER.toUInt() ->
                pointer(PointerEventType.Enter, pointerX, pointerY)
            SDL_EVENT_WINDOW_MOUSE_LEAVE.toUInt() ->
                pointer(PointerEventType.Exit, pointerX, pointerY)
            SDL_EVENT_WINDOW_RESIZED.toUInt() -> {
                windowWidth = event.window.data1.coerceAtLeast(1)
                windowHeight = event.window.data2.coerceAtLeast(1)
                if (currentPlacement.persistsFloatingGeometry()) {
                    state.size = DpSize(windowWidth.dp, windowHeight.dp)
                }
                requestRender()
            }
            SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED.toUInt() -> requestRender()
            SDL_EVENT_KEY_DOWN.toUInt(),
            SDL_EVENT_KEY_UP.toUInt() -> {
                if (!currentEnabled || !currentFocusable) return
                key(
                    key = composeKeyForSdlScancode(event.key.scancode.toInt()),
                    type =
                        if (event.type == SDL_EVENT_KEY_DOWN.toUInt()) {
                            KeyEventType.KeyDown
                        } else {
                            KeyEventType.KeyUp
                        },
                    modifiers = event.key.mod.toInt(),
                )
                if (event.type == SDL_EVENT_KEY_DOWN.toUInt() && event.key.key == SDLK_ESCAPE) {
                    requestClose()
                }
            }
            SDL_EVENT_TEXT_INPUT.toUInt() ->
                if (currentEnabled && currentFocusable) {
                    textInput(event.text.text?.toKString().orEmpty())
                }
            SDL_EVENT_TEXT_EDITING.toUInt() ->
                if (currentEnabled && currentFocusable) {
                    platformContext.updateComposingText(event.edit.text?.toKString().orEmpty())
                }
            SDL_EVENT_MOUSE_MOTION.toUInt() -> {
                platformContext.nativeDragAndDropManager.pointerMotion()
                if (!currentEnabled) return
                if (event.motion.which == UInt.MAX_VALUE) return
                pointerX = event.motion.x.roundToInt()
                pointerY = event.motion.y.roundToInt()
                pointer(PointerEventType.Move, pointerX, pointerY)
                if (dropAccepted) scene?.rootDragAndDropNode?.onMoved(currentDropEvent())
            }
            SDL_EVENT_MOUSE_WHEEL.toUInt() -> {
                if (!currentEnabled) return
                val direction =
                    if (
                        event.wheel.direction == sdl3.SDL_MouseWheelDirection.SDL_MOUSEWHEEL_FLIPPED
                    ) {
                        1f
                    } else {
                        -1f
                }
                val step = 40f * (metrics?.density ?: 1f)
                val modifiers = SDL_GetModState().toInt()
                val scrollDelta =
                    nativeMouseWheelScrollDelta(
                        x = event.wheel.x * step * direction,
                        y = event.wheel.y * step * direction,
                        isShiftPressed = modifiers and SdlShiftMask != 0,
                    )
                pointer(
                    PointerEventType.Scroll,
                    pointerX,
                    pointerY,
                    scrollDelta = scrollDelta,
                )
            }
            SDL_EVENT_MOUSE_BUTTON_DOWN.toUInt(),
            SDL_EVENT_MOUSE_BUTTON_UP.toUInt() -> {
                if (!currentEnabled) return
                if (event.button.which == UInt.MAX_VALUE) return
                pointerX = event.button.x.roundToInt()
                pointerY = event.button.y.roundToInt()
                val button = pointerButton(event.button.button) ?: return
                setButtonPressed(button, event.type == SDL_EVENT_MOUSE_BUTTON_DOWN.toUInt())
                pointer(
                    if (event.type == SDL_EVENT_MOUSE_BUTTON_DOWN.toUInt()) {
                        PointerEventType.Press
                    } else {
                        PointerEventType.Release
                    },
                    pointerX,
                    pointerY,
                    button = button,
                )
                if (
                    event.type == SDL_EVENT_MOUSE_BUTTON_UP.toUInt() &&
                        button == PointerButton.Primary
                ) {
                    platformContext.nativeDragAndDropManager.pointerRelease()
                }
            }
            SDL_EVENT_FINGER_DOWN.toUInt(),
            SDL_EVENT_FINGER_MOTION.toUInt(),
            SDL_EVENT_FINGER_UP.toUInt() -> {
                if (!currentEnabled) return
                touch(event)
            }
            SDL_EVENT_DROP_BEGIN.toUInt() -> beginDrop()
            SDL_EVENT_DROP_FILE.toUInt() -> receiveDropItem(event, isFile = true)
            SDL_EVENT_DROP_TEXT.toUInt() -> receiveDropItem(event, isFile = false)
            SDL_EVENT_DROP_COMPLETE.toUInt() -> completeDrop()
        }
    }

    fun requestClose() = closeRequest()

    fun minimize() {
        sdlWindow?.let { SDL_MinimizeWindow(it) }
    }

    fun toggleMaximized() {
        val nextPlacement =
            if (currentPlacement == WindowPlacement.Maximized || isMaximized) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Maximized
            }
        currentPlacement = nextPlacement
        state.placement = nextPlacement
        applyPlacement()
        requestRender()
    }

    fun requestFocus() {
        sdlWindow?.let { SDL_RaiseWindow(it) }
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
        val id = event.tfinger.fingerID.toLong()
        val released = event.type == SDL_EVENT_FINGER_UP.toUInt()
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
                SDL_EVENT_FINGER_DOWN.toUInt() -> PointerEventType.Press
                SDL_EVENT_FINGER_UP.toUInt() -> PointerEventType.Release
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
            timeMillis = (event.tfinger.timestamp / 1_000_000uL).toLong(),
        )
        if (released) touchPoints.remove(id)
    }

    private fun beginDrop() {
        if (dropAccepted) {
            val dragEvent = currentDropEvent()
            scene?.rootDragAndDropNode?.onExited(dragEvent)
            scene?.rootDragAndDropNode?.onEnded(dragEvent)
        }
        clearDrop()
        updateDropPointerPosition()
        startDropIfAccepted()
    }

    private fun receiveDropItem(event: SDL_Event, isFile: Boolean) {
        val nativeData = event.drop.data ?: return
        val value = nativeData.toKString()
        if (isFile) droppedFiles += value else droppedText = value
        updateDropPointerPosition()
        startDropIfAccepted()
    }

    private fun completeDrop() {
        if (dropAccepted) {
            val dragEvent = currentDropEvent()
            scene?.rootDragAndDropNode?.onDrop(dragEvent)
            scene?.rootDragAndDropNode?.onEnded(dragEvent)
        }
        clearDrop()
    }

    private fun startDropIfAccepted() {
        if (dropAccepted) return
        val root = scene?.rootDragAndDropNode ?: return
        val dragEvent = currentDropEvent()
        dropAccepted = root.acceptDragAndDropTransfer(dragEvent)
        if (dropAccepted) {
            root.onStarted(dragEvent)
            root.onEntered(dragEvent)
            root.onMoved(dragEvent)
        }
    }

    private fun updateDropPointerPosition() {
        memScoped {
            val x = alloc<FloatVar>()
            val y = alloc<FloatVar>()
            SDL_GetMouseState(x.ptr, y.ptr)
            pointerX = x.value.roundToInt()
            pointerY = y.value.roundToInt()
        }
    }

    private fun clearDrop() {
        droppedFiles.clear()
        droppedText = null
        dropAccepted = false
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
        if (menuBarModel.activateShortcut(event)) return
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

    fun render(forceDraw: Boolean = false) {
        if (!isRenderable) return
        val window = sdlWindow ?: return
        val nativeLayer = skiaLayer ?: return
        val nativeScene = scene ?: return
        if (!rendering.compareAndSet(expect = false, update = true)) {
            if (forceDraw) forcedRenderScheduled.value = true
            renderScheduled.value = true
            application.requestFrame()
            return
        }
        try {
            renderScheduled.value = false
            val scheduledForceDraw = forcedRenderScheduled.getAndSet(false)
            val nextMetrics = queryMetrics(window)
            val metricsChanged = nextMetrics != metrics
            if (metricsChanged) {
                val densityChanged = nextMetrics.density != metrics?.density
                metrics = nextMetrics
                nativeScene.density = Density(nextMetrics.density)
                nativeScene.size = IntSize(nextMetrics.pixelWidth, nextMetrics.pixelHeight)
                clearPointerButtons()
                updateWindowInfo(nextMetrics)
                if (densityChanged) println("$currentTitle: ${nextMetrics.description()}")
            }
            platformContext.updateTextInputRect(nextMetrics)
            if (currentTransparent && metricsChanged) {
                kplatform_window_set_transparent(window, 1)
            }
            val hadPendingLayout = nativeScene.hasPendingMeasureOrLayout
            val composeNeedsDraw =
                forceDraw ||
                    scheduledForceDraw ||
                    metricsChanged ||
                    hadPendingLayout ||
                    nativeScene.hasInvalidations()
            if (composeNeedsDraw) {
                if (hadPendingLayout) nativeScene.measureAndLayout()
                nativeLayer.render(force = true)
                damageTracker.clear()
                if (renderStats) {
                    val total = nextMetrics.pixelWidth * nextMetrics.pixelHeight
                    println(
                        "Render [Skia GPU]: $total pixels, CPU upload=0B, " +
                            "layout=$hadPendingLayout resize=$metricsChanged"
                    )
                }
            }
            // Accessibility updates are coalesced and performed after the visual frame is
            // presented.
            updateAccessibility(nextMetrics)
            accessibility.refreshAfterLayout()
            if (nativeScene.hasInvalidations()) requestRender()
        } finally {
            rendering.value = false
        }
    }

    private fun updateAccessibility(currentMetrics: RenderMetrics? = metrics) {
        val resolvedMetrics = currentMetrics ?: return
        val window = sdlWindow ?: return
        memScoped {
            val x = alloc<IntVar>()
            val y = alloc<IntVar>()
            SDL_GetWindowPosition(window, x.ptr, y.ptr)
            accessibility.updateWindow(
                title = currentTitle,
                visible = currentVisible && windowShown && !currentMinimized,
                focused = platformContext.windowInfo.isWindowFocused,
                screenX = x.value,
                screenY = y.value,
                width = resolvedMetrics.windowWidth,
                height = resolvedMetrics.windowHeight,
                scaleX = resolvedMetrics.inputScaleX,
                scaleY = resolvedMetrics.inputScaleY,
            )
        }
    }

    private fun updateWindowInfo(metrics: RenderMetrics) {
        platformContext.windowInfo.containerSize = IntSize(metrics.pixelWidth, metrics.pixelHeight)
        platformContext.windowInfo.containerDpSize =
            DpSize(
                (metrics.pixelWidth / metrics.density).dp,
                (metrics.pixelHeight / metrics.density).dp,
            )
    }

    private fun queryMetrics(window: CPointer<SDL_Window>): RenderMetrics = memScoped {
        val pixelWidth = alloc<IntVar>()
        val pixelHeight = alloc<IntVar>()
        SDL_GetWindowSizeInPixels(window, pixelWidth.ptr, pixelHeight.ptr)
        val density = SDL_GetWindowDisplayScale(window).takeIf { it > 0f } ?: 1f
        RenderMetrics(
            windowWidth = windowWidth.coerceAtLeast(1),
            windowHeight = windowHeight.coerceAtLeast(1),
            pixelWidth = pixelWidth.value.coerceAtLeast(1),
            pixelHeight = pixelHeight.value.coerceAtLeast(1),
            density = density,
        )
    }

    private fun applyMinimumSize() {
        val window = sdlWindow ?: return
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
        val window = sdlWindow ?: return
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
        val window = sdlWindow ?: return
        when (currentPlacement) {
            WindowPlacement.Fullscreen ->
                check(SDL_SetWindowFullscreen(window, true)) {
                    "Could not enter fullscreen: ${SDL_GetError()?.toKString()}"
                }
            WindowPlacement.Maximized -> {
                SDL_SetWindowFullscreen(window, false)
                SDL_MaximizeWindow(window)
            }
            WindowPlacement.Floating -> {
                SDL_SetWindowFullscreen(window, false)
                SDL_RestoreWindow(window)
            }
        }
    }

    private fun configureHitTest() {
        val window = sdlWindow ?: return
        if (currentUndecorated) {
            val reference = checkNotNull(hitTestReference)
            check(SDL_SetWindowHitTest(window, NativeWindowHitTest, reference.asCPointer())) {
                "Window hit testing is unavailable: ${SDL_GetError()?.toKString()}"
            }
        } else {
            SDL_SetWindowHitTest(window, null, null)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val nativeLayer = skiaLayer
        if (nativeLayer?.renderApi == org.jetbrains.skiko.GraphicsApi.OPENGL) {
            nativeLayer.withOpenGlContext { scene?.close() }
        } else {
            scene?.close()
        }
        scene = null
        accessibility.close()
        platformContext.nativeDragAndDropManager.close()
        platformContext.close()
        sdlWindow?.let { SDL_SetWindowHitTest(it, null, null) }
        hitTestReference?.dispose()
        hitTestReference = null
        gpuInteropRegistry = null
        skiaLayer?.detach()
        skiaLayer = null
        sdlWindow?.let { SDL_DestroyWindow(it) }
        sdlWindow = null
        platformContext.window = null
    }

    private fun updateLifecycle() {
        platformContext.updateLifecycle(
            isVisible = currentVisible && windowShown,
            isMinimized = currentMinimized,
        )
        updateAccessibility()
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

/** Converts Shift + a vertical mouse-wheel tick into horizontal scrolling. */
internal fun nativeMouseWheelScrollDelta(
    x: Float,
    y: Float,
    isShiftPressed: Boolean,
): Offset =
    if (isShiftPressed && x == 0f) {
        Offset(x = y, y = 0f)
    } else {
        Offset(x = x, y = y)
    }

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
