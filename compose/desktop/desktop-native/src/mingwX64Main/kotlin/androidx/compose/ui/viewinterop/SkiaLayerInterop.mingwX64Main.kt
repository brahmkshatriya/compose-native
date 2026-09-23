@file:OptIn(org.jetbrains.skiko.InternalSkikoApi::class)

package androidx.compose.ui.viewinterop

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer

internal actual val SkiaLayer.nativeRendererDescription: String
    get() = rendererDescription

internal actual fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T =
    withOpenGlContext(block)

internal actual fun SkiaLayer.drawNativeOpenGlTexture(
    textureId: Int,
    width: Int,
    height: Int,
    canvas: Canvas,
) {
    drawOpenGlTexture(textureId, width, height, canvas)
}
