@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.viewinterop

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import cnames.structs.SDL_Window
import kotlinx.cinterop.CPointer

/**
 * Window-scoped host for real platform child views.
 *
 * This is intentionally separate from [NativeView], whose contract is framebuffer rendering.
 * Platform views participate in the host OS view hierarchy and therefore keep native focus,
 * accessibility, input, IME, drag-and-drop, and platform-widget behavior.
 */
internal interface NativePlatformViewHost : AutoCloseable {
    /** Modifier installed at the root of the Compose window for draw-order traversal. */
    val rootModifier: Modifier

    /** Called after the Skia renderer has attached its platform surface/view. */
    fun onRenderLayerAttached() = Unit

    /** Installs the Compose-scene hit test used to arbitrate input with real native child views. */
    fun setInteropHitTest(hitTest: ((Offset) -> Any?)?) = Unit
}

internal val LocalNativePlatformViewHost =
    staticCompositionLocalOf<NativePlatformViewHost?> { null }

internal expect fun createNativePlatformViewHost(
    window: CPointer<SDL_Window>,
    dispatch: (action: () -> Unit) -> Unit,
): NativePlatformViewHost?
