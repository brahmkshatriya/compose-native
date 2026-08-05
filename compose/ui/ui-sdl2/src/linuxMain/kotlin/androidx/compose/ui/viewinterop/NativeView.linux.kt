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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import cairo.kgpu_layer_create
import cairo.kgpu_layer_destroy
import cairo.kgpu_layer_draw_mesh
import cairo.kgpu_layer_finish
import cairo.kgpu_layer_framebuffer
import cairo.kgpu_layer_prepare
import cairo.kgpu_layer_read_pixels
import cairo.kgpu_context_renderer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

internal val LocalGpuInteropRegistry = staticCompositionLocalOf<GpuInteropRegistry?> { null }

internal val LocalNativeViewInvalidationDispatcher =
    staticCompositionLocalOf<((() -> Unit) -> Unit)> { { it() } }

internal enum class GpuInteropEmission {
    Unsupported,
    External,
    Rasterized,
}

internal interface GpuInteropLayerCommand {
    val compositionId: Long
    fun draw(gpu: COpaquePointer): Boolean
    fun snapshot(gpu: COpaquePointer?): CairoImage? = null

    fun drawRasterized(
        gpu: COpaquePointer?,
        canvas: CairoCanvas,
        maskInLocal: Path,
    ): Boolean {
        val image = snapshot(gpu) ?: return false
        try {
            canvas.save()
            try {
                canvas.clipPath(maskInLocal)
                canvas.drawSurface(image.surface, 0f, 0f, 1f, BlendMode.SrcOver)
            } finally {
                canvas.restore()
            }
        } finally {
            image.close()
        }
        return true
    }
}

internal interface GpuInteropCompositionRecorder {
    fun emit(
        layer: GpuInteropLayerCommand,
        canvas: androidx.compose.ui.graphics.Canvas,
        maskInRoot: Path,
        maskInLocal: Path,
    ): GpuInteropEmission
}

internal class GpuInteropRegistry(
    private val onFallbackDamage: (Rect) -> Unit = {},
) {
    var context: COpaquePointer? = null
    var rootCanvas: CairoCanvas? = null
    var compositionRecorder: GpuInteropCompositionRecorder? = null
    private val layers = mutableListOf<GpuNativeViewLayer>()
    private val fallbackMasks = mutableMapOf<Long, Path>()
    private var nextCompositionId = 1L

    fun create(view: InteropView): GpuNativeViewLayer =
        GpuNativeViewLayer(this, nextCompositionId++, view).also(layers::add)

    fun remove(layer: GpuNativeViewLayer) {
        layers.remove(layer)
        fallbackMasks.remove(layer.compositionId)
    }

    fun draw(): Int {
        val gpu = context ?: return 0
        val fallbackIds = fallbackMasks.keys.toSet()
        var drawnLayers = 0
        layers.toList().forEach {
            if (it.compositionId in fallbackIds && it.draw(gpu)) drawnLayers++
        }
        return drawnLayers
    }

    fun emitBoundary(
        layer: GpuInteropLayerCommand,
        canvas: androidx.compose.ui.graphics.Canvas,
        maskInRoot: Path,
        maskInLocal: Path,
    ): GpuInteropEmission {
        val result =
            compositionRecorder?.emit(layer, canvas, maskInRoot, maskInLocal)
                ?: GpuInteropEmission.Unsupported
        if (result != GpuInteropEmission.Unsupported) {
            fallbackMasks.remove(layer.compositionId)
        }
        return result
    }

    fun setFallbackMask(layer: GpuInteropLayerCommand, path: Path) {
        fallbackMasks[layer.compositionId] = Path().also { it.addPath(path) }
    }

    fun refreshFallbackMask(
        layer: GpuInteropLayerCommand,
        path: Path,
        previousBounds: Rect,
    ): Boolean {
        if (layer.compositionId !in fallbackMasks) return false
        setFallbackMask(layer, path)
        onFallbackDamage(previousBounds)
        onFallbackDamage(path.getBounds())
        return true
    }

    fun applyFallbackMasks() {
        val canvas = rootCanvas ?: return
        fallbackMasks.values.forEach(canvas::clearInteropPathInRoot)
    }

    val rasterReadbacks: Long
        get() = layers.sumOf(GpuNativeViewLayer::rasterReadbacks)

    val rasterReadbackBytes: Long
        get() = layers.sumOf(GpuNativeViewLayer::rasterReadbackBytes)
}

internal class GpuNativeViewSnapshotCacheState {
    private var renderGeneration = 0L
    private var snapshotGeneration = -1L

    fun onRendered() {
        renderGeneration++
    }

    fun invalidateSnapshot() {
        snapshotGeneration = -1L
    }

    val needsReadback: Boolean
        get() = snapshotGeneration != renderGeneration

    fun onReadback() {
        snapshotGeneration = renderGeneration
    }
}

internal class GpuNativeViewRenderInvalidation {
    private var pending = true

    fun request() {
        pending = true
    }

    fun consume(block: () -> Boolean): Boolean {
        if (!pending) return false
        pending = false
        val rendered = block()
        if (!rendered) pending = true
        return rendered
    }
}

internal class GpuNativeViewLayer(
    private val registry: GpuInteropRegistry,
    override val compositionId: Long,
    private val view: InteropView,
) : GpuInteropLayerCommand, AutoCloseable {
    private companion object {
        // Matches the subdivision used by the Cairo graphics-layer perspective renderer.
        const val MeshColumns = 12
        const val MeshRows = 12
        // Cairo antialiasing can expose a subpixel fringe between the transparent root cutout
        // and an exactly coincident external polygon. Draw slightly underneath the cutout; the
        // retained CPU scene remains the authoritative clip and covers this overdraw completely.
        const val EdgeOverdrawPixels = 1.5f
    }

    private val handle = checkNotNull(kgpu_layer_create())
    internal val transformedMask = Path()
    private val localMask = Path()
    private var size = IntSize.Zero
    private var density = 1f
    private var meshPositions = FloatArray(0)
    private val renderInvalidation = GpuNativeViewRenderInvalidation()
    private val snapshotCacheState = GpuNativeViewSnapshotCacheState()
    private var cachedSnapshot: CairoImage? = null
    private var hasRendered = false

    internal var rasterReadbacks = 0L
        private set
    internal var rasterReadbackBytes = 0L
        private set

    fun resize(value: IntSize, density: Float) {
        if (size != value || this.density != density) {
            size = value
            this.density = density
            renderInvalidation.request()
            snapshotCacheState.invalidateSnapshot()
        }
    }

    fun updateDensity(value: Float) {
        if (density != value) {
            density = value
            renderInvalidation.request()
        }
    }

    fun requestRender() {
        renderInvalidation.request()
    }

    fun updateGeometry(coordinates: LayoutCoordinates, mask: Path) {
        localMask.reset()
        localMask.fillType = mask.fillType
        localMask.addPath(mask)
        refreshGeometry(coordinates)
    }

    fun refreshGeometry(coordinates: LayoutCoordinates): Boolean {
        if (!coordinates.isAttached || size.width <= 0 || size.height <= 0 || localMask.isEmpty) {
            return false
        }
        val transform = Matrix()
        coordinates.findRootCoordinates().transformFrom(coordinates, transform)
        transformedMask.reset()
        transformedMask.fillType = localMask.fillType
        transformedMask.addPath(localMask)
        transformedMask.transform(transform)

        val stride = MeshColumns + 1
        val requiredSize = (MeshRows + 1) * stride * 2
        val positions =
            if (meshPositions.size == requiredSize) meshPositions else FloatArray(requiredSize)
        for (row in 0..MeshRows) {
            for (column in 0..MeshColumns) {
                val horizontalFraction = column.toFloat() / MeshColumns
                val verticalFraction = row.toFloat() / MeshRows
                val local =
                    androidx.compose.ui.geometry.Offset(
                        -EdgeOverdrawPixels +
                            (size.width + EdgeOverdrawPixels * 2f) * horizontalFraction,
                        -EdgeOverdrawPixels +
                            (size.height + EdgeOverdrawPixels * 2f) * verticalFraction,
                    )
                val rootPosition = coordinates.localToRoot(local)
                val index = (row * stride + column) * 2
                positions[index] = rootPosition.x
                positions[index + 1] = rootPosition.y
            }
        }
        meshPositions = positions
        return true
    }

    private fun render(gpu: COpaquePointer): Boolean {
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

    private fun updateTexture(gpu: COpaquePointer): Boolean {
        renderInvalidation.consume {
            // The dirty bit is cleared before entering native code. An update callback raised
            // during rendering therefore remains pending for the following compositor frame.
            render(gpu).also { rendered ->
                if (rendered) {
                    hasRendered = true
                    snapshotCacheState.onRendered()
                }
            }
        }
        return hasRendered
    }

    override fun draw(gpu: COpaquePointer): Boolean {
        val positions = meshPositions
        if (positions.isEmpty() || !updateTexture(gpu)) return false
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
        return true
    }

    override fun drawRasterized(
        gpu: COpaquePointer?,
        canvas: CairoCanvas,
        maskInLocal: Path,
    ): Boolean {
        val context = gpu ?: return false
        if (size.width <= 0 || size.height <= 0 || !updateTexture(context)) return false
        var image = cachedSnapshot
        if (image == null || image.width != size.width || image.height != size.height) {
            image?.close()
            image = CairoImage(size.width, size.height)
            cachedSnapshot = image
            snapshotCacheState.invalidateSnapshot()
        }
        if (snapshotCacheState.needsReadback) {
            val copied =
                kgpu_layer_read_pixels(
                    context,
                    handle,
                    image.surface.data.reinterpret(),
                    size.width,
                    size.height,
                    image.surface.stride,
                )
            if (copied <= 0) return false
            image.surface.markDirty()
            snapshotCacheState.onReadback()
            rasterReadbacks++
            rasterReadbackBytes += copied
        }
        canvas.save()
        try {
            canvas.clipPath(maskInLocal)
            canvas.drawSurface(image.surface, 0f, 0f, 1f, BlendMode.SrcOver)
        } finally {
            canvas.restore()
        }
        return true
    }

    override fun close() {
        registry.remove(this)
        cachedSnapshot?.close()
        cachedSnapshot = null
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
 * Cairo frame with antialiased coverage before the external texture is composited. Root-level GPU
 * views remain zero-readback. When an isolated Compose layer requires CPU composition, one retained
 * snapshot is refreshed after each successful native render and reused for geometry-only redraws.
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
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val lastLayoutBounds = remember { arrayOfNulls<IntRect>(1) }
    var renderRequest by remember(view) { mutableLongStateOf(1L) }
    val renderedRequest = remember(view) { longArrayOf(Long.MIN_VALUE) }
    val lastGpuEmission = remember(view) { arrayOf(GpuInteropEmission.Unsupported) }
    val boundsMask = remember { Path() }
    val callbackActive = remember(view) { booleanArrayOf(false) }
    val requestRender: () -> Unit = remember(view, gpuLayer, dispatchInvalidation) {
        {
            dispatchInvalidation {
                if (callbackActive[0]) {
                    if (gpuLayer != null) {
                        gpuLayer.requestRender()
                        if (lastGpuEmission[0] == GpuInteropEmission.Rasterized) {
                            renderRequest += 1L
                        }
                    } else {
                        renderRequest += 1L
                    }
                }
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
            coordinates[0] = null
            lastLayoutBounds[0] = null
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
                        return@onSizeChanged
                    }
                    val current = nativeFrame
                    if (current?.target?.width == size.width && current.target.height == size.height) {
                        return@onSizeChanged
                    }
                    current?.close()
                    nativeFrame = NativeViewFrame(size)
                    renderRequest += 1L
                }
                .onPlaced { coordinates[0] = it }
                .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { bounds ->
                    val nextBounds = bounds.boundsInRoot
                    if (lastLayoutBounds[0] == nextBounds) return@onLayoutRectChanged
                    lastLayoutBounds[0] = nextBounds
                    val placed = coordinates[0] ?: return@onLayoutRectChanged
                    dispatchInvalidation {
                        val layer = gpuLayer
                        if (callbackActive[0] && layer != null) {
                            val previousBounds = layer.transformedMask.getBounds()
                            if (layer.refreshGeometry(placed)) {
                                val fallbackMoved =
                                    gpuRegistry?.refreshFallbackMask(
                                        layer,
                                        layer.transformedMask,
                                        previousBounds,
                                    ) == true
                                if (fallbackMoved) renderRequest += 1L
                            }
                        }
                    }
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
            renderedRequest[0] = requestedRender
            coordinates[0]?.let { gpuLayer.updateGeometry(it, mask) }
            val emission =
                gpuRegistry?.emitBoundary(
                    gpuLayer,
                    drawContext.canvas,
                    gpuLayer.transformedMask,
                    mask,
                ) ?: GpuInteropEmission.Unsupported
            lastGpuEmission[0] = emission
            when (emission) {
                GpuInteropEmission.Rasterized -> Unit
                GpuInteropEmission.External ->
                    clipPath(mask) {
                        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    }
                GpuInteropEmission.Unsupported -> {
                    gpuRegistry?.setFallbackMask(gpuLayer, gpuLayer.transformedMask)
                    clipPath(mask) {
                        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    }
                }
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
