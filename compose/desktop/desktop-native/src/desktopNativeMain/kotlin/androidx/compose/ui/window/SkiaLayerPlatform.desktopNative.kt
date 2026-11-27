package androidx.compose.ui.window

import org.jetbrains.skia.Bitmap
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate

internal expect fun createNativeSkiaLayer(): SkiaLayer

internal expect val SkiaLayer.nativeRenderApi: GraphicsApi

internal expect fun SkiaLayer.setNativeRenderDelegate(delegate: SkikoRenderDelegate)

internal expect val SkiaLayer.nativeRendererDescription: String

internal expect fun SkiaLayer.nativeDiagnosticsDescription(): String

internal expect fun SkiaLayer.nativeSnapshot(width: Int, height: Int): Bitmap

internal expect fun SkiaLayer.nativeRender(force: Boolean): Boolean

internal expect fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T
