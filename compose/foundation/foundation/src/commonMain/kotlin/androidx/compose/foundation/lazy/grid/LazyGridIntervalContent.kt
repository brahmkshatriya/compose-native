/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.foundation.lazy.grid

import androidx.collection.MutableIntList
import androidx.collection.mutableIntListOf
import androidx.compose.foundation.lazy.layout.EmptyStickyItems
import androidx.compose.foundation.lazy.layout.LazyLayoutIntervalContent
import androidx.compose.foundation.lazy.layout.MutableIntervalList
import androidx.compose.foundation.lazy.layout.StickyItems
import androidx.compose.runtime.Composable

internal class LazyGridIntervalContent(content: LazyGridScope.() -> Unit) :
    LazyGridScope, LazyLayoutIntervalContent<LazyGridInterval>() {
    internal val spanLayoutProvider: LazyGridSpanLayoutProvider = LazyGridSpanLayoutProvider(this)

    override val intervals = MutableIntervalList<LazyGridInterval>()

    internal var hasCustomSpans = false

    private var _headerIndexes: MutableIntList? = null
    private var _nonSlidingHeaderIndexes: MutableIntList? = null
    private var _stickyItems: StickyItems? = null

    val stickyItems: StickyItems
        get() = _stickyItems ?: EmptyStickyItems

    init {
        apply(content)
    }

    private fun getOrCreateHeaderIndexes(): MutableIntList {
        return _headerIndexes
            ?: mutableIntListOf().also { headerIndexes ->
                val nonSlidingHeaderIndexes = mutableIntListOf()
                _headerIndexes = headerIndexes
                _nonSlidingHeaderIndexes = nonSlidingHeaderIndexes
                _stickyItems = StickyItems(headerIndexes, nonSlidingHeaderIndexes)
            }
    }

    override fun item(
        key: Any?,
        span: (LazyGridItemSpanScope.() -> GridItemSpan)?,
        contentType: Any?,
        content: @Composable LazyGridItemScope.() -> Unit,
    ) {
        intervals.addInterval(
            1,
            LazyGridInterval(
                key = key?.let { { key } },
                span = span?.let { { span() } } ?: DefaultSpan,
                type = { contentType },
                item = { content() },
            ),
        )
        if (span != null) hasCustomSpans = true
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        span: (LazyGridItemSpanScope.(Int) -> GridItemSpan)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyGridItemScope.(index: Int) -> Unit,
    ) {
        intervals.addInterval(
            count,
            LazyGridInterval(
                key = key,
                span = span ?: DefaultSpan,
                type = contentType,
                item = itemContent,
            ),
        )
        if (span != null) hasCustomSpans = true
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyGridItemScope.(Int) -> Unit,
    ) = stickyHeader(key, contentType, true, content)

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        isSlidable: Boolean,
        content: @Composable LazyGridItemScope.(Int) -> Unit,
    ) {
        val headerIndexes = getOrCreateHeaderIndexes()
        val headerIndex = intervals.size
        headerIndexes.add(headerIndex)
        if (!isSlidable) {
            checkNotNull(_nonSlidingHeaderIndexes).add(headerIndex)
        }
        item(key, { GridItemSpan(maxLineSpan) }, contentType) { content.invoke(this, headerIndex) }
    }

    private companion object {
        val DefaultSpan: LazyGridItemSpanScope.(Int) -> GridItemSpan = { GridItemSpan(1) }
    }
}

internal class LazyGridInterval(
    override val key: ((index: Int) -> Any)?,
    val span: LazyGridItemSpanScope.(Int) -> GridItemSpan,
    override val type: ((index: Int) -> Any?),
    val item: @Composable LazyGridItemScope.(Int) -> Unit,
) : LazyLayoutIntervalContent.Interval
