@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.PlatformContext
import cnames.structs.SDL_Window
import kotlinx.cinterop.CPointer
import org.jetbrains.skiko.SkiaLayer

internal expect val NativeDesktopSelfTestContent: @Composable ApplicationScope.() -> Unit

internal expect fun nativeGetEnvironmentVariable(name: String): String?

internal expect fun nativeSleepMicroseconds(value: UInt)

internal expect fun configureNativeSdlEnvironment()

internal expect fun configureNativeGraphics(layer: SkiaLayer)

internal expect fun nativeGraphicsWindowFlags(layer: SkiaLayer): ULong

internal expect fun attachNativeSkiaLayer(
    layer: SkiaLayer,
    window: CPointer<SDL_Window>,
    transparency: Boolean,
    queryContentScale: () -> Float,
    queryFullscreen: () -> Boolean,
    updateFullscreen: (Boolean) -> Unit,
    onRenderRequested: () -> Unit,
)

internal expect object NativeDesktopIntegration {
    fun install()

    fun close()

    fun pollEvents()

    fun pollAccessibility(): Boolean

    fun updateSystemTheme(dark: Boolean?)
}

internal interface NativeAccessibility : PlatformContext.SemanticsOwnerListener {
    fun open(title: String)

    fun updateWindow(
        title: String,
        visible: Boolean,
        focused: Boolean,
        screenX: Int,
        screenY: Int,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
    )

    fun close()

    fun refreshAfterLayout()

    fun onAccessibilityBusConnected()
}

internal expect fun createNativeAccessibility(
    dispatchAction: (() -> Unit) -> Unit,
): NativeAccessibility
