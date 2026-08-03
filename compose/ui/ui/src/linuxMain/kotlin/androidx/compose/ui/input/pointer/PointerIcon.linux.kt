/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.input.pointer

internal data class LinuxCursor(val name: String) : PointerIcon

internal actual val pointerIconDefault: PointerIcon = LinuxCursor("default")
internal actual val pointerIconCrosshair: PointerIcon = LinuxCursor("crosshair")
internal actual val pointerIconText: PointerIcon = LinuxCursor("text")
internal actual val pointerIconHand: PointerIcon = LinuxCursor("pointer")
