@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package androidx.compose.ui.viewinterop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoImage
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import cairo.kgpu_layer_create
import cairo.kgpu_layer_destroy
import cairo.kgpu_layer_draw_mesh
import cairo.kgpu_layer_finish
import cairo.kgpu_layer_framebuffer
import cairo.kgpu_layer_prepare
import cairo.kgpu_context_renderer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

internal val LocalGpuInteropRegistry = staticCompositionLocalOf<GpuInteropRegistry?> { null }

internal val LocalNativeViewInvalidationDispatcher =
    staticCompositionLocalOf<((() -> Unit) -> Unit)> { { it() } }

internal class GpuInteropRegistry {
    var context: COpaquePointer? = null
    var rootCanvas: CairoCanvas? = null
    private val layers = mutableListOf<GpuNativeViewLayer>()

    fun create(view: InteropView): GpuNativeViewLayer =
        GpuNativeViewLayer(this, view).also(layers::add)

    fun remove(layer: GpuNativeViewLayer) {
        layers.remove(layer)
    }

    fun draw() {
        val gpu = context ?: return
        layers.toList().forEach { it.draw(gpu) }
    }

    fun clearMask(path: Path) {
        rootCanvas?.clearInteropPathInRoot(path)
    }

}

internal class GpuNativeViewLayer(
    private val registry: GpuInteropRegistry,
    private val view: InteropView,
) : AutoCloseable {
    private companion object {
        // Matches the subdivision used by the Cairo graphics-layer perspective renderer.
        const val MeshColumns = 12
        const val MeshRows = 12
    }

    private val handle = checkNotNull(kgpu_layer_create())
    private val transformedMask = Path()
    private var size = IntSize.Zero
    private var density = 1f
    private var meshPositions = FloatArray(0)

    fun resize(value: IntSize, density: Float) {
        size = value
        this.density = density
    }

    fun updateDensity(value: Float) {
        density = value
    }

    fun updateGeometry(coordinates: LayoutCoordinates, localMask: Path) {
        if (!coordinates.isAttached || size.width <= 0 || size.height <= 0) return
        val transform = Matrix()
        coordinates.findRootCoordinates().transformFrom(coordinates, transform)
        transformedMask.reset()
        transformedMask.fillType = localMask.fillType
        transformedMask.addPath(localMask)
        transformedMask.transform(transform)
        registry.clearMask(transformedMask)

        val stride = MeshColumns + 1
        val requiredSize = (MeshRows + 1) * stride * 2
        val positions =
            if (meshPositions.size == requiredSize) meshPositions else FloatArray(requiredSize)
        for (row in 0..MeshRows) {
            for (column in 0..MeshColumns) {
                val local =
                    androidx.compose.ui.geometry.Offset(
                        size.width * column.toFloat() / MeshColumns,
                        size.height * row.toFloat() / MeshRows,
                    )
                val rootPosition = coordinates.localToRoot(local)
                val index = (row * stride + column) * 2
                positions[index] = rootPosition.x
                positions[index + 1] = rootPosition.y
            }
        }
        meshPositions = positions
    }

    fun render(): Boolean {
        val gpu = registry.context ?: return false
        if (size.width <= 0 || size.height <= 0) return false
        if (kgpu_layer_prepare(gpu, handle, size.width, size.height) == 0) return false
        return try {
            view.renderOpenGl(
                OpenGlInteropRenderTarget(
                    framebuffer = kgpu_layer_framebuffer(handle),
                    width = size.width,
                    height = size.height,
                    internalFormat = 0x8058, // GL_RGBA8
                    renderer = kgpu_context_renderer(gpu)?.toKString() ?: "unknown",
                    density = density,
                )
            )
        } finally {
            kgpu_layer_finish(gpu)
        }
    }

    fun draw(gpu: COpaquePointer) {
        val positions = meshPositions
        if (positions.isEmpty()) return
        positions.usePinned {
            kgpu_layer_draw_mesh(
                gpu,
                handle,
                it.addressOf(0),
                MeshColumns,
                MeshRows,
                1f,
            )
        }
    }

    override fun close() {
        registry.remove(this)
        registry.context?.let { kgpu_layer_destroy(it, handle) }
    }
}

private class NativeViewFrame(size: IntSize) : AutoCloseable {
    val image = CairoImage(size.width, size.height)
    val target =
        InteropRenderTarget(
            pixels = image.surface.data,
            width = size.width,
            height = size.height,
            stride = image.surface.stride,
        )

    override fun close() = image.close()
}

/**
 * Places a native framebuffer renderer inside Compose.
 *
 * [clipPath] supplies an optional local-coordinate alpha mask. GPU views are projected through
 * the current Compose transform (including perspective rotation), and the path is cut from the
 * Cairo frame with antialiased coverage before the external texture is composited. No native
 * framebuffer pixels are read back to the CPU.
 *
 * Compose content can be drawn over the native view, and pointer input remains handled by Compose.
 */
@Composable
fun NativeView(
    factory: () -> InteropView,
    modifier: Modifier = Modifier,
    update: (InteropView) -> Unit = {},
    onRelease: (InteropView) -> Unit = { it.close() },
    clipPath: ((Size) -> Path)? = null,
) {
    val view = remember { factory() }
    val density = LocalDensity.current.density
    val focusRequester = remember { FocusRequester() }
    val gpuRegistry = LocalGpuInteropRegistry.current
    val dispatchInvalidation = LocalNativeViewInvalidationDispatcher.current
    val gpuLayer =
        remember(view, gpuRegistry) {
            if (view.backend == InteropRenderBackend.OpenGl) {
                requireNotNull(gpuRegistry) { "OpenGL interop requires the ui-sdl2 window host" }
                    .create(view)
            } else {
                null
            }
        }
    var nativeFrame by remember { mutableStateOf<NativeViewFrame?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var renderRequest by remember(view) { mutableLongStateOf(1L) }
    val renderedRequest = remember(view) { longArrayOf(Long.MIN_VALUE) }
    val boundsMask = remember { Path() }
    val callbackActive = remember(view) { booleanArrayOf(false) }
    val requestRender: () -> Unit = remember(view, dispatchInvalidation) {
        {
            dispatchInvalidation {
                if (callbackActive[0]) renderRequest += 1L
            }
        }
    }

    SideEffect {
        update(view)
        gpuLayer?.updateDensity(density)
    }

    LaunchedEffect(view, view.continuousRendering) {
        if (view.continuousRendering) {
            while (true) {
                withFrameNanos { requestRender() }
            }
        }
    }

    DisposableEffect(view) {
        callbackActive[0] = true
        view.setRenderInvalidationCallback(requestRender)
        onDispose {
            callbackActive[0] = false
            view.setRenderInvalidationCallback(null)
            nativeFrame?.close()
            nativeFrame = null
            gpuLayer?.close()
            onRelease(view)
        }
    }

    Canvas(
        modifier =
            modifier
                .then(
                    if (view.acceptsInput) {
                        Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { view.setFocused(it.isFocused) }
                            .focusable()
                            .onKeyEvent { event ->
                                var modifiers = 0
                                if (event.isCtrlPressed) modifiers = modifiers or 1
                                if (event.isShiftPressed) modifiers = modifiers or 2
                                if (event.isAltPressed) modifiers = modifiers or 4
                                if (event.isMetaPressed) modifiers = modifiers or 8
                                view.sendKeyEvent(
                                    InteropKeyEvent(
                                        keyCode = event.key.keyCode,
                                        codePoint = event.utf16CodePoint,
                                        pressed = event.type == KeyEventType.KeyDown,
                                        modifiers = modifiers,
                                    )
                                )
                            }
                            .pointerInput(view) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        val button =
                                            when (event.button) {
                                                PointerButton.Secondary -> 3
                                                PointerButton.Tertiary -> 2
                                                else -> 1
                                            }
                                        val type =
                                            when (event.type) {
                                                PointerEventType.Press,
                                                PointerEventType.Release -> InteropPointerEventType.Button
                                                PointerEventType.Scroll -> InteropPointerEventType.Scroll
                                                else -> InteropPointerEventType.Move
                                            }
                                        if (event.type == PointerEventType.Press) {
                                            focusRequester.requestFocus()
                                        }
                                        var modifiers = 0
                                        if (event.keyboardModifiers.isCtrlPressed) modifiers = modifiers or 1
                                        if (event.keyboardModifiers.isShiftPressed) modifiers = modifiers or 2
                                        if (event.keyboardModifiers.isAltPressed) modifiers = modifiers or 4
                                        if (event.keyboardModifiers.isMetaPressed) modifiers = modifiers or 8
                                        val handled =
                                            view.sendPointerEvent(
                                                InteropPointerEvent(
                                                    type = type,
                                                    x = change.position.x,
                                                    y = change.position.y,
                                                    timeMillis = change.uptimeMillis,
                                                    button = button,
                                                    pressed = event.type == PointerEventType.Press,
                                                    scrollDeltaX = change.scrollDelta.x,
                                                    scrollDeltaY = change.scrollDelta.y,
                                                    modifiers = modifiers,
                                                )
                                            )
                                        if (handled) change.consume()
                                    }
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .onSizeChanged { size ->
                    if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                    if (gpuLayer != null) {
                        gpuLayer.resize(size, density)
                        renderRequest += 1L
                        return@onSizeChanged
                    }
                    val current = nativeFrame
                    if (current?.target?.width == size.width && current.target.height == size.height) {
                        return@onSizeChanged
                    }
                    current?.close()
                    nativeFrame = NativeViewFrame(size)
                    renderRequest += 1L
                }.onGloballyPositioned { value ->
                coordinates = value
            }
    ) {
        val requestedRender = renderRequest
        val mask =
            clipPath?.invoke(size)
                ?: boundsMask.apply {
                    reset()
                    addRect(Rect(0f, 0f, size.width, size.height))
                }
        if (gpuLayer != null) {
            coordinates?.let { gpuLayer.updateGeometry(it, mask) }
            clipPath(mask) {
                drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            }
            if (renderedRequest[0] != requestedRender) {
                gpuLayer.render()
                renderedRequest[0] = requestedRender
            }
            return@Canvas
        }
        val frame = nativeFrame ?: return@Canvas
        if (renderedRequest[0] != requestedRender) {
            frame.image.surface.flush()
            if (view.render(frame.target)) frame.image.surface.markDirty()
            renderedRequest[0] = requestedRender
        }
        if (clipPath == null) {
            drawImage(frame.image)
        } else {
            clipPath(mask) { drawImage(frame.image) }
        }
    }
}
