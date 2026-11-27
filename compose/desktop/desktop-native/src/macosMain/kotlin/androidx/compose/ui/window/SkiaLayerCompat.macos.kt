@file:OptIn(org.jetbrains.skiko.InternalSkikoApi::class)

package androidx.compose.ui.window

import org.jetbrains.skia.Bitmap
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate

internal actual fun createNativeSkiaLayer(): SkiaLayer = SkiaLayer()

internal actual val SkiaLayer.nativeRenderApi: GraphicsApi
    get() = renderApi

internal actual fun SkiaLayer.setNativeRenderDelegate(delegate: SkikoRenderDelegate) {
    renderDelegate = delegate
}

internal actual val SkiaLayer.nativeRendererDescription: String
    get() = rendererDescription

internal actual fun SkiaLayer.nativeDiagnosticsDescription(): String =
    "renderer=$rendererDescription"

internal actual fun SkiaLayer.nativeSnapshot(width: Int, height: Int): Bitmap =
    snapshot(width.coerceAtLeast(1), height.coerceAtLeast(1))

internal actual fun SkiaLayer.nativeRender(force: Boolean): Boolean = render(force)

internal actual fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T =
    withOpenGlContext(block)
