package androidx.compose.foundation.window

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.WindowScope

/**
 * A composable region that the native window manager may use to drag the window.
 * Interactive window controls should be placed outside this region.
 */
@Composable
fun WindowScope.WindowDraggableArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val key = remember { Any() }
    DisposableEffect(window, key) {
        onDispose { window.removeDraggableArea(key) }
    }
    Box(
        modifier = modifier.onGloballyPositioned {
            window.updateDraggableArea(key, it.boundsInRoot())
        },
        propagateMinConstraints = true,
        content = { content() },
    )
}
