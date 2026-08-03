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

package androidx.compose.ui.graphics.cairo

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import cairo.kc_create
import cairo.kc_destroy
import cairo.kc_status
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class CairoTextTest {
    private val density = Density(1.25f)
    private val resolver = CairoText.createFontFamilyResolver()

    @Test
    fun maxIntrinsicWidthKeepsLetterSpacedTextOnOneLine() {
        val style = TextStyle(fontSize = 16.sp, letterSpacing = 0.5.sp)

        listOf("Month", "Navigation", "Animations", "WebView").forEach { text ->
            val intrinsics =
                CairoText.createParagraphIntrinsics(
                    text = text,
                    style = style,
                    annotations = emptyList(),
                    placeholders = emptyList(),
                    density = density,
                    fontFamilyResolver = resolver,
                )
            val paragraph =
                CairoText.createParagraph(
                    paragraphIntrinsics = intrinsics,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = ceil(intrinsics.maxIntrinsicWidth).toInt()),
                )

            assertEquals(1, paragraph.lineCount, text)
        }
    }

    @Test
    fun minIntrinsicWidthKeepsSegmentedButtonLabelsOnOneLine() {
        val materialLabelStyle = TextStyle(fontSize = 14.sp, letterSpacing = 0.1.sp)

        listOf("Day", "Week", "Month").forEach { text ->
            val intrinsics =
                CairoText.createParagraphIntrinsics(
                    text = text,
                    style = materialLabelStyle,
                    annotations = emptyList(),
                    placeholders = emptyList(),
                    density = Density(1.5f),
                    fontFamilyResolver = resolver,
                )
            val paragraph =
                CairoText.createParagraph(
                    paragraphIntrinsics = intrinsics,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = ceil(intrinsics.minIntrinsicWidth).toInt()),
                )

            assertEquals(1, paragraph.lineCount, text)
        }
    }

    @Test
    fun maxLinesClipsWithoutRequiringEllipsis() {
        val paragraph =
            CairoText.createParagraph(
                text = "Buttons and controls",
                style = TextStyle(fontSize = 16.sp),
                annotations = emptyList(),
                placeholders = emptyList(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = 60),
                density = density,
                fontFamilyResolver = resolver,
            )

        assertEquals(1, paragraph.lineCount)
        assertTrue(paragraph.didExceedMaxLines)
    }

    @Test
    fun numericInputCursorAndSelectionUseTheTextLineHeight() {
        val paragraph =
            CairoText.createParagraph(
                text = "20260803",
                style = TextStyle(fontSize = 16.sp),
                annotations = emptyList(),
                placeholders = emptyList(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = 400),
                density = Density(1.5f),
                fontFamilyResolver = resolver,
            )

        val cursor = paragraph.getCursorRect(0)
        val firstDigit = paragraph.getBoundingBox(0)
        assertTrue(cursor.height >= 20f, "cursor=$cursor lineHeight=${paragraph.getLineHeight(0)}")
        assertTrue(
            firstDigit.height >= 20f,
            "digit=$firstDigit lineHeight=${paragraph.getLineHeight(0)}",
        )
    }

    @Test
    fun webAddressSelectionUsesTheWholeTextLine() {
        val paragraph =
            CairoText.createParagraph(
                text = "https://www.youtube.com/",
                style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                annotations = emptyList(),
                placeholders = emptyList(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = 800),
                density = Density(1.5f),
                fontFamilyResolver = resolver,
            )

        for (offset in "https://www.youtube.com/".indices) {
            val characterBounds = paragraph.getBoundingBox(offset)
            assertEquals(paragraph.getLineTop(0), characterBounds.top)
            assertEquals(paragraph.getLineBottom(0), characterBounds.bottom)
        }
    }

    @Test
    fun tightHeightConstraintDoesNotCollapseTextLine() {
        val paragraph =
            CairoText.createParagraph(
                text = "2026-08-03",
                style = TextStyle(fontSize = 16.sp),
                annotations = emptyList(),
                placeholders = emptyList(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = 400, maxHeight = 8),
                density = Density(1.5f),
                fontFamilyResolver = resolver,
            )

        assertTrue(paragraph.height >= 20f, "height=${paragraph.height}")
    }

    @Test
    fun zeroScaleDoesNotPoisonCanvas() {
        val surface = CairoSurface(32, 32)
        val context = checkNotNull(kc_create(surface.handle))

        try {
            val canvas = CairoCanvas(context)
            canvas.save()
            canvas.scale(0f, 1f)
            canvas.restore()

            assertEquals(0, kc_status(context))
        } finally {
            kc_destroy(context)
            surface.close()
        }
    }
}
