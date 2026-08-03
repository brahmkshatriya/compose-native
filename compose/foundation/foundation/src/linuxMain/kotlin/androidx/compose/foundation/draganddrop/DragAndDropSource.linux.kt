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

package androidx.compose.foundation.draganddrop

import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Immutable
internal actual object DragAndDropSourceDefaults {
    actual val DefaultStartDetector: DragAndDropStartDetector = {
        detectDragStart(::requestDragAndDropTransfer)
    }
}

private suspend fun PointerInputScope.detectDragStart(onDragStart: (Offset) -> Unit) {
    coroutineScope {
        launch {
            detectDragGestures(
                matcher = PointerMatcher.mouse(PointerButton.Primary),
                onDragStart = onDragStart,
                onDrag = { _ -> },
            )
        }
        launch { detectTapGestures(matcher = PointerMatcher.touch, onLongPress = onDragStart) }
    }
}

internal actual class CacheDrawScopeDragShadowCallback {
    actual fun drawDragShadow(drawScope: DrawScope) = Unit

    actual fun cachePicture(scope: CacheDrawScope): DrawResult =
        with(scope) { onDrawWithContent { drawContent() } }
}
