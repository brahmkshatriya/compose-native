/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.viewinterop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputFilter
import androidx.compose.ui.input.pointer.PointerInputModifier
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny

// TODO: Try to combine with Android's [Modifier.pointerInteropFilter] and commonize

// TODO: Make @InternalComposeUiApi once stabilized.
//  The idea here is to provide ability to utilize [ComposeScene.hitTest] outside of this module.

internal fun Modifier.pointerInteropFilter(interopViewHolder: InteropViewHolder): Modifier =
    this then InteropPointerInputModifier(interopViewHolder)

/**
 * Add an association with [InteropView] to the modified element.
 * Allows hit testing and custom pointer input handling for the [InteropView].
 *
 * @param isInteractive If `true`, the modifier will be applied. If `false`, returns the original modifier.
 * @param interopViewHolder The [InteropViewHolder] to associate with the modified element.
 */
internal fun Modifier.pointerInteropFilter(
    isInteractive: Boolean,
    interopViewHolder: InteropViewHolder
): Modifier =
    if (isInteractive) {
        this.pointerInteropFilter(interopViewHolder)
    } else {
        this
    }

/**
 * Associates an arbitrary native-platform interop target with this Compose node for hit testing.
 *
 * Unlike [pointerInteropFilter], this modifier does not forward Compose pointer events. It is used
 * by hosts where the operating system dispatches input directly to a real native child view after
 * Compose decides that the native view is the topmost interactive target at a position.
 */
@InternalComposeUiApi
public fun Modifier.platformInteropHitTestTarget(
    target: Any,
    isInteractive: Boolean = true,
): Modifier =
    if (isInteractive) {
        this then PlatformInteropHitTestModifier(target)
    } else {
        this
    }

internal class PlatformInteropHitTestModifier(
    val target: Any,
) : PointerInputFilter(), PointerInputModifier {
    override val pointerInputFilter: PointerInputFilter = this

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = Unit

    override fun onCancel() = Unit
}

internal class InteropPointerInputModifier(
    private val interopViewHolder: InteropViewHolder
) : PointerInputFilter(), PointerInputModifier {
    override val pointerInputFilter: PointerInputFilter = this

    val interopView: InteropView
        get() = interopViewHolder.interopView

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) {
        /*
         * If the event was a down or up event, we dispatch to platform as early as possible.
         * If the event is a move event, and we can still intercept, we dispatch to platform after
         * we have a chance to intercept due to movement.
         *
         * See Android's PointerInteropFilter as original source for this logic.
         */
        val dispatchDuringInitialTunnel = pointerEvent.changes.fastAny {
            it.changedToDownIgnoreConsumed() || it.changedToUpIgnoreConsumed()
        }
        if (pass == PointerEventPass.Initial && dispatchDuringInitialTunnel) {
            interopViewHolder.dispatchToView(pointerEvent)
        }
        if (pass == PointerEventPass.Final && !dispatchDuringInitialTunnel) {
            interopViewHolder.dispatchToView(pointerEvent)
        }
    }

    override fun onCancel() {
    }
}
