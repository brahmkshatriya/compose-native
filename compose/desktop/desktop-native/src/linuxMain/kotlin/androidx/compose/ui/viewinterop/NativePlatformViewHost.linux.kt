@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.viewinterop

import cnames.structs.SDL_Window
import kotlinx.cinterop.CPointer

internal actual fun createNativePlatformViewHost(
    window: CPointer<SDL_Window>,
    dispatch: (action: () -> Unit) -> Unit,
): NativePlatformViewHost? = null
