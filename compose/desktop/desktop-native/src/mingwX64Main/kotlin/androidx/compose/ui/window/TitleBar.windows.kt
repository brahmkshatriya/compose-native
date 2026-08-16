/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.window

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal actual val PlatformClientTitleBarOutsideFullscreen: Boolean = false

internal actual val PlatformCaptionButtonWidth: Dp = 46.dp

@Composable internal actual fun PlatformTitleBarEndPadding() {}

// Vector replicas of the Segoe MDL2/Fluent caption glyphs: minimize (E921), maximize (E922),
// restore (E923), and close (E8BB).
@Composable
internal actual fun CaptionButtonIcon(type: CaptionButtonType, color: Color, modifier: Modifier) {
    ComposeCanvas(modifier) {
        val strokeWidth = 1.25.dp.toPx()
        val cap = StrokeCap.Square
        when (type) {
            CaptionButtonType.Minimize ->
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.2f, size.height * 0.68f),
                    end = Offset(size.width * 0.8f, size.height * 0.68f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
            CaptionButtonType.Maximize ->
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
                    size = Size(size.width * 0.56f, size.height * 0.56f),
                    style = Stroke(strokeWidth),
                )
            CaptionButtonType.Restore -> {
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.31f, size.height * 0.18f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    style = Stroke(strokeWidth),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.17f, size.height * 0.32f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    style = Stroke(strokeWidth),
                )
            }
            CaptionButtonType.Close -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.24f, size.height * 0.24f),
                    end = Offset(size.width * 0.76f, size.height * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.76f, size.height * 0.24f),
                    end = Offset(size.width * 0.24f, size.height * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
            }
        }
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
        CaptionButtonIcon(type = type, color = glyphColor, modifier = Modifier.size(16.dp))
    }
}
