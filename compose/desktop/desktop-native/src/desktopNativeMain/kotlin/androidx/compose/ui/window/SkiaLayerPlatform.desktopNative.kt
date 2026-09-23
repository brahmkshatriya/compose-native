package androidx.compose.ui.window

import org.jetbrains.skia.Bitmap
import org.jetbrains.skiko.SkiaLayer

internal expect val SkiaLayer.nativeRendererDescription: String

internal expect fun SkiaLayer.nativeDiagnosticsDescription(): String

internal expect fun SkiaLayer.nativeSnapshot(width: Int, height: Int): Bitmap

internal expect fun SkiaLayer.nativeRender(force: Boolean): Boolean

internal expect fun <T> SkiaLayer.withNativeOpenGlContext(block: () -> T): T
