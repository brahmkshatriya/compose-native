@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    org.jetbrains.skiko.InternalSkikoApi::class,
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SkiaRasterImage
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.toKString
import nativedesktop.kgl_layer_create
import nativedesktop.kgl_layer_destroy
import nativedesktop.kgl_layer_finish
import nativedesktop.kgl_layer_framebuffer
import nativedesktop.kgl_layer_prepare
import nativedesktop.kgl_layer_texture
import nativedesktop.kgl_renderer
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer

internal val LocalGpuInteropRegistry = staticCompositionLocalOf<GpuInteropRegistry?> { null }

internal val LocalNativeViewInvalidationDispatcher =
    staticCompositionLocalOf<((() -> Unit) -> Unit)> { { it() } }

internal class GpuInteropRegistry(private val layer: SkiaLayer) {
    val rendererDescription: String =
        layer.withOpenGlContext { kgl_renderer()?.toKString() ?: layer.rendererDescription }

    fun create(view: InteropView): GpuNativeViewLayer = GpuNativeViewLayer(this, view)

    internal val isAvailable: Boolean
        get() = layer.renderApi == GraphicsApi.OPENGL

    internal fun withExternalGl(block: () -> Boolean): Boolean {
        if (!isAvailable) return false
        return try {
            layer.withOpenGlContext(block)
        } catch (failure: Throwable) {
            if (isAvailable) throw failure
            false
        }
    }

    internal fun drawTexture(
        textureId: Int,
        width: Int,
        height: Int,
        canvas: androidx.compose.ui.graphics.Canvas,
    ): Boolean {
        if (!isAvailable) return false
        return try {
            layer.drawOpenGlTexture(textureId, width, height, canvas.skiaCanvas)
            true
        } catch (failure: Throwable) {
            if (isAvailable) throw failure
            false
        }
    }

    internal fun destroyLayer(handle: COpaquePointer) {
        if (!isAvailable) {
            kgl_layer_destroy(handle)
            return
        }
        try {
            layer.withOpenGlContext { kgl_layer_destroy(handle) }
        } catch (failure: Throwable) {
            if (isAvailable) throw failure
            kgl_layer_destroy(handle)
        }
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
    private val view: InteropView,
) : AutoCloseable {
    private var handle: COpaquePointer? = checkNotNull(kgl_layer_create())
    private var size = IntSize.Zero
    private var density = 1f
    private val renderInvalidation = GpuNativeViewRenderInvalidation()
    private var hasRendered = false

    fun resize(value: IntSize, density: Float) {
        if (size != value || this.density != density) {
            size = value
            this.density = density
            renderInvalidation.request()
        }
    }

    fun updateDensity(value: Float): Boolean {
        if (density == value) return false
        density = value
        renderInvalidation.request()
        return true
    }

    fun requestRender() {
        renderInvalidation.request()
    }

    private fun render(): Boolean {
        val layer = handle ?: return false
        if (!registry.isAvailable) return false
        if (size.width <= 0 || size.height <= 0) return false
        return registry.withExternalGl {
            if (kgl_layer_prepare(layer, size.width, size.height) == 0) {
                return@withExternalGl false
            }
            try {
                view.renderOpenGl(
                    OpenGlInteropRenderTarget(
                        framebuffer = kgl_layer_framebuffer(layer).toInt(),
                        width = size.width,
                        height = size.height,
                        internalFormat = 0x8058, // GL_RGBA8
                        renderer = registry.rendererDescription,
                        density = density,
                    )
                )
            } finally {
                kgl_layer_finish(layer)
            }
        }
    }

    private fun updateTexture(): Boolean {
        if (renderInvalidation.consume(::render)) hasRendered = true
        return hasRendered
    }

    fun draw(canvas: androidx.compose.ui.graphics.Canvas): Boolean {
        val layer = handle ?: return false
        if (!registry.isAvailable) return false
        if (!updateTexture()) return false
        val texture = kgl_layer_texture(layer).toInt()
        if (texture == 0) return false
        return registry.drawTexture(texture, size.width, size.height, canvas)
    }

    override fun close() {
        val layer = handle ?: return
        handle = null
        registry.destroyLayer(layer)
    }
}

private class NativeViewFrame(size: IntSize) : AutoCloseable {
    val image = SkiaRasterImage(size.width, size.height)
    val target =
        InteropRenderTarget(
            pixels = image.pixels,
            width = size.width,
            height = size.height,
            stride = image.stride,
        )

    fun notifyPixelsChanged() = image.notifyPixelsChanged()

    override fun close() = image.close()
}

/**
 * Places a native framebuffer renderer inside Compose.
 *
 * CPU renderers receive a mutable Skia raster buffer. OpenGL renderers receive an FBO from the
 * window's current SDL context; its color texture is borrowed by Skia and drawn without readback.
 * Compose transforms, clipping, content drawn above the view, and pointer input remain native to
 * the regular scene graph.
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
                requireNotNull(gpuRegistry) { "OpenGL interop requires the ui-sdl3 window host" }
                    .create(view)
            } else {
                null
            }
        }
    var nativeFrame by remember { mutableStateOf<NativeViewFrame?>(null) }
    var renderRequest by remember(view) { mutableLongStateOf(1L) }
    val renderedRequest = remember(view) { longArrayOf(Long.MIN_VALUE) }
    val boundsMask = remember { Path() }
    val callbackActive = remember(view) { booleanArrayOf(false) }
    val requestRender: () -> Unit =
        remember(view, gpuLayer, dispatchInvalidation) {
            {
                dispatchInvalidation {
                    if (callbackActive[0]) {
                        gpuLayer?.requestRender()
                        renderRequest += 1L
                    }
                }
            }
        }

    SideEffect {
        update(view)
        if (gpuLayer?.updateDensity(density) == true) requestRender()
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
                        Modifier.focusRequester(focusRequester)
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
                                                PointerEventType.Release ->
                                                    InteropPointerEventType.Button
                                                PointerEventType.Scroll ->
                                                    InteropPointerEventType.Scroll
                                                else -> InteropPointerEventType.Move
                                            }
                                        if (event.type == PointerEventType.Press) {
                                            focusRequester.requestFocus()
                                        }
                                        var modifiers = 0
                                        if (event.keyboardModifiers.isCtrlPressed)
                                            modifiers = modifiers or 1
                                        if (event.keyboardModifiers.isShiftPressed)
                                            modifiers = modifiers or 2
                                        if (event.keyboardModifiers.isAltPressed)
                                            modifiers = modifiers or 4
                                        if (event.keyboardModifiers.isMetaPressed)
                                            modifiers = modifiers or 8
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
                    if (
                        current?.target?.width == size.width && current.target.height == size.height
                    ) {
                        return@onSizeChanged
                    }
                    current?.close()
                    nativeFrame = NativeViewFrame(size)
                    renderRequest += 1L
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
            clipPath(mask) { drawIntoCanvas { gpuLayer.draw(it) } }
            return@Canvas
        }
        val frame = nativeFrame ?: return@Canvas
        if (renderedRequest[0] != requestedRender) {
            if (view.render(frame.target)) frame.notifyPixelsChanged()
            renderedRequest[0] = requestedRender
        }
        if (clipPath == null) {
            drawImage(frame.image.imageBitmap)
        } else {
            clipPath(mask) { drawImage(frame.image.imageBitmap) }
        }
    }
}
