/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal actual val PlatformClientTitleBarOutsideFullscreen: Boolean = true

internal actual val PlatformKeepsResizableStyleWhenMaximized: Boolean = true

internal actual val PlatformCaptionButtonWidth: Dp = 46.dp

internal actual val PlatformCaptionButtonHeight: Dp = 40.dp

@Composable internal actual fun PlatformTitleBarEndPadding() {}

@OptIn(ExperimentalTextApi::class)
private val WindowsCaptionIconFontFamily =
    FontFamily(SystemFont("Segoe Fluent Icons"), SystemFont("Segoe MDL2 Assets"))

private fun CaptionButtonType.windowsGlyph(): String =
    when (this) {
        CaptionButtonType.Minimize -> "\uE921"
        CaptionButtonType.Maximize -> "\uE922"
        CaptionButtonType.Restore -> "\uE923"
        CaptionButtonType.Close -> "\uE8BB"
    }

@Composable
internal actual fun CaptionButtonIcon(type: CaptionButtonType, color: Color, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        BasicText(
            text = type.windowsGlyph(),
            style =
                TextStyle(
                    color = color,
                    fontFamily = WindowsCaptionIconFontFamily,
                    fontSize = 10.sp,
                ),
        )
    }
}

@Composable
internal actual fun AutoCaptionButtonContent(
    type: CaptionButtonType,
    interaction: MutableInteractionSource,
    foreground: Color,
) {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background =
        when {
            type == CaptionButtonType.Close && (hovered || pressed) -> Color(0xFFC42B1C)
            pressed -> foreground.copy(alpha = 0.12f)
            hovered -> foreground.copy(alpha = 0.08f)
            else -> Color.Transparent
        }
    val glyphColor =
        if (type == CaptionButtonType.Close && (hovered || pressed)) {
            Color.White
        } else {
            foreground
        }
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        CaptionButtonIcon(type = type, color = glyphColor, modifier = Modifier.fillMaxSize())
    }
}
