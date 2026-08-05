/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.node

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

/**
 * A [Modifier.Node] that draws into the space of the layout.
 *
 * This is the [androidx.compose.ui.Modifier.Node] equivalent of
 * [androidx.compose.ui.draw.DrawModifier]
 *
 * @sample androidx.compose.ui.samples.DrawModifierNodeSample
 */
interface DrawModifierNode : DelegatableNode {
    fun ContentDrawScope.draw()

    fun onMeasureResultChanged() {}
}

/**
 * Optional contract for draw nodes whose visual output extends beyond their measured bounds.
 *
 * The reported [drawInvalidationOutsets] are used only for damage tracking; they do not affect
 * measurement, placement, clipping, hit testing, or semantics.
 */
interface DrawInvalidationOutsetModifierNode {
    val drawInvalidationOutsets: LayerOutsets
}

internal class DrawNodeOwnerScope(internal val drawNode: DrawModifierNode) : OwnerScope {
    override val isValidOwnerScope: Boolean
        get() = drawNode.node.isAttached

    companion object {
        internal val OnDrawReadsChanged: (DrawNodeOwnerScope) -> Unit = { scope ->
            if (scope.isValidOwnerScope) scope.drawNode.invalidateDraw()
        }
    }
}

/**
 * Invalidates this modifier's draw layer, ensuring that a draw pass will be run on the next frame.
 */
fun DrawModifierNode.invalidateDraw() {
    if (node.isAttached) {
        val coordinator = requireCoordinator(Nodes.Any)
        requireOwner().onDrawDamage(invalidationBoundsInRoot(coordinator))
        coordinator.invalidateLayer()
    }
}

private fun DrawModifierNode.invalidationBoundsInRoot(coordinator: NodeCoordinator): Rect {
    val outsets =
        (this as? DrawInvalidationOutsetModifierNode)?.drawInvalidationOutsets
            ?: LayerOutsets.Zero
    if (outsets == LayerOutsets.Zero) return coordinator.boundsInRoot(clipBounds = false)
    val density = requireDensity()
    val left = with(density) { outsets.left.toPx() }
    val top = with(density) { outsets.top.toPx() }
    val right = with(density) { outsets.right.toPx() }
    val bottom = with(density) { outsets.bottom.toPx() }
    val width = coordinator.size.width.toFloat()
    val height = coordinator.size.height.toFloat()
    val points =
        arrayOf(
            coordinator.localToRoot(Offset(-left, -top)),
            coordinator.localToRoot(Offset(width + right, -top)),
            coordinator.localToRoot(Offset(width + right, height + bottom)),
            coordinator.localToRoot(Offset(-left, height + bottom)),
        )
    return Rect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y },
    )
}

/**
 * If the node implements [DrawModifierNode], then this will just call [DrawModifierNode.draw]. if
 * it does NOT implement [DrawModifierNode], it will dispatch draw recursively to any of its direct
 * delegates which DO implement [DrawModifierNode]
 *
 * This can be useful when there is a DelegatingNode which wants to ensure all draw calls are
 * executed of any delegates, but the implementation of the node may not have knowledge of which
 * delegates actually implement [DrawModifierNode].
 */
fun DelegatableNode.dispatchDraw(scope: ContentDrawScope) {
    node.dispatchForKind(Nodes.Draw) { with(it) { with(scope) { draw() } } }
}
