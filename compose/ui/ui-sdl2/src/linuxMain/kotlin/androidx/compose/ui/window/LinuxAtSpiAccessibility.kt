@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)
@file:Suppress("DEPRECATION")

package androidx.compose.ui.window

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import linuxdesktop.kld_atspi_is_connected
import linuxdesktop.kld_atspi_window_add_action
import linuxdesktop.kld_atspi_window_add_node
import linuxdesktop.kld_atspi_window_begin_update
import linuxdesktop.kld_atspi_window_commit_update
import linuxdesktop.kld_atspi_window_create
import linuxdesktop.kld_atspi_window_destroy
import linuxdesktop.kld_atspi_window_set_collection
import linuxdesktop.kld_atspi_window_set_editable_actions
import linuxdesktop.kld_atspi_window_set_value
import platform.posix.getenv

private object AtSpiRole {
    const val CheckBox = 7u
    const val ComboBox = 11u
    const val Dialog = 16u
    const val Image = 27u
    const val Label = 29u
    const val List = 31u
    const val ListItem = 32u
    const val PageTab = 37u
    const val Panel = 39u
    const val PasswordText = 40u
    const val ProgressBar = 42u
    const val Button = 43u
    const val RadioButton = 44u
    const val ScrollPane = 49u
    const val Slider = 51u
    const val Table = 55u
    const val TableCell = 56u
    const val Text = 61u
    const val Unknown = 67u
    const val Entry = 79u
    const val Heading = 83u
    const val Grouping = 99u
    const val Switch = 130u
}

private object AtSpiState {
    const val Active = 1
    const val Checked = 4
    const val Collapsed = 5
    const val Editable = 7
    const val Enabled = 8
    const val Expandable = 9
    const val Expanded = 10
    const val Focusable = 11
    const val Focused = 12
    const val Horizontal = 14
    const val Modal = 16
    const val Selectable = 22
    const val Selected = 23
    const val Sensitive = 24
    const val Showing = 25
    const val Vertical = 29
    const val Visible = 30
    const val Indeterminate = 32
    const val SelectableText = 38
    const val Checkable = 41
    const val HasPopup = 42
    const val ReadOnly = 43
}

private object AtSpiActionId {
    const val Click = 1
    const val LongClick = 2
    const val Expand = 3
    const val Collapse = 4
    const val Dismiss = 5
    const val Focus = 6
    const val SetProgress = 7
    const val PageUp = 8
    const val PageDown = 9
    const val PageLeft = 10
    const val PageRight = 11
    const val SetText = 12
    const val InsertText = 13
    const val SetSelection = 14
    const val CopyText = 15
    const val CutText = 16
    const val PasteText = 17
    const val CustomStart = 1000
}

private data class AtSpiWindowState(
    val title: String = "",
    val visible: Boolean = false,
    val focused: Boolean = false,
    val screenX: Int = 0,
    val screenY: Int = 0,
    val width: Int = 1,
    val height: Int = 1,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

private typealias AtSpiInvocation = (Double, String?, Int, Int) -> Boolean

internal class LinuxAtSpiAccessibility(
    private val dispatchAction: (() -> Unit) -> Unit,
) : PlatformContext.SemanticsOwnerListener {
    private val debug = getenv("KTNATIVE_ATSPI_DEBUG") != null
    private val owners = mutableSetOf<SemanticsOwner>()
    private val actions = mutableMapOf<Long, AtSpiInvocation>()
    private var stableReference: StableRef<LinuxAtSpiAccessibility>? = null
    private var nativeWindow: COpaquePointer? = null
    private var windowState = AtSpiWindowState()
    private var refreshAfterLayout = false
    private var rebuildPending = false

    fun open(title: String) {
        if (nativeWindow != null) return
        val reference = StableRef.create(this)
        val handle =
            kld_atspi_window_create(
                title,
                reference.asCPointer(),
                staticCFunction(::performAtSpiAction),
            )
        if (handle == null) {
            reference.dispose()
            return
        }
        stableReference = reference
        nativeWindow = handle
        windowState = windowState.copy(title = title)
        rebuild()
    }

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
    ) {
        val next =
            AtSpiWindowState(
                title = title,
                visible = visible,
                focused = focused,
                screenX = screenX,
                screenY = screenY,
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                scaleX = scaleX.takeIf { it.isFinite() && it > 0f } ?: 1f,
                scaleY = scaleY.takeIf { it.isFinite() && it > 0f } ?: 1f,
            )
        if (next == windowState) return
        windowState = next
        rebuildPending = true
    }

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (owners.add(semanticsOwner)) rebuildPending = true
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        if (owners.remove(semanticsOwner)) rebuildPending = true
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        if (semanticsOwner in owners) rebuildPending = true
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        if (semanticsOwner in owners) rebuildPending = true
    }

    fun close() {
        val handle = nativeWindow
        nativeWindow = null
        actions.clear()
        owners.clear()
        refreshAfterLayout = false
        rebuildPending = false
        if (handle != null) kld_atspi_window_destroy(handle)
        stableReference?.dispose()
        stableReference = null
    }

    fun refreshAfterLayout() {
        if (!refreshAfterLayout && !rebuildPending) return
        refreshAfterLayout = false
        rebuild()
    }

    fun onAccessibilityBusConnected() {
        if (rebuildPending) rebuild()
    }

    private fun rebuild() {
        val handle = nativeWindow ?: return
        if (kld_atspi_is_connected() == 0) {
            rebuildPending = true
            return
        }
        rebuildPending = false
        val state = windowState
        kld_atspi_window_begin_update(
            handle,
            state.title,
            if (state.visible) 1 else 0,
            if (state.focused) 1 else 0,
            state.screenX,
            state.screenY,
            state.width,
            state.height,
        )

        val nodes =
            owners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .filterNot { it.config.isHiddenFromAccessibility() }
                .distinctBy(SemanticsNode::id)
        val includedIds = nodes.mapTo(mutableSetOf(), SemanticsNode::id)
        actions.clear()

        nodes.forEach { node ->
            val config = node.config
            val parentId = node.nearestAccessibleParentId(includedIds)
            val bounds = node.boundsInWindow.toLogicalBounds(state.scaleX, state.scaleY)
            val text = config.accessibleText()
            val name = config.accessibleName(text)
            val description = config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            val accessibleId =
                config.getOrNull(SemanticsProperties.TestTag)?.takeIf(String::isNotBlank)
                    ?: "compose-${node.id}"
            val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
            val role = config.atSpiRole(text, node.isInTabularCollection())
            val stateBits = config.atSpiStates(state.visible, bounds, text)

            kld_atspi_window_add_node(
                handle,
                node.id,
                parentId,
                role,
                name,
                description,
                accessibleId,
                text,
                stateBits,
                bounds.left,
                bounds.top,
                bounds.width,
                bounds.height,
                selection?.start ?: 0,
                selection?.end ?: 0,
            )

            addCollection(handle, node, config)
            addActions(handle, node, config)
            addEditableText(handle, node, config)
            addValue(handle, node, config)
        }
        kld_atspi_window_commit_update(handle)
    }

    private fun currentAction(
        nodeId: Int,
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<
            androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
        >,
    ): (() -> Boolean)? = currentConfig(nodeId)?.getOrNull(key)?.action

    private fun currentConfig(nodeId: Int): SemanticsConfiguration? =
        owners
            .asSequence()
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
            .firstOrNull { it.id == nodeId }
            ?.config

    private fun addActions(
        handle: COpaquePointer,
        node: SemanticsNode,
        config: SemanticsConfiguration,
    ) {
        fun add(
            actionId: Int,
            name: String,
            description: String,
            invocation: AtSpiInvocation,
        ) {
            actions[actionKey(node.id, actionId)] = invocation
            kld_atspi_window_add_action(
                handle,
                node.id,
                actionId,
                name,
                description,
                "",
            )
        }

        config.getOrNull(SemanticsActions.OnClick)?.action?.let {
            add(
                AtSpiActionId.Click,
                "click",
                config.getOrNull(SemanticsActions.OnClick)?.label ?: "Activate",
            ) { _, _, _, _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.OnClick)
                    ?.action
                    ?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.OnLongClick)?.action?.let {
            add(
                AtSpiActionId.LongClick,
                "long-click",
                config.getOrNull(SemanticsActions.OnLongClick)?.label ?: "Long click",
            ) { _, _, _, _ -> currentAction(node.id, SemanticsActions.OnLongClick)?.invoke() == true }
        }
        config.getOrNull(SemanticsActions.Expand)?.action?.let {
            add(AtSpiActionId.Expand, "expand", "Expand") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Expand)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.Collapse)?.action?.let {
            add(AtSpiActionId.Collapse, "collapse", "Collapse") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Collapse)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.Dismiss)?.action?.let {
            add(AtSpiActionId.Dismiss, "dismiss", "Dismiss") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Dismiss)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.RequestFocus)?.action?.let {
            add(AtSpiActionId.Focus, "focus", "Move keyboard focus here") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.RequestFocus)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.PageUp)?.action?.let {
            add(AtSpiActionId.PageUp, "page-up", "Move one page up") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.PageUp)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.PageDown)?.action?.let {
            add(AtSpiActionId.PageDown, "page-down", "Move one page down") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.PageDown)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.PageLeft)?.action?.let {
            add(AtSpiActionId.PageLeft, "page-left", "Move one page left") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.PageLeft)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.PageRight)?.action?.let {
            add(AtSpiActionId.PageRight, "page-right", "Move one page right") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.PageRight)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.CustomActions).orEmpty().forEachIndexed { index, custom ->
            val actionId = AtSpiActionId.CustomStart + index
            add(actionId, custom.label, custom.label) { _, _, _, _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.CustomActions)
                    ?.getOrNull(index)
                    ?.action
                    ?.invoke() == true
            }
        }
    }

    private fun addCollection(
        handle: COpaquePointer,
        node: SemanticsNode,
        config: SemanticsConfiguration,
    ) {
        val collection = config.getOrNull(SemanticsProperties.CollectionInfo)
        val item = config.getOrNull(SemanticsProperties.CollectionItemInfo)
        if (collection == null && item == null) return
        kld_atspi_window_set_collection(
            handle,
            node.id,
            collection?.rowCount ?: -1,
            collection?.columnCount ?: -1,
            item?.rowIndex ?: -1,
            item?.rowSpan ?: 0,
            item?.columnIndex ?: -1,
            item?.columnSpan ?: 0,
        )
    }

    private fun addEditableText(
        handle: COpaquePointer,
        node: SemanticsNode,
        config: SemanticsConfiguration,
    ) {
        fun register(actionId: Int, invocation: AtSpiInvocation): Int {
            actions[actionKey(node.id, actionId)] = invocation
            return actionId
        }

        val setText =
            config.getOrNull(SemanticsActions.SetText)?.action?.let {
                register(AtSpiActionId.SetText) { _, text, _, _ ->
                    currentConfig(node.id)
                        ?.getOrNull(SemanticsActions.SetText)
                        ?.action
                        ?.invoke(AnnotatedString(text.orEmpty())) == true
                }
            } ?: -1
        val insertText =
            config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.let {
                register(AtSpiActionId.InsertText) { _, text, position, _ ->
                    val current = currentConfig(node.id) ?: return@register false
                    val moved =
                        current.getOrNull(SemanticsActions.SetSelection)?.action
                            ?.invoke(position, position, false) ?: true
                    moved &&
                        current.getOrNull(SemanticsActions.InsertTextAtCursor)?.action
                            ?.invoke(AnnotatedString(text.orEmpty())) == true
                }
            } ?: -1
        val setSelection =
            config.getOrNull(SemanticsActions.SetSelection)?.action?.let {
                register(AtSpiActionId.SetSelection) { _, _, start, end ->
                    currentConfig(node.id)
                        ?.getOrNull(SemanticsActions.SetSelection)
                        ?.action
                        ?.invoke(start, end, false) == true
                }
            } ?: -1
        fun registerClipboard(
            actionId: Int,
            key: androidx.compose.ui.semantics.SemanticsPropertyKey<
                androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
            >,
        ): Int =
            config.getOrNull(key)?.action?.let {
                register(actionId) { _, _, start, end ->
                    val current = currentConfig(node.id) ?: return@register false
                    val moved =
                        current.getOrNull(SemanticsActions.SetSelection)?.action
                            ?.invoke(start, end, false) ?: true
                    moved && current.getOrNull(key)?.action?.invoke() == true
                }
            } ?: -1

        val copyText = registerClipboard(AtSpiActionId.CopyText, SemanticsActions.CopyText)
        val cutText = registerClipboard(AtSpiActionId.CutText, SemanticsActions.CutText)
        val pasteText = registerClipboard(AtSpiActionId.PasteText, SemanticsActions.PasteText)
        if (setText < 0 && insertText < 0 && setSelection < 0 &&
            copyText < 0 && cutText < 0 && pasteText < 0
        ) return
        kld_atspi_window_set_editable_actions(
            handle,
            node.id,
            setText,
            insertText,
            setSelection,
            copyText,
            cutText,
            pasteText,
        )
    }

    private fun addValue(
        handle: COpaquePointer,
        node: SemanticsNode,
        config: SemanticsConfiguration,
    ) {
        val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return
        val hasSetProgress = config.getOrNull(SemanticsActions.SetProgress)?.action != null
        val actionId = if (hasSetProgress) AtSpiActionId.SetProgress else -1
        if (hasSetProgress) {
            actions[actionKey(node.id, actionId)] = { value, _, _, _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.SetProgress)
                    ?.action
                    ?.invoke(value.toFloat()) == true
            }
        }
        val increment =
            if (range.steps > 0) {
                (range.range.endInclusive - range.range.start) / (range.steps + 1)
            } else {
                0f
            }
        kld_atspi_window_set_value(
            handle,
            node.id,
            range.range.start.toDouble(),
            range.range.endInclusive.toDouble(),
            range.current.toDouble(),
            increment.toDouble(),
            actionId,
        )
    }

    internal fun perform(
        nodeId: Int,
        actionId: Int,
        numericValue: Double,
        textValue: String?,
        selectionStart: Int,
        selectionEnd: Int,
    ): Int {
        val action = actions[actionKey(nodeId, actionId)] ?: return 0
        val expectedWindow = nativeWindow ?: return 0
        if (debug) println("AT-SPI: queue action node=$nodeId action=$actionId")
        dispatchAction {
            if (nativeWindow == expectedWindow) {
                val result = action(numericValue, textValue, selectionStart, selectionEnd)
                if (result) {
                    Snapshot.sendApplyNotifications()
                    refreshAfterLayout = true
                }
                if (debug) println("AT-SPI: action node=$nodeId action=$actionId result=$result")
            }
        }
        return 1
    }
}

private fun performAtSpiAction(
    context: COpaquePointer?,
    nodeId: Int,
    actionId: Int,
    numericValue: Double,
    textValue: CPointer<ByteVar>?,
    selectionStart: Int,
    selectionEnd: Int,
): Int =
    context
        ?.asStableRef<LinuxAtSpiAccessibility>()
        ?.get()
        ?.perform(
            nodeId,
            actionId,
            numericValue,
            textValue?.toKString(),
            selectionStart,
            selectionEnd,
        ) ?: 0

private fun actionKey(nodeId: Int, actionId: Int): Long =
    (nodeId.toLong() shl 32) xor actionId.toLong().and(0xffffffffL)

private data class LogicalBounds(val left: Int, val top: Int, val width: Int, val height: Int)

private fun Rect.toLogicalBounds(scaleX: Float, scaleY: Float): LogicalBounds {
    val left = floor(this.left / scaleX).toInt()
    val top = floor(this.top / scaleY).toInt()
    val right = ceil(this.right / scaleX).toInt()
    val bottom = ceil(this.bottom / scaleY).toInt()
    return LogicalBounds(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
}

private fun SemanticsNode.isInTabularCollection(): Boolean {
    var candidate = parent
    while (candidate != null) {
        val collection = candidate.config.getOrNull(SemanticsProperties.CollectionInfo)
        if (collection != null) return collection.columnCount > 1
        candidate = candidate.parent
    }
    return false
}

private fun SemanticsNode.nearestAccessibleParentId(includedIds: Set<Int>): Int {
    var candidate = parent
    while (candidate != null) {
        if (candidate.id in includedIds) return candidate.id
        candidate = candidate.parent
    }
    return -1
}

private fun SemanticsConfiguration.isHiddenFromAccessibility(): Boolean =
    getOrNull(SemanticsProperties.HideFromAccessibility) != null ||
        getOrNull(SemanticsProperties.InvisibleToUser) != null

private fun SemanticsConfiguration.accessibleText(): String {
    val value =
        getOrNull(SemanticsProperties.EditableText)?.text
            ?: getOrNull(SemanticsProperties.InputText)?.text
            ?: getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    return if (getOrNull(SemanticsProperties.Password) != null) "•".repeat(value.length) else value
}

private fun SemanticsConfiguration.accessibleName(text: String): String =
    getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ")?.takeIf(String::isNotBlank)
        ?: getOrNull(SemanticsProperties.PaneTitle)?.takeIf(String::isNotBlank)
        ?: text

private fun SemanticsConfiguration.atSpiRole(text: String, tabularItem: Boolean): UInt {
    if (getOrNull(SemanticsProperties.IsDialog) != null) return AtSpiRole.Dialog
    if (getOrNull(SemanticsProperties.Heading) != null) return AtSpiRole.Heading
    when (getOrNull(SemanticsProperties.Role)) {
        Role.Button -> return AtSpiRole.Button
        Role.Checkbox -> return AtSpiRole.CheckBox
        Role.Switch -> return AtSpiRole.Switch
        Role.RadioButton -> return AtSpiRole.RadioButton
        Role.Tab -> return AtSpiRole.PageTab
        Role.Image -> return AtSpiRole.Image
        Role.DropdownList -> return AtSpiRole.ComboBox
        Role.ValuePicker -> return AtSpiRole.Slider
        Role.Carousel -> return AtSpiRole.List
    }
    return when {
        getOrNull(SemanticsProperties.Password) != null -> AtSpiRole.PasswordText
        getOrNull(SemanticsActions.SetText) != null ||
            getOrNull(SemanticsProperties.IsEditable) == true -> AtSpiRole.Entry
        getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null ->
            if (getOrNull(SemanticsActions.SetProgress) != null) AtSpiRole.Slider
            else AtSpiRole.ProgressBar
        getOrNull(SemanticsActions.ScrollBy) != null -> AtSpiRole.ScrollPane
        getOrNull(SemanticsProperties.CollectionItemInfo) != null ->
            if (tabularItem) AtSpiRole.TableCell else AtSpiRole.ListItem
        getOrNull(SemanticsProperties.CollectionInfo)?.columnCount?.let { it > 1 } == true ->
            AtSpiRole.Table
        getOrNull(SemanticsProperties.CollectionInfo) != null -> AtSpiRole.List
        getOrNull(SemanticsProperties.IsTraversalGroup) == true -> AtSpiRole.Grouping
        text.isNotEmpty() -> AtSpiRole.Label
        else -> AtSpiRole.Panel
    }
}

private fun SemanticsConfiguration.atSpiStates(
    windowVisible: Boolean,
    bounds: LogicalBounds,
    text: String,
): ULong {
    var states = 0uL
    fun add(state: Int) {
        states = states or (1uL shl state)
    }

    val disabled = getOrNull(SemanticsProperties.Disabled) != null
    if (!disabled) {
        add(AtSpiState.Enabled)
        add(AtSpiState.Sensitive)
    }
    if (windowVisible && bounds.width > 0 && bounds.height > 0) {
        add(AtSpiState.Showing)
        add(AtSpiState.Visible)
    }
    val focused = getOrNull(SemanticsProperties.Focused)
    if (focused != null || getOrNull(SemanticsActions.RequestFocus) != null) add(AtSpiState.Focusable)
    if (focused == true) {
        add(AtSpiState.Focused)
        add(AtSpiState.Active)
    }
    if (getOrNull(SemanticsProperties.IsEditable) == true || getOrNull(SemanticsActions.SetText) != null) {
        add(AtSpiState.Editable)
    } else if (text.isNotEmpty()) {
        add(AtSpiState.ReadOnly)
    }
    if (getOrNull(SemanticsProperties.TextSelectionRange) != null) add(AtSpiState.SelectableText)

    val canExpand = getOrNull(SemanticsActions.Expand) != null
    val canCollapse = getOrNull(SemanticsActions.Collapse) != null
    if (canExpand || canCollapse) add(AtSpiState.Expandable)
    if (canExpand) add(AtSpiState.Collapsed)
    if (canCollapse) add(AtSpiState.Expanded)

    val selected = getOrNull(SemanticsProperties.Selected)
    if (selected != null) add(AtSpiState.Selectable)
    if (selected == true) add(AtSpiState.Selected)

    when (getOrNull(SemanticsProperties.ToggleableState)) {
        ToggleableState.On -> {
            add(AtSpiState.Checkable)
            add(AtSpiState.Checked)
        }
        ToggleableState.Indeterminate -> {
            add(AtSpiState.Checkable)
            add(AtSpiState.Indeterminate)
        }
        ToggleableState.Off -> add(AtSpiState.Checkable)
        null -> Unit
    }
    if (getOrNull(SemanticsProperties.IsPopup) != null) add(AtSpiState.HasPopup)
    if (getOrNull(SemanticsProperties.IsDialog) != null) add(AtSpiState.Modal)
    if (getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) add(AtSpiState.Horizontal)
    if (getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) add(AtSpiState.Vertical)
    return states
}
