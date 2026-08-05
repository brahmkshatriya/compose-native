/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.input.key

/** Represents a key combination which triggers a menu action. */
class KeyShortcut(
    internal val key: Key,
    internal val ctrl: Boolean = false,
    internal val meta: Boolean = false,
    internal val alt: Boolean = false,
    internal val shift: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is KeyShortcut &&
            key == other.key &&
            ctrl == other.ctrl &&
            meta == other.meta &&
            alt == other.alt &&
            shift == other.shift

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + ctrl.hashCode()
        result = 31 * result + meta.hashCode()
        result = 31 * result + alt.hashCode()
        result = 31 * result + shift.hashCode()
        return result
    }

    override fun toString(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (meta) append("Meta+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key)
    }
}
