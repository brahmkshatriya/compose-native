package androidx.compose.ui.viewinterop

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer

internal expect val SkiaLayer.nativeRendererDescription: String

internal expect fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T

internal expect fun SkiaLayer.drawNativeOpenGlTexture(
    textureId: Int,
    width: Int,
    height: Int,
    canvas: Canvas,
)
