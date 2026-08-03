/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.ui.semantics

import androidx.compose.ui.unit.IntRect

private class SemanticRegionImpl : SemanticsRegion {
    private var rect: IntRect? = null

    override fun set(rect: IntRect) {
        this.rect = if (rect.left < rect.right && rect.top < rect.bottom) rect else null
    }

    override val bounds: IntRect
        get() = rect ?: IntRect.Zero

    override val isEmpty: Boolean
        get() = rect == null

    override fun intersect(region: SemanticsRegion): Boolean {
        val a = rect ?: return false
        val b =
            (region as SemanticRegionImpl).rect
                ?: run {
                    rect = null
                    return false
                }
        set(
            IntRect(
                left = maxOf(a.left, b.left),
                top = maxOf(a.top, b.top),
                right = minOf(a.right, b.right),
                bottom = minOf(a.bottom, b.bottom),
            )
        )
        return rect != null
    }

    override fun difference(rect: IntRect): Boolean {
        val current = this.rect ?: return false
        if (
            rect.left <= current.left &&
                rect.top <= current.top &&
                rect.right >= current.right &&
                rect.bottom >= current.bottom
        ) {
            this.rect = null
            return false
        }
        return true
    }
}

internal actual fun SemanticsRegion(): SemanticsRegion = SemanticRegionImpl()
