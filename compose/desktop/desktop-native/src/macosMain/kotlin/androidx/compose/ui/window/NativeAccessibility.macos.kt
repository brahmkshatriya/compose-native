package androidx.compose.ui.window

import androidx.compose.ui.semantics.SemanticsOwner

internal actual fun createNativeAccessibility(
    dispatchAction: (() -> Unit) -> Unit,
): NativeAccessibility = NoopMacosNativeAccessibility

private object NoopMacosNativeAccessibility : NativeAccessibility {
    override fun open(title: String) = Unit

    override fun updateWindow(
        title: String,
        visible: Boolean,
        focused: Boolean,
        screenX: Int,
        screenY: Int,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
    ) = Unit

    override fun close() = Unit
    override fun refreshAfterLayout() = Unit
    override fun onAccessibilityBusConnected() = Unit
    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) = Unit
    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) = Unit
    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
}
