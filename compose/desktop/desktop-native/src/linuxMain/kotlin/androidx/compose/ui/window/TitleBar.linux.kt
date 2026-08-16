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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal actual val PlatformClientTitleBarOutsideFullscreen: Boolean = true

internal actual val PlatformCaptionButtonWidth: Dp = 28.dp

@Composable
internal actual fun PlatformTitleBarEndPadding() {
    Spacer(Modifier.width(4.dp))
}

// Directly drawn replicas of the KDE Breeze caption glyphs: a down chevron for minimize, an up
// chevron for maximize, a diamond for restore, and a cross for close.
@Composable
internal actual fun CaptionButtonIcon(type: CaptionButtonType, color: Color, modifier: Modifier) {
    ComposeCanvas(modifier) {
        val strokeWidth = 1.25.dp.toPx()
        val cap = StrokeCap.Square
        when (type) {
            CaptionButtonType.Minimize -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.17f, size.height * 0.31f),
                    end = Offset(size.width * 0.5f, size.height * 0.64f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.83f, size.height * 0.31f),
                    end = Offset(size.width * 0.5f, size.height * 0.64f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
            }
            CaptionButtonType.Maximize -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.17f, size.height * 0.69f),
                    end = Offset(size.width * 0.5f, size.height * 0.36f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.83f, size.height * 0.69f),
                    end = Offset(size.width * 0.5f, size.height * 0.36f),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
            }
            CaptionButtonType.Restore -> {
                val diamond =
                    Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.14f)
                        lineTo(size.width * 0.86f, size.height * 0.5f)
                        lineTo(size.width * 0.5f, size.height * 0.86f)
                        lineTo(size.width * 0.14f, size.height * 0.5f)
                        close()
                    }
                drawPath(diamond, color, style = Stroke(strokeWidth))
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
            type == CaptionButtonType.Close && pressed -> Color(0xFFA01E17)
            type == CaptionButtonType.Close && hovered -> Color(0xFFC42B1C)
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
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        CaptionButtonIcon(type = type, color = glyphColor, modifier = Modifier.size(16.dp))
    }
}
