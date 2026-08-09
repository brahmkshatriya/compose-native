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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.posix.getenv
import platform.windows.CHILDID_SELF
import platform.windows.EVENT_OBJECT_CREATE
import platform.windows.EVENT_OBJECT_DESTROY
import platform.windows.EVENT_OBJECT_FOCUS
import platform.windows.EVENT_OBJECT_LOCATIONCHANGE
import platform.windows.EVENT_OBJECT_NAMECHANGE
import platform.windows.EVENT_OBJECT_REORDER
import platform.windows.EVENT_OBJECT_STATECHANGE
import platform.windows.EVENT_OBJECT_TEXTSELECTIONCHANGED
import platform.windows.EVENT_OBJECT_VALUECHANGE
import platform.windows.GetActiveWindow
import platform.windows.HWND
import platform.windows.NotifyWinEvent
import platform.windows.OBJID_CLIENT

/** Provider-neutral roles that map directly to UI Automation control types. */
internal enum class NativeAccessibilityRole {
    Button,
    CheckBox,
    ComboBox,
    Dialog,
    Edit,
    Group,
    Header,
    Image,
    List,
    ListItem,
    Pane,
    ProgressBar,
    RadioButton,
    ScrollPane,
    Slider,
    TabItem,
    Table,
    TableCell,
    Text,
}

/** UI Automation patterns supported by a semantics node. */
internal enum class NativeAccessibilityPattern {
    ExpandCollapse,
    Grid,
    GridItem,
    Invoke,
    RangeValue,
    Scroll,
    Selection,
    SelectionItem,
    Text,
    Toggle,
    Value,
}

internal enum class NativeAccessibilityToggleState {
    Off,
    On,
    Indeterminate,
}

internal data class NativeAccessibilityBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class NativeAccessibilityRange(
    val minimum: Double,
    val maximum: Double,
    val value: Double,
    val increment: Double,
    val readOnly: Boolean,
)

internal data class NativeAccessibilityCollection(
    val rowCount: Int,
    val columnCount: Int,
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val columnSpan: Int,
)

internal data class NativeAccessibilityActionDescriptor(
    val id: Int,
    val name: String,
    val description: String,
)

/** Immutable node representation consumed by a future IRawElementProviderFragment adapter. */
internal data class NativeAccessibilityNode(
    val id: Int,
    val parentId: Int,
    val role: NativeAccessibilityRole,
    val name: String,
    val description: String,
    val automationId: String,
    val text: String,
    val bounds: NativeAccessibilityBounds,
    val enabled: Boolean,
    val visible: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val selected: Boolean?,
    val toggleState: NativeAccessibilityToggleState?,
    val expanded: Boolean?,
    val editable: Boolean,
    val readOnly: Boolean,
    val password: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    val range: NativeAccessibilityRange?,
    val collection: NativeAccessibilityCollection?,
    val patterns: Set<NativeAccessibilityPattern>,
    val actions: List<NativeAccessibilityActionDescriptor>,
)

internal data class NativeAccessibilityWindowSnapshot(
    val title: String,
    val visible: Boolean,
    val focused: Boolean,
    val screenX: Int,
    val screenY: Int,
    val width: Int,
    val height: Int,
)

/**
 * A stable, provider-ready snapshot of the Compose semantics tree.
 *
 * Kotlin/Native's bundled MinGW platform library does not expose UIAutomationCore interfaces, and
 * SDL's window procedure does not currently forward WM_GETOBJECT to Compose. Consequently this
 * class owns tree construction, action dispatch, and WinEvent invalidation, while deliberately
 * keeping the final COM adapter separate. That adapter can consume [nodes] and call
 * [NativeAccessibility.perform].
 */
internal data class NativeAccessibilitySnapshot(
    val generation: Long,
    val window: NativeAccessibilityWindowSnapshot,
    val nodes: List<NativeAccessibilityNode>,
)

private data class NativeAccessibilityWindowState(
    val title: String = "",
    val visible: Boolean = false,
    val focused: Boolean = false,
    val screenX: Int = 0,
    val screenY: Int = 0,
    val width: Int = 1,
    val height: Int = 1,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    fun snapshot(): NativeAccessibilityWindowSnapshot =
        NativeAccessibilityWindowSnapshot(
            title = title,
            visible = visible,
            focused = focused,
            screenX = screenX,
            screenY = screenY,
            width = width,
            height = height,
        )
}

private object NativeAccessibilityActionId {
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

private typealias NativeAccessibilityInvocation = (Double, String?, Int, Int) -> Boolean

/** Windows counterpart of LinuxAtSpiAccessibility. */
internal class NativeAccessibility(private val dispatchAction: (() -> Unit) -> Unit) :
    PlatformContext.SemanticsOwnerListener {
    private val debug = getenv("KTNATIVE_UIA_DEBUG") != null
    private val owners = mutableSetOf<SemanticsOwner>()
    private val actions = mutableMapOf<Long, NativeAccessibilityInvocation>()
    private var windowHandle: HWND? = null
    private var windowState = NativeAccessibilityWindowState()
    private var opened = false
    private var refreshAfterLayout = false
    private var rebuildPending = false
    private var generation = 0L
    private var lifetime = 0L

    internal var snapshot =
        NativeAccessibilitySnapshot(
            generation = generation,
            window = windowState.snapshot(),
            nodes = emptyList(),
        )
        private set

    fun open(title: String) {
        if (opened) return
        opened = true
        lifetime++
        windowHandle = windowHandle ?: GetActiveWindow()
        windowState = windowState.copy(title = title)
        rebuildPending = true
        rebuild()
        notifyWindows(EVENT_OBJECT_CREATE)
    }

    /**
     * Binds the bridge to an HWND obtained from SDL_PROP_WINDOW_WIN32_HWND_POINTER.
     *
     * [open] also attempts GetActiveWindow, but explicit attachment avoids ambiguity for multiple
     * windows and is the required path for a future WM_GETOBJECT adapter.
     */
    fun attachToNativeWindow(handle: COpaquePointer?) {
        val next: HWND? = handle?.reinterpret()
        if (windowHandle == next) return
        windowHandle = next
        if (opened) {
            notifyWindows(EVENT_OBJECT_REORDER)
            rebuildPending = true
        }
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
            NativeAccessibilityWindowState(
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
        if (next.focused && windowHandle == null) GetActiveWindow()?.let { windowHandle = it }
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
        if (opened) notifyWindows(EVENT_OBJECT_DESTROY)
        opened = false
        lifetime++
        actions.clear()
        owners.clear()
        refreshAfterLayout = false
        rebuildPending = false
        generation++
        snapshot =
            NativeAccessibilitySnapshot(
                generation = generation,
                window = windowState.snapshot(),
                nodes = emptyList(),
            )
        windowHandle = null
    }

    fun refreshAfterLayout() {
        if (!refreshAfterLayout && !rebuildPending) return
        refreshAfterLayout = false
        rebuild()
    }

    /** Retained for the shared SDL event-loop contract; Windows has no accessibility bus. */
    fun onAccessibilityBusConnected() {
        if (rebuildPending) rebuild()
    }

    private fun rebuild() {
        if (!opened) return
        rebuildPending = false
        val previous = snapshot
        val state = windowState
        val semanticsNodes =
            owners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .filterNot { it.config.isHiddenFromAccessibility() }
                .distinctBy(SemanticsNode::id)
        val includedIds = semanticsNodes.mapTo(mutableSetOf(), SemanticsNode::id)
        actions.clear()

        val nodes =
            semanticsNodes.map { node ->
                val config = node.config
                val text = config.accessibleText()
                val bounds = node.boundsInWindow.toScreenBounds(state)
                val actionDescriptors = registerActions(node, config)
                val collection = config.nativeCollection()
                val range = config.nativeRange()
                val selected = config.getOrNull(SemanticsProperties.Selected)
                val toggleState = config.getOrNull(SemanticsProperties.ToggleableState).toNative()
                val editable =
                    config.getOrNull(SemanticsProperties.IsEditable) == true ||
                        config.getOrNull(SemanticsActions.SetText) != null
                val visible = state.visible && bounds.width > 0 && bounds.height > 0
                val focused = config.getOrNull(SemanticsProperties.Focused) == true
                val focusable =
                    config.getOrNull(SemanticsProperties.Focused) != null ||
                        config.getOrNull(SemanticsActions.RequestFocus) != null
                val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
                NativeAccessibilityNode(
                    id = node.id,
                    parentId = node.nearestAccessibleParentId(includedIds),
                    role = config.nativeRole(text, node.isInTabularCollection()),
                    name = config.accessibleName(text),
                    description = config.getOrNull(SemanticsProperties.StateDescription).orEmpty(),
                    automationId =
                        config.getOrNull(SemanticsProperties.TestTag)?.takeIf(String::isNotBlank)
                            ?: "compose-${node.id}",
                    text = text,
                    bounds = bounds,
                    enabled = config.getOrNull(SemanticsProperties.Disabled) == null,
                    visible = visible,
                    focusable = focusable,
                    focused = focused,
                    selected = selected,
                    toggleState = toggleState,
                    expanded =
                        when {
                            config.getOrNull(SemanticsActions.Collapse) != null -> true
                            config.getOrNull(SemanticsActions.Expand) != null -> false
                            else -> null
                        },
                    editable = editable,
                    readOnly = !editable,
                    password = config.getOrNull(SemanticsProperties.Password) != null,
                    selectionStart = selection?.start ?: 0,
                    selectionEnd = selection?.end ?: 0,
                    range = range,
                    collection = collection,
                    patterns = config.nativePatterns(),
                    actions = actionDescriptors,
                )
            }

        generation++
        snapshot =
            NativeAccessibilitySnapshot(
                generation = generation,
                window = state.snapshot(),
                nodes = nodes,
            )
        emitWindowsChanges(previous, snapshot)
    }

    private fun registerActions(
        node: SemanticsNode,
        config: SemanticsConfiguration,
    ): List<NativeAccessibilityActionDescriptor> {
        val descriptors = mutableListOf<NativeAccessibilityActionDescriptor>()

        fun add(
            actionId: Int,
            name: String,
            description: String,
            invocation: NativeAccessibilityInvocation,
        ) {
            descriptors += NativeAccessibilityActionDescriptor(actionId, name, description)
            actions[actionKey(node.id, actionId)] = invocation
        }

        config.getOrNull(SemanticsActions.OnClick)?.action?.let {
            add(
                NativeAccessibilityActionId.Click,
                "invoke",
                config.getOrNull(SemanticsActions.OnClick)?.label ?: "Activate",
            ) { _, _, _, _ ->
                currentConfig(node.id)?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ==
                    true
            }
        }
        config.getOrNull(SemanticsActions.OnLongClick)?.action?.let {
            add(
                NativeAccessibilityActionId.LongClick,
                "long-click",
                config.getOrNull(SemanticsActions.OnLongClick)?.label ?: "Long click",
            ) { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.OnLongClick)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.Expand)?.action?.let {
            add(NativeAccessibilityActionId.Expand, "expand", "Expand") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Expand)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.Collapse)?.action?.let {
            add(NativeAccessibilityActionId.Collapse, "collapse", "Collapse") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Collapse)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.Dismiss)?.action?.let {
            add(NativeAccessibilityActionId.Dismiss, "dismiss", "Dismiss") { _, _, _, _ ->
                currentAction(node.id, SemanticsActions.Dismiss)?.invoke() == true
            }
        }
        config.getOrNull(SemanticsActions.RequestFocus)?.action?.let {
            add(NativeAccessibilityActionId.Focus, "focus", "Move keyboard focus here") { _, _, _, _
                ->
                currentAction(node.id, SemanticsActions.RequestFocus)?.invoke() == true
            }
        }
        registerPagingActions(node, config, ::add)
        registerTextActions(node, config, ::add)
        registerRangeAction(node, config, ::add)
        config.getOrNull(SemanticsActions.CustomActions).orEmpty().forEachIndexed { index, custom ->
            val actionId = NativeAccessibilityActionId.CustomStart + index
            add(actionId, custom.label, custom.label) { _, _, _, _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.CustomActions)
                    ?.getOrNull(index)
                    ?.action
                    ?.invoke() == true
            }
        }
        return descriptors
    }

    private fun registerPagingActions(
        node: SemanticsNode,
        config: SemanticsConfiguration,
        add: (Int, String, String, NativeAccessibilityInvocation) -> Unit,
    ) {
        fun register(
            id: Int,
            name: String,
            description: String,
            key:
                androidx.compose.ui.semantics.SemanticsPropertyKey<
                    androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
                >,
        ) {
            config.getOrNull(key)?.action?.let {
                add(id, name, description) { _, _, _, _ ->
                    currentAction(node.id, key)?.invoke() == true
                }
            }
        }
        register(
            NativeAccessibilityActionId.PageUp,
            "page-up",
            "Move one page up",
            SemanticsActions.PageUp,
        )
        register(
            NativeAccessibilityActionId.PageDown,
            "page-down",
            "Move one page down",
            SemanticsActions.PageDown,
        )
        register(
            NativeAccessibilityActionId.PageLeft,
            "page-left",
            "Move one page left",
            SemanticsActions.PageLeft,
        )
        register(
            NativeAccessibilityActionId.PageRight,
            "page-right",
            "Move one page right",
            SemanticsActions.PageRight,
        )
    }

    private fun registerTextActions(
        node: SemanticsNode,
        config: SemanticsConfiguration,
        add: (Int, String, String, NativeAccessibilityInvocation) -> Unit,
    ) {
        config.getOrNull(SemanticsActions.SetText)?.action?.let {
            add(NativeAccessibilityActionId.SetText, "set-value", "Set text") { _, text, _, _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.SetText)
                    ?.action
                    ?.invoke(AnnotatedString(text.orEmpty())) == true
            }
        }
        config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.let {
            add(NativeAccessibilityActionId.InsertText, "insert-text", "Insert text") {
                _,
                text,
                position,
                _ ->
                val current = currentConfig(node.id) ?: return@add false
                val moved =
                    current
                        .getOrNull(SemanticsActions.SetSelection)
                        ?.action
                        ?.invoke(position, position, false) ?: true
                moved &&
                    current
                        .getOrNull(SemanticsActions.InsertTextAtCursor)
                        ?.action
                        ?.invoke(AnnotatedString(text.orEmpty())) == true
            }
        }
        config.getOrNull(SemanticsActions.SetSelection)?.action?.let {
            add(NativeAccessibilityActionId.SetSelection, "set-selection", "Set text selection") {
                _,
                _,
                start,
                end ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.SetSelection)
                    ?.action
                    ?.invoke(start, end, false) == true
            }
        }
        registerClipboardAction(
            node,
            config,
            NativeAccessibilityActionId.CopyText,
            "copy",
            SemanticsActions.CopyText,
            add,
        )
        registerClipboardAction(
            node,
            config,
            NativeAccessibilityActionId.CutText,
            "cut",
            SemanticsActions.CutText,
            add,
        )
        registerClipboardAction(
            node,
            config,
            NativeAccessibilityActionId.PasteText,
            "paste",
            SemanticsActions.PasteText,
            add,
        )
    }

    private fun registerClipboardAction(
        node: SemanticsNode,
        config: SemanticsConfiguration,
        actionId: Int,
        name: String,
        key:
            androidx.compose.ui.semantics.SemanticsPropertyKey<
                androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
            >,
        add: (Int, String, String, NativeAccessibilityInvocation) -> Unit,
    ) {
        config.getOrNull(key)?.action?.let {
            add(actionId, name, name.replaceFirstChar(Char::uppercaseChar)) { _, _, start, end ->
                val current = currentConfig(node.id) ?: return@add false
                val moved =
                    current
                        .getOrNull(SemanticsActions.SetSelection)
                        ?.action
                        ?.invoke(start, end, false) ?: true
                moved && current.getOrNull(key)?.action?.invoke() == true
            }
        }
    }

    private fun registerRangeAction(
        node: SemanticsNode,
        config: SemanticsConfiguration,
        add: (Int, String, String, NativeAccessibilityInvocation) -> Unit,
    ) {
        config.getOrNull(SemanticsActions.SetProgress)?.action?.let {
            add(NativeAccessibilityActionId.SetProgress, "set-range-value", "Set value") {
                value,
                _,
                _,
                _ ->
                currentConfig(node.id)
                    ?.getOrNull(SemanticsActions.SetProgress)
                    ?.action
                    ?.invoke(value.toFloat()) == true
            }
        }
    }

    private fun currentAction(
        nodeId: Int,
        key:
            androidx.compose.ui.semantics.SemanticsPropertyKey<
                androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>
            >,
    ): (() -> Boolean)? = currentConfig(nodeId)?.getOrNull(key)?.action

    private fun currentConfig(nodeId: Int): SemanticsConfiguration? =
        owners
            .asSequence()
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
            .firstOrNull { it.id == nodeId }
            ?.config

    /** Entry point used by an IRawElementProvider pattern implementation. */
    internal fun perform(
        nodeId: Int,
        actionId: Int,
        numericValue: Double,
        textValue: String?,
        selectionStart: Int,
        selectionEnd: Int,
    ): Int {
        val action = actions[actionKey(nodeId, actionId)] ?: return 0
        val expectedLifetime = lifetime
        if (debug) println("UIA: queue action node=$nodeId action=$actionId")
        dispatchAction {
            if (opened && lifetime == expectedLifetime) {
                val result = action(numericValue, textValue, selectionStart, selectionEnd)
                if (result) {
                    Snapshot.sendApplyNotifications()
                    refreshAfterLayout = true
                }
                if (debug) println("UIA: action node=$nodeId action=$actionId result=$result")
            }
        }
        return 1
    }

    private fun emitWindowsChanges(
        previous: NativeAccessibilitySnapshot,
        current: NativeAccessibilitySnapshot,
    ) {
        if (previous.window.title != current.window.title) notifyWindows(EVENT_OBJECT_NAMECHANGE)
        if (
            previous.window.visible != current.window.visible ||
                previous.window.focused != current.window.focused
        ) {
            notifyWindows(EVENT_OBJECT_STATECHANGE)
        }
        if (
            previous.window.screenX != current.window.screenX ||
                previous.window.screenY != current.window.screenY ||
                previous.window.width != current.window.width ||
                previous.window.height != current.window.height
        ) {
            notifyWindows(EVENT_OBJECT_LOCATIONCHANGE)
        }

        val oldNodes = previous.nodes.associateBy(NativeAccessibilityNode::id)
        val newNodes = current.nodes.associateBy(NativeAccessibilityNode::id)
        if (
            oldNodes.keys != newNodes.keys ||
                previous.nodes.map { it.id to it.parentId } !=
                    current.nodes.map { it.id to it.parentId }
        ) {
            notifyWindows(EVENT_OBJECT_REORDER)
        }
        val changed = oldNodes.keys.intersect(newNodes.keys)
        if (changed.any { oldNodes[it]?.name != newNodes[it]?.name }) {
            notifyWindows(EVENT_OBJECT_NAMECHANGE)
        }
        if (
            changed.any {
                oldNodes[it]?.text != newNodes[it]?.text ||
                    oldNodes[it]?.range != newNodes[it]?.range
            }
        ) {
            notifyWindows(EVENT_OBJECT_VALUECHANGE)
        }
        if (
            changed.any {
                oldNodes[it]?.selectionStart != newNodes[it]?.selectionStart ||
                    oldNodes[it]?.selectionEnd != newNodes[it]?.selectionEnd
            }
        ) {
            notifyWindows(EVENT_OBJECT_TEXTSELECTIONCHANGED)
        }
        if (changed.any { oldNodes[it]?.bounds != newNodes[it]?.bounds }) {
            notifyWindows(EVENT_OBJECT_LOCATIONCHANGE)
        }
        if (
            changed.any {
                val old = oldNodes[it]
                val new = newNodes[it]
                old?.enabled != new?.enabled ||
                    old?.visible != new?.visible ||
                    old?.selected != new?.selected ||
                    old?.toggleState != new?.toggleState ||
                    old?.expanded != new?.expanded
            }
        ) {
            notifyWindows(EVENT_OBJECT_STATECHANGE)
        }
        if (
            previous.nodes.firstOrNull { it.focused }?.id !=
                current.nodes.firstOrNull { it.focused }?.id
        ) {
            notifyWindows(EVENT_OBJECT_FOCUS)
        }
    }

    private fun notifyWindows(event: Int) {
        val handle = windowHandle ?: return
        // Until WM_GETOBJECT exposes fragment providers, only announce the client object itself.
        NotifyWinEvent(event.toUInt(), handle, OBJID_CLIENT, CHILDID_SELF)
    }
}

private fun actionKey(nodeId: Int, actionId: Int): Long =
    (nodeId.toLong() shl 32) xor actionId.toLong().and(0xffffffffL)

private fun Rect.toScreenBounds(state: NativeAccessibilityWindowState): NativeAccessibilityBounds {
    // Compose's scene coordinates are framebuffer pixels, while SDL reports the window position
    // in desktop coordinates. A native provider should replace the origin with ClientToScreen(HWND)
    // when it is attached so that window decorations cannot skew hit testing.
    val left = state.screenX + floor(this.left / state.scaleX).toInt()
    val top = state.screenY + floor(this.top / state.scaleY).toInt()
    val right = state.screenX + ceil(this.right / state.scaleX).toInt()
    val bottom = state.screenY + ceil(this.bottom / state.scaleY).toInt()
    return NativeAccessibilityBounds(
        left = left,
        top = top,
        width = (right - left).coerceAtLeast(0),
        height = (bottom - top).coerceAtLeast(0),
    )
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
    getOrNull(SemanticsProperties.ContentDescription)
        ?.joinToString(", ")
        ?.takeIf(String::isNotBlank)
        ?: getOrNull(SemanticsProperties.PaneTitle)?.takeIf(String::isNotBlank)
        ?: text

private fun SemanticsConfiguration.nativeRole(
    text: String,
    tabularItem: Boolean,
): NativeAccessibilityRole {
    if (getOrNull(SemanticsProperties.IsDialog) != null) return NativeAccessibilityRole.Dialog
    if (getOrNull(SemanticsProperties.Heading) != null) return NativeAccessibilityRole.Header
    when (getOrNull(SemanticsProperties.Role)) {
        Role.Button -> return NativeAccessibilityRole.Button
        Role.Checkbox -> return NativeAccessibilityRole.CheckBox
        Role.Switch -> return NativeAccessibilityRole.CheckBox
        Role.RadioButton -> return NativeAccessibilityRole.RadioButton
        Role.Tab -> return NativeAccessibilityRole.TabItem
        Role.Image -> return NativeAccessibilityRole.Image
        Role.DropdownList -> return NativeAccessibilityRole.ComboBox
        Role.ValuePicker -> return NativeAccessibilityRole.Slider
        Role.Carousel -> return NativeAccessibilityRole.List
    }
    return when {
        getOrNull(SemanticsProperties.Password) != null -> NativeAccessibilityRole.Edit
        getOrNull(SemanticsActions.SetText) != null ||
            getOrNull(SemanticsProperties.IsEditable) == true -> NativeAccessibilityRole.Edit
        getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null ->
            if (getOrNull(SemanticsActions.SetProgress) != null) NativeAccessibilityRole.Slider
            else NativeAccessibilityRole.ProgressBar
        getOrNull(SemanticsActions.ScrollBy) != null -> NativeAccessibilityRole.ScrollPane
        getOrNull(SemanticsProperties.CollectionItemInfo) != null ->
            if (tabularItem) NativeAccessibilityRole.TableCell else NativeAccessibilityRole.ListItem
        getOrNull(SemanticsProperties.CollectionInfo)?.columnCount?.let { it > 1 } == true ->
            NativeAccessibilityRole.Table
        getOrNull(SemanticsProperties.CollectionInfo) != null -> NativeAccessibilityRole.List
        getOrNull(SemanticsProperties.IsTraversalGroup) == true -> NativeAccessibilityRole.Group
        text.isNotEmpty() -> NativeAccessibilityRole.Text
        else -> NativeAccessibilityRole.Pane
    }
}

private fun SemanticsConfiguration.nativePatterns(): Set<NativeAccessibilityPattern> = buildSet {
    if (getOrNull(SemanticsActions.OnClick) != null) add(NativeAccessibilityPattern.Invoke)
    if (getOrNull(SemanticsProperties.ToggleableState) != null) {
        add(NativeAccessibilityPattern.Toggle)
    }
    if (
        getOrNull(SemanticsActions.Expand) != null || getOrNull(SemanticsActions.Collapse) != null
    ) {
        add(NativeAccessibilityPattern.ExpandCollapse)
    }
    if (getOrNull(SemanticsProperties.Selected) != null) {
        add(NativeAccessibilityPattern.SelectionItem)
    }
    if (getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null) {
        add(NativeAccessibilityPattern.RangeValue)
    }
    if (
        getOrNull(SemanticsActions.SetText) != null ||
            getOrNull(SemanticsProperties.IsEditable) == true
    ) {
        add(NativeAccessibilityPattern.Value)
    }
    if (
        getOrNull(SemanticsProperties.Text) != null ||
            getOrNull(SemanticsProperties.EditableText) != null ||
            getOrNull(SemanticsProperties.InputText) != null
    ) {
        add(NativeAccessibilityPattern.Text)
    }
    if (
        getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
            getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    ) {
        add(NativeAccessibilityPattern.Scroll)
    }
    getOrNull(SemanticsProperties.CollectionInfo)?.let {
        add(NativeAccessibilityPattern.Selection)
        if (it.columnCount > 1) add(NativeAccessibilityPattern.Grid)
    }
    getOrNull(SemanticsProperties.CollectionItemInfo)?.let {
        if (isInGridCollection()) add(NativeAccessibilityPattern.GridItem)
    }
}

private fun SemanticsConfiguration.isInGridCollection(): Boolean =
    getOrNull(SemanticsProperties.CollectionItemInfo)?.let { it.columnIndex >= 0 } == true

private fun SemanticsConfiguration.nativeRange(): NativeAccessibilityRange? {
    val range = getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return null
    val increment =
        if (range.steps > 0) {
            (range.range.endInclusive - range.range.start) / (range.steps + 1)
        } else {
            0f
        }
    return NativeAccessibilityRange(
        minimum = range.range.start.toDouble(),
        maximum = range.range.endInclusive.toDouble(),
        value = range.current.toDouble(),
        increment = increment.toDouble(),
        readOnly = getOrNull(SemanticsActions.SetProgress) == null,
    )
}

private fun SemanticsConfiguration.nativeCollection(): NativeAccessibilityCollection? {
    val collection = getOrNull(SemanticsProperties.CollectionInfo)
    val item = getOrNull(SemanticsProperties.CollectionItemInfo)
    if (collection == null && item == null) return null
    return NativeAccessibilityCollection(
        rowCount = collection?.rowCount ?: -1,
        columnCount = collection?.columnCount ?: -1,
        rowIndex = item?.rowIndex ?: -1,
        rowSpan = item?.rowSpan ?: 0,
        columnIndex = item?.columnIndex ?: -1,
        columnSpan = item?.columnSpan ?: 0,
    )
}

private fun ToggleableState?.toNative(): NativeAccessibilityToggleState? =
    when (this) {
        ToggleableState.Off -> NativeAccessibilityToggleState.Off
        ToggleableState.On -> NativeAccessibilityToggleState.On
        ToggleableState.Indeterminate -> NativeAccessibilityToggleState.Indeterminate
        null -> null
    }
