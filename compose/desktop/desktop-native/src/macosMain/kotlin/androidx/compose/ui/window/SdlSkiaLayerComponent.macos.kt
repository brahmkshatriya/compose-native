@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    org.jetbrains.skiko.InternalSkikoApi::class,
)

package androidx.compose.ui.window

import cnames.structs.SDL_GLContextState
import cnames.structs.SDL_Window
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import nativedesktop.kgl_renderer
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.MacosSkiaLayerComponent
import platform.QuartzCore.CAMetalLayer
import sdl3.SDL_GL_CreateContext
import sdl3.SDL_GL_DestroyContext
import sdl3.SDL_GL_MakeCurrent
import sdl3.SDL_GL_SetSwapInterval
import sdl3.SDL_GL_SwapWindow
import sdl3.SDL_GetError
import sdl3.SDL_GetWindowSizeInPixels
import sdl3.SDL_Metal_CreateView
import sdl3.SDL_Metal_DestroyView
import sdl3.SDL_Metal_GetLayer

internal class SdlSkiaLayerComponent(
    private val window: CPointer<SDL_Window>,
    private val queryContentScale: () -> Float,
    private val queryFullscreen: () -> Boolean,
    private val updateFullscreen: (Boolean) -> Unit,
    private val onRenderRequested: () -> Unit,
) : MacosSkiaLayerComponent {
    private var metalView: COpaquePointer? = null

    override val windowHandle: Any
        get() = window

    override val drawableWidth: Int
        get() = drawableSize().first

    override val drawableHeight: Int
        get() = drawableSize().second

    override val contentScale: Float
        get() = queryContentScale()

    override val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    override var fullscreen: Boolean
        get() = queryFullscreen()
        set(value) = updateFullscreen(value)

    override fun createOpenGlContext(): NativePointer =
        (SDL_GL_CreateContext(window)
                ?: error("Could not create the SDL OpenGL context: ${SDL_GetError()?.toKString()}"))
            .rawValue

    override fun makeOpenGlContextCurrent(context: NativePointer) {
        val pointer = checkNotNull(interpretCPointer<SDL_GLContextState>(context))
        check(SDL_GL_MakeCurrent(window, pointer)) {
            "Could not make the SDL OpenGL context current: ${SDL_GetError()?.toKString()}"
        }
    }

    override fun setOpenGlSwapInterval(interval: Int): Boolean = SDL_GL_SetSwapInterval(interval)

    override fun swapOpenGlBuffers() {
        SDL_GL_SwapWindow(window)
    }

    override fun deleteOpenGlContext(context: NativePointer) {
        interpretCPointer<SDL_GLContextState>(context)?.let { SDL_GL_DestroyContext(it) }
    }

    override fun openGlRendererName(): String? = kgl_renderer()?.toKString()

    override fun createMetalLayer(): CAMetalLayer {
        check(metalView == null) { "The SDL Metal view is already created" }
        val view =
            SDL_Metal_CreateView(window)
                ?: error("Could not create the SDL Metal view: ${SDL_GetError()?.toKString()}")
        metalView = view
        val layerPointer = SDL_Metal_GetLayer(view)
        if (layerPointer == null) {
            SDL_Metal_DestroyView(view)
            metalView = null
            error("SDL Metal view has no CAMetalLayer: ${SDL_GetError()?.toKString()}")
        }
        return try {
            interpretObjCPointer<CAMetalLayer>(layerPointer.rawValue)
        } catch (failure: Throwable) {
            SDL_Metal_DestroyView(view)
            metalView = null
            throw failure
        }
    }

    override fun deleteMetalLayer() {
        metalView?.let { SDL_Metal_DestroyView(it) }
        metalView = null
    }

    override fun requestRender() = onRenderRequested()

    private fun drawableSize(): Pair<Int, Int> = memScoped {
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        SDL_GetWindowSizeInPixels(window, width.ptr, height.ptr)
        width.value.coerceAtLeast(0) to height.value.coerceAtLeast(0)
    }
}
