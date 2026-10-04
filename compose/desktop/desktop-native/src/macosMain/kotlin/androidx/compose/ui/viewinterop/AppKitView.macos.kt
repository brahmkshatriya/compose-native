@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.viewinterop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.traverseDescendantsInDrawOrder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import cnames.structs.SDL_Window
import kotlin.math.roundToInt
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import nativedesktop.kld_macos_interop_container_place_view
import nativedesktop.kld_macos_interop_host_background_view
import nativedesktop.kld_macos_interop_host_create
import nativedesktop.kld_macos_interop_host_destroy
import nativedesktop.kld_macos_interop_host_overlay_view
import nativedesktop.kld_macos_interop_host_prepare_render_surface
import nativedesktop.kld_macos_interop_host_set_background_hit_test
import nativedesktop.kld_macos_interop_host_sync_z_order
import nativedesktop.kld_macos_interop_wrapper_create
import nativedesktop.kld_macos_interop_wrapper_destroy
import nativedesktop.kld_macos_interop_wrapper_fitting_size
import nativedesktop.kld_macos_interop_wrapper_set_interactive
import platform.AppKit.NSView
import platform.Foundation.NSMakeRect

/** Determines which side of the Compose rendering surface hosts an [AppKitView]. */
public enum class AppKitInteropPlacement {
    /**
     * Places the AppKit view below Compose. The composable clears its occupied pixels from the
     * Compose surface, so later Compose drawing can appear above the native view.
     */
    Background,

    /**
     * Places the AppKit view above the Compose surface. The native view may cover Compose content
     * regardless of Compose draw order.
     */
    Overlay,
}

/** Configuration for [AppKitView]. */
@Immutable
public data class AppKitInteropProperties(
    /** Whether AppKit should route mouse, keyboard, focus, and gesture input to the native view. */
    public val isInteractive: Boolean = true,
    /** Whether the native view lives below or above the Compose rendering surface. */
    public val placement: AppKitInteropPlacement = AppKitInteropPlacement.Background,
    /** Whether rectangular clipping from Compose ancestors is applied to the native wrapper. */
    public val clipToBounds: Boolean = true,
)

/**
 * Embeds an AppKit [NSView] in a Compose Native macOS window.
 *
 * The view remains a real AppKit child view: native focus, text input/IME, accessibility,
 * scrolling, gestures, drag-and-drop, context menus, and platform controls are handled by AppKit.
 * Compose owns the view's position and size while it is hosted.
 *
 * [AppKitInteropPlacement.Background] supports Compose content drawn above the native view by
 * clearing the corresponding region of the Skia surface. [AppKitInteropPlacement.Overlay] is
 * useful for views that must always remain above Compose.
 *
 * [factory] is invoked once when this composable enters composition and must return a detached
 * [NSView]. [update] is snapshot-observed independently from recomposition. [onRelease] is invoked
 * exactly once when the view permanently leaves composition.
 */
@Composable
public fun <T : NSView> AppKitView(
    factory: () -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = {},
    onRelease: (T) -> Unit = {},
    properties: AppKitInteropProperties = AppKitInteropProperties(),
) {
    val host =
        LocalNativePlatformViewHost.current as? MacosNativePlatformViewHost
            ?: error("AppKitView must be hosted inside a Compose Native macOS Window")
    val density = LocalDensity.current
    val view = remember { factory() }
    val latestOnRelease = rememberUpdatedState(onRelease)
    val holder = remember(host, view) { AppKitViewHolder(host, view, properties) }

    SideEffect {
        holder.density = density
        holder.properties = properties
        holder.setUpdate(update)
    }

    DisposableEffect(holder) {
        onDispose { holder.dispose { latestOnRelease.value(view) } }
    }

    val nativeModifier =
        if (properties.placement == AppKitInteropPlacement.Background) {
            modifier.drawBehind {
                // The macOS Skia render surfaces are configured for alpha compositing. Clearing
                // here exposes the background AppKit container while preserving normal Compose
                // draw order for content rendered after this node.
                drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            }
        } else {
            modifier
        }
    val inputModifier =
        if (properties.placement == AppKitInteropPlacement.Background) {
            nativeModifier.platformInteropHitTestTarget(holder, properties.isInteractive)
        } else {
            nativeModifier
        }

    Layout(
        content = {},
        modifier = inputModifier.then(AppKitInteropTraversalElement(host, holder)),
    ) { _, constraints ->
        val size = holder.measure(constraints, density)
        layout(size.width, size.height) {}
    }
}

private class MacosNativePlatformViewHost(
    private val handle: COpaquePointer,
    private val dispatch: (action: () -> Unit) -> Unit,
) : NativePlatformViewHost {
    private val backgroundContainer: COpaquePointer =
        checkNotNull(kld_macos_interop_host_background_view(handle))
    private val overlayContainer: COpaquePointer =
        checkNotNull(kld_macos_interop_host_overlay_view(handle))
    private val holders = linkedSetOf<AppKitViewHolder<*>>()
    private val snapshotObserver = SnapshotStateObserver { command -> dispatch(command) }
    private val reference = StableRef.create(this)
    private var interopHitTest: ((Offset) -> Any?)? = null
    private var closed = false

    internal var rootNode: AppKitInteropTraversalNode? = null

    override val rootModifier: Modifier = AppKitInteropTraversalElement(this, null)

    init {
        snapshotObserver.start()
    }

    override fun onRenderLayerAttached() {
        kld_macos_interop_host_prepare_render_surface(handle)
        kld_macos_interop_host_sync_z_order(handle)
    }

    override fun setInteropHitTest(hitTest: ((Offset) -> Any?)?) {
        if (closed) return
        interopHitTest = hitTest
        kld_macos_interop_host_set_background_hit_test(
            handle,
            if (hitTest == null) null else MacosInteropBackgroundHitTest,
            if (hitTest == null) null else reference.asCPointer(),
        )
    }

    fun hitTestBackground(x: Double, y: Double): COpaquePointer? {
        if (closed) return null
        val position =
            Offset(
                x = x.toFloat(),
                y = y.toFloat(),
            )
        val target =
            interopHitTest?.invoke(
                position
            ) as? AppKitViewHolder<*> ?: return null
        return if (
            target.host === this &&
                target.isPlaced &&
                target.properties.isInteractive &&
                target.properties.placement == AppKitInteropPlacement.Background
        ) {
            target.wrapperHandle
        } else {
            null
        }
    }

    fun place(holder: AppKitViewHolder<*>) {
        check(!closed) { "The AppKit interop host is closed" }
        val added = holders.add(holder)
        holder.isPlaced = true
        if (added || holder.wrapper.superview == null) reorder()
        holder.observeUpdate()
    }

    fun unplace(holder: AppKitViewHolder<*>) {
        if (!holders.remove(holder)) return
        holder.isPlaced = false
        snapshotObserver.clear(holder)
        holder.wrapper.removeFromSuperview()
        reorder()
    }

    fun propertiesChanged(holder: AppKitViewHolder<*>, old: AppKitInteropProperties) {
        kld_macos_interop_wrapper_set_interactive(
            holder.wrapperHandle,
            if (holder.properties.isInteractive) 1 else 0,
        )
        if (old.placement != holder.properties.placement && holder in holders) reorder()
        holder.reapplyLayout()
    }

    fun observeUpdate(holder: AppKitViewHolder<*>, block: () -> Unit) {
        if (!holder.isPlaced || closed) return
        snapshotObserver.observeReads(holder, { changed -> changed.observeUpdate() }, block)
    }

    private fun reorder() {
        if (closed) return
        kld_macos_interop_host_prepare_render_surface(handle)
        val ordered = mutableListOf<AppKitViewHolder<*>>()
        rootNode?.traverseDescendantsInDrawOrder { node ->
            node.holder?.let { holder ->
                if (holder in holders && holder !in ordered) ordered += holder
            }
            true
        }
        // During the first layout pass the root traversal node can be unavailable briefly. Keep
        // placement deterministic until the next traversal rather than dropping the native view.
        holders.forEach { holder -> if (holder !in ordered) ordered += holder }

        reorderContainer(
            backgroundContainer,
            ordered.filter { it.properties.placement == AppKitInteropPlacement.Background },
        )
        reorderContainer(
            overlayContainer,
            ordered.filter { it.properties.placement == AppKitInteropPlacement.Overlay },
        )
        kld_macos_interop_host_sync_z_order(handle)
    }

    private fun reorderContainer(
        container: COpaquePointer,
        ordered: List<AppKitViewHolder<*>>,
    ) {
        var previous: COpaquePointer? = null
        ordered.forEach { holder ->
            kld_macos_interop_container_place_view(container, holder.wrapperHandle, previous)
            previous = holder.wrapperHandle
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        holders.forEach { holder ->
            holder.isPlaced = false
            holder.wrapper.removeFromSuperview()
        }
        holders.clear()
        snapshotObserver.stop()
        rootNode = null
        interopHitTest = null
        kld_macos_interop_host_set_background_hit_test(handle, null, null)
        kld_macos_interop_host_destroy(handle)
        reference.dispose()
    }
}

private val MacosInteropBackgroundHitTest =
    staticCFunction { data: COpaquePointer?, x: Double, y: Double ->
        if (data == null) {
            null
        } else {
            data.asStableRef<MacosNativePlatformViewHost>().get().hitTestBackground(x, y)
        }
    }

private class AppKitViewHolder<T : NSView>(
    val host: MacosNativePlatformViewHost,
    val view: T,
    properties: AppKitInteropProperties,
) {
    val wrapperHandle: COpaquePointer =
        checkNotNull(kld_macos_interop_wrapper_create()) {
            "Could not create the AppKit interop wrapper"
        }
    val wrapper: NSView = interpretObjCPointer(wrapperHandle.rawValue)

    var isPlaced: Boolean = false
    var density: Density = Density(1f)
        set(value) {
            if (field == value) return
            field = value
            reapplyLayout()
        }
    private var disposed = false
    private var updateBlock: (T) -> Unit = {}
    private var lastCoordinates: LayoutCoordinates? = null
    private var lastDensity: Density = Density(1f)

    var properties: AppKitInteropProperties = properties
        set(value) {
            if (field == value) return
            val old = field
            field = value
            host.propertiesChanged(this, old)
        }

    init {
        check(view.superview == null) {
            "AppKitView factory must return a detached NSView"
        }
        wrapper.addSubview(view)
        kld_macos_interop_wrapper_set_interactive(
            wrapperHandle,
            if (properties.isInteractive) 1 else 0,
        )
    }

    fun setUpdate(block: (T) -> Unit) {
        updateBlock = block
        observeUpdate()
    }

    fun observeUpdate() {
        host.observeUpdate(this) {
            updateBlock(view)
            reapplyLayout()
        }
    }

    fun measure(constraints: Constraints, density: Density): IntSize {
        val frameSize =
            view.frame.useContents {
                size.width.coerceAtLeast(0.0) to size.height.coerceAtLeast(0.0)
            }
        val fittingSize = memScoped {
            val width = alloc<DoubleVar>()
            val height = alloc<DoubleVar>()
            kld_macos_interop_wrapper_fitting_size(wrapperHandle, width.ptr, height.ptr)
            width.value to height.value
        }
        val preferredSize =
            IntSize(
                width =
                    ((fittingSize.first.takeIf { it > 0.0 } ?: frameSize.first) * density.density)
                        .roundToInt()
                        .coerceAtLeast(0),
                height =
                    ((fittingSize.second.takeIf { it > 0.0 } ?: frameSize.second) * density.density)
                        .roundToInt()
                        .coerceAtLeast(0),
            )
        val width =
            if (constraints.hasFixedWidth) constraints.maxWidth
            else constraints.constrainWidth(preferredSize.width)
        val height =
            if (constraints.hasFixedHeight) constraints.maxHeight
            else constraints.constrainHeight(preferredSize.height)
        return IntSize(width, height)
    }

    fun layoutAccordingTo(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        lastDensity = density
        applyLayout(coordinates, density)
    }

    fun reapplyLayout() {
        val coordinates = lastCoordinates ?: return
        if (!coordinates.isAttached) return
        applyLayout(coordinates, lastDensity)
    }

    private fun applyLayout(coordinates: LayoutCoordinates, density: Density) {
        if (disposed) return
        val root = coordinates.findRootCoordinates()
        val unclipped =
            root.localBoundingBoxOf(sourceCoordinates = coordinates, clipBounds = false)
        val clipped =
            if (properties.clipToBounds) {
                root.localBoundingBoxOf(sourceCoordinates = coordinates, clipBounds = true)
            } else {
                unclipped
            }
        val scale = density.density.toDouble().coerceAtLeast(0.0001)

        if (clipped.isEmpty || clipped.width <= 0f || clipped.height <= 0f) {
            wrapper.hidden = true
            return
        }
        wrapper.hidden = false
        wrapper.frame = clipped.toNSRect(scale)

        val childRect =
            Rect(
                left = unclipped.left - clipped.left,
                top = unclipped.top - clipped.top,
                right = unclipped.right - clipped.left,
                bottom = unclipped.bottom - clipped.top,
            )
        view.frame = childRect.toNSRect(scale)
    }

    fun dispose(onRelease: () -> Unit) {
        if (disposed) return
        disposed = true
        host.unplace(this)
        view.removeFromSuperview()
        wrapper.removeFromSuperview()
        try {
            onRelease()
        } finally {
            kld_macos_interop_wrapper_destroy(wrapperHandle)
        }
    }
}

private fun Rect.toNSRect(scale: Double) =
    NSMakeRect(
        x = left / scale,
        y = top / scale,
        w = width / scale,
        h = height / scale,
    )

private data class AppKitInteropTraversalElement(
    val host: MacosNativePlatformViewHost,
    val holder: AppKitViewHolder<*>?,
) : ModifierNodeElement<AppKitInteropTraversalNode>() {
    override fun create(): AppKitInteropTraversalNode = AppKitInteropTraversalNode(host, holder)

    override fun update(node: AppKitInteropTraversalNode) {
        node.update(host, holder)
    }
}

private class AppKitInteropTraversalNode(
    private var host: MacosNativePlatformViewHost,
    internal var holder: AppKitViewHolder<*>?,
) : Modifier.Node(), TraversableNode, LayoutAwareModifierNode, UnplacedAwareModifierNode,
    GlobalPositionAwareModifierNode {
    override val traverseKey: Any = AppKitInteropTraversalKey
    private var placed = false

    override fun onAttach() {
        if (holder == null) host.rootNode = this
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        val current = holder ?: return
        host.place(current)
        current.layoutAccordingTo(coordinates)
        placed = true
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val current = holder ?: return
        if (!placed) {
            host.place(current)
            placed = true
        }
        current.layoutAccordingTo(coordinates)
    }

    override fun onUnplaced() {
        holder?.let(host::unplace)
        placed = false
    }

    override fun onDetach() {
        val current = holder
        if (current == null) {
            if (host.rootNode === this) host.rootNode = null
        } else if (placed) {
            host.unplace(current)
            placed = false
        }
        super.onDetach()
    }

    fun update(newHost: MacosNativePlatformViewHost, newHolder: AppKitViewHolder<*>?) {
        if (host === newHost && holder === newHolder) return
        holder?.let { old -> if (placed) host.unplace(old) }
        if (holder == null && host.rootNode === this) host.rootNode = null
        host = newHost
        holder = newHolder
        placed = false
        if (newHolder == null && isAttached) host.rootNode = this
    }
}

private object AppKitInteropTraversalKey

internal actual fun createNativePlatformViewHost(
    window: CPointer<SDL_Window>,
    dispatch: (action: () -> Unit) -> Unit,
): NativePlatformViewHost? {
    val handle = kld_macos_interop_host_create(window) ?: return null
    return MacosNativePlatformViewHost(handle, dispatch)
}
