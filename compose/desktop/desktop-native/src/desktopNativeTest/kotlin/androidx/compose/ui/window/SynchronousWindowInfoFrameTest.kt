/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.compose.ui.window

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.FrameRecomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers

@OptIn(InternalComposeUiApi::class)
class SynchronousWindowInfoFrameTest {
    @Test
    fun synchronousFrameAppliesWindowInfoStateChange() {
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val composition = Composition(UnitApplier(), recomposer.compositionContext)
        val windowWidth = mutableStateOf(800)
        var observedWidth = 0
        try {
            composition.setContent { observedWidth = windowWidth.value }
            assertEquals(800, observedWidth)

            windowWidth.value = 560
            assertEquals(800, observedWidth)

            recomposer.performFrame(0L)

            assertEquals(560, observedWidth)
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }
}

private class UnitApplier : Applier<Unit> {
    override val current: Unit = Unit

    override fun down(node: Unit) = Unit

    override fun up() = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun clear() = Unit
}
