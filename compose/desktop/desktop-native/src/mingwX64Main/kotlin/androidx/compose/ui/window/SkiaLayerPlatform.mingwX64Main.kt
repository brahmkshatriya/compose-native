@file:OptIn(org.jetbrains.skiko.InternalSkikoApi::class)

package androidx.compose.ui.window

import org.jetbrains.skia.Bitmap
import org.jetbrains.skiko.SkiaLayer

internal actual val SkiaLayer.nativeRendererDescription: String
    get() = rendererDescription

internal actual fun SkiaLayer.nativeDiagnosticsDescription(): String {
    val value = diagnostics
    return "transparentBuffer=${value.hasTransparentWindowBuffer} " +
        "(requested=${value.transparencyRequested}), " +
        "frameBuffers=${value.effectiveFrameBufferCount ?: "unknown"} " +
        "(requested=${value.frameBuffering})"
}

internal actual fun SkiaLayer.nativeSnapshot(width: Int, height: Int): Bitmap =
    snapshot(width, height)

internal actual fun SkiaLayer.nativeRender(force: Boolean): Boolean = render(force)

internal actual fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T =
    withOpenGlContext(block)
