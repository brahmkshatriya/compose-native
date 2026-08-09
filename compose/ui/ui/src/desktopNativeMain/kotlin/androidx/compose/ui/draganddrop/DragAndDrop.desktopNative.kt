/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.draganddrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset

actual class DragAndDropEvent
@ExperimentalComposeUiApi
constructor(
    internal val offset: Offset,
    @property:ExperimentalComposeUiApi val transferData: DragAndDropTransferData?,
)

internal actual val DragAndDropEvent.positionInRoot: Offset
    get() = offset

actual class DragAndDropTransferData
@ExperimentalComposeUiApi
constructor(
    @property:ExperimentalComposeUiApi val files: List<String> = emptyList(),
    @property:ExperimentalComposeUiApi val text: String? = null,
)
