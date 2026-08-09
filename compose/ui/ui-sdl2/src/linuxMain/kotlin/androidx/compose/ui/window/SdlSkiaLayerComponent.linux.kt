@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    org.jetbrains.skiko.InternalSkikoApi::class,
)

package androidx.compose.ui.window

import cnames.structs.SDL_GLContextState
import cnames.structs.SDL_Renderer
import cnames.structs.SDL_Window
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeNullPtr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import linuxdesktop.kgl_context_is_lost
import linuxdesktop.kgl_renderer
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.FrameBuffering
import org.jetbrains.skiko.GpuPriority
import org.jetbrains.skiko.LinuxSkiaLayerComponent
import org.jetbrains.skiko.SkikoProperties
import platform.posix.getenv
import platform.posix.setenv
import sdl2.SDL_BLENDMODE_NONE
import sdl2.SDL_CreateRenderer
import sdl2.SDL_CreateTexture
import sdl2.SDL_DestroyRenderer
import sdl2.SDL_DestroyTexture
import sdl2.SDL_GLAttr
import sdl2.SDL_GL_CreateContext
import sdl2.SDL_GL_DestroyContext
import sdl2.SDL_GL_GetAttribute
import sdl2.SDL_GL_GetProcAddress
import sdl2.SDL_GL_MakeCurrent
import sdl2.SDL_GL_SetAttribute
import sdl2.SDL_GL_SetSwapInterval
import sdl2.SDL_GL_SwapWindow
import sdl2.SDL_GetCurrentDisplayMode
import sdl2.SDL_GetCurrentRenderOutputSize
import sdl2.SDL_GetDisplayForWindow
import sdl2.SDL_GetError
import sdl2.SDL_GetWindowFlags
import sdl2.SDL_GetWindowSizeInPixels
import sdl2.SDL_PIXELFORMAT_ARGB8888
import sdl2.SDL_RenderClear
import sdl2.SDL_RenderPresent
import sdl2.SDL_RenderTexture
import sdl2.SDL_SOFTWARE_RENDERER
import sdl2.SDL_SetTextureBlendMode
import sdl2.SDL_Texture
import sdl2.SDL_TextureAccess
import sdl2.SDL_UpdateTexture
import sdl2.SDL_WINDOW_TRANSPARENT

private fun resolveSdlOpenGlFunction(
    @Suppress("UNUSED_PARAMETER") context: COpaquePointer?,
    name: CPointer<ByteVar>?,
): COpaquePointer? = SDL_GL_GetProcAddress(name?.toKString())

private val SdlOpenGlResolver = staticCFunction(::resolveSdlOpenGlFunction)

internal class SdlSkiaLayerComponent(
    private val window: CPointer<SDL_Window>,
    override val transparency: Boolean,
    private val queryContentScale: () -> Float,
    private val queryFullscreen: () -> Boolean,
    private val updateFullscreen: (Boolean) -> Unit,
    private val onRenderRequested: () -> Unit,
) : LinuxSkiaLayerComponent {
    private var softwareRenderer: CPointer<SDL_Renderer>? = null
    private var softwareTexture: CPointer<SDL_Texture>? = null
    private var softwareTextureWidth = 0
    private var softwareTextureHeight = 0

    override val windowHandle: Any
        get() = window

    override val drawableWidth: Int
        get() = drawableSize().first

    override val drawableHeight: Int
        get() = drawableSize().second

    override val contentScale: Float
        get() = queryContentScale()

    override val pixelGeometry: PixelGeometry
        get() = SkikoProperties.pixelGeometry

    override val transparencySupported: Boolean
        get() = transparency && SDL_GetWindowFlags(window) and SDL_WINDOW_TRANSPARENT != 0uL

    override val effectiveFrameBufferCount: Int?
        get() {
            if (softwareRenderer != null) return null
            return memScoped {
                val doubleBuffered = alloc<IntVar>()
                if (!SDL_GL_GetAttribute(SDL_GLAttr.SDL_GL_DOUBLEBUFFER, doubleBuffered.ptr)) {
                    null
                } else if (doubleBuffered.value != 0) {
                    2
                } else {
                    1
                }
            }
        }

    override val displayRefreshRate: Float
        get() {
            val display = SDL_GetDisplayForWindow(window)
            if (display == 0u) return 60f
            val mode = SDL_GetCurrentDisplayMode(display) ?: return 60f
            return mode.pointed.refresh_rate.takeIf { it > 0f } ?: 60f
        }

    override var fullscreen: Boolean
        get() = queryFullscreen()
        set(value) = updateFullscreen(value)

    override val openGlResolverContext: NativePointer
        get() = nativeNullPtr

    override val openGlResolver: NativePointer
        get() = SdlOpenGlResolver.rawValue

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

    override fun isOpenGlContextLost(context: NativePointer): Boolean {
        val pointer = interpretCPointer<SDL_GLContextState>(context) ?: return true
        if (!SDL_GL_MakeCurrent(window, pointer)) return true
        return kgl_context_is_lost() != 0
    }

    override fun configureGpuPriority(priority: GpuPriority) {
        if (priority == GpuPriority.Auto || getenv("DRI_PRIME") != null) return
        setenv("DRI_PRIME", if (priority == GpuPriority.Discrete) "1" else "0", 0)
    }

    override fun configureFrameBuffering(frameBuffering: FrameBuffering) {
        // SDL exposes single/double buffering only. Triple buffering remains driver/compositor
        // controlled, as it is for the JVM Linux OpenGL redrawer.
        if (frameBuffering != FrameBuffering.DEFAULT) {
            SDL_GL_SetAttribute(SDL_GLAttr.SDL_GL_DOUBLEBUFFER, 1)
        }
    }

    override fun beginSoftwareRendering() {
        if (softwareRenderer != null) return
        softwareRenderer =
            SDL_CreateRenderer(window, SDL_SOFTWARE_RENDERER)
                ?: error(
                    "Could not create the SDL software renderer: ${SDL_GetError()?.toKString()}"
                )
    }

    override fun presentSoftwareFrame(
        pixels: NativePointer,
        width: Int,
        height: Int,
        rowBytes: Int,
    ) {
        val renderer = checkNotNull(softwareRenderer)
        if (
            softwareTexture == null ||
                softwareTextureWidth != width ||
                softwareTextureHeight != height
        ) {
            softwareTexture?.let { SDL_DestroyTexture(it) }
            softwareTexture =
                SDL_CreateTexture(
                    renderer,
                    SDL_PIXELFORMAT_ARGB8888,
                    SDL_TextureAccess.SDL_TEXTUREACCESS_STREAMING,
                    width,
                    height,
                )
                    ?: error(
                        "Could not create the SDL software texture: ${SDL_GetError()?.toKString()}"
                    )
            check(SDL_SetTextureBlendMode(softwareTexture, SDL_BLENDMODE_NONE)) {
                "Could not configure the SDL software texture: ${SDL_GetError()?.toKString()}"
            }
            softwareTextureWidth = width
            softwareTextureHeight = height
        }
        check(
            SDL_UpdateTexture(softwareTexture, null, interpretCPointer<ByteVar>(pixels), rowBytes)
        ) {
            "Could not upload the Skia software framebuffer: ${SDL_GetError()?.toKString()}"
        }
        check(
            SDL_RenderClear(renderer) && SDL_RenderTexture(renderer, softwareTexture, null, null)
        ) {
            "Could not present the Skia software framebuffer: ${SDL_GetError()?.toKString()}"
        }
        SDL_RenderPresent(renderer)
    }

    override fun endSoftwareRendering() {
        softwareTexture?.let { SDL_DestroyTexture(it) }
        softwareTexture = null
        softwareTextureWidth = 0
        softwareTextureHeight = 0
        softwareRenderer?.let { SDL_DestroyRenderer(it) }
        softwareRenderer = null
    }

    override fun requestRender() = onRenderRequested()

    private fun drawableSize(): Pair<Int, Int> = memScoped {
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        val renderer = softwareRenderer
        if (renderer == null || !SDL_GetCurrentRenderOutputSize(renderer, width.ptr, height.ptr)) {
            SDL_GetWindowSizeInPixels(window, width.ptr, height.ptr)
        }
        width.value.coerceAtLeast(0) to height.value.coerceAtLeast(0)
    }
}
