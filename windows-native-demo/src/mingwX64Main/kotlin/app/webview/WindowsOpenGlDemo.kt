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
