@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.webview

import kotlin.math.sin
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import platform.opengl32.GL_COLOR_BUFFER_BIT
import platform.opengl32.GL_FRAMEBUFFER
import platform.opengl32.glClear
import platform.opengl32.glClearColor
import platform.opengl32.glViewport
import platform.windows.GetModuleHandleA
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryA

/**
 * Windows placeholders for the catalogue's WPE WebKit integration.
 *
 * The catalogue UI is shared verbatim with the Linux demo. WPE is Linux-only, so these functions
 * leave its native surface unavailable until a WebView2-backed implementation is supplied.
 */
fun app_webview_create(uri: String): COpaquePointer? = null

fun app_webview_destroy(view: COpaquePointer?) = Unit

fun app_webview_error(view: COpaquePointer?): CPointer<ByteVar>? = null

fun app_webview_render(
    view: COpaquePointer?,
    framebuffer: Int,
    width: Int,
    height: Int,
    deviceScale: Float,
): Int = 0

fun app_webview_load_uri(view: COpaquePointer?, uri: String) = Unit

fun app_webview_go_back(view: COpaquePointer?) = Unit

fun app_webview_go_forward(view: COpaquePointer?) = Unit

fun app_webview_reload(view: COpaquePointer?) = Unit

fun app_webview_can_go_back(view: COpaquePointer?): Int = 0

fun app_webview_can_go_forward(view: COpaquePointer?): Int = 0

fun app_webview_media_set_playing(view: COpaquePointer?, playing: Int) = Unit

fun app_webview_media_seek(view: COpaquePointer?, seconds: Double) = Unit

fun app_webview_media_set_volume(view: COpaquePointer?, volume: Double) = Unit

fun app_webview_set_focused(view: COpaquePointer?, focused: Int) = Unit

fun app_webview_pointer_motion(view: COpaquePointer?, x: Int, y: Int, time: UInt, modifiers: UInt) =
    Unit

fun app_webview_pointer_button(
    view: COpaquePointer?,
    x: Int,
    y: Int,
    time: UInt,
    button: UInt,
    pressed: Int,
    modifiers: UInt,
) = Unit

fun app_webview_scroll(
    view: COpaquePointer?,
    x: Int,
    y: Int,
    time: UInt,
    deltaX: Double,
    deltaY: Double,
    modifiers: UInt,
) = Unit

fun app_webview_key(
    view: COpaquePointer?,
    composeKey: Long,
    codePoint: UInt,
    pressed: Int,
    modifiers: UInt,
) = Unit

private val bindFramebuffer: CPointer<CFunction<(target: UInt, framebuffer: UInt) -> Unit>>? by
    lazy(LazyThreadSafetyMode.NONE) {
        val module = GetModuleHandleA("opengl32.dll") ?: LoadLibraryA("opengl32.dll")
        val wglGetProcAddress =
            GetProcAddress(module, "wglGetProcAddress")
                ?.reinterpret<CFunction<(name: CPointer<ByteVar>?) -> COpaquePointer?>>()
        if (wglGetProcAddress == null) {
            null
        } else {
            memScoped {
                wglGetProcAddress("glBindFramebuffer".cstr.ptr)
                    ?.reinterpret<CFunction<(target: UInt, framebuffer: UInt) -> Unit>>()
            }
        }
    }

fun app_demo_render_gl(framebuffer: Int, width: Int, height: Int, phase: Float) {
    if (width <= 0 || height <= 0) return
    val bind = bindFramebuffer ?: return
    bind(GL_FRAMEBUFFER.toUInt(), framebuffer.toUInt())
    glViewport(0, 0, width, height)
    val wave = ((sin(phase.toDouble()) + 1.0) * 0.5).toFloat()
    glClearColor(0.08f + wave * 0.18f, 0.12f, 0.30f + wave * 0.35f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT.toUInt())
}
