/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package androidx.compose.ui.window

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.isUiSystemInDarkTheme
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The window caption control being drawn by a [TitleBar]. */
enum class CaptionButtonType {
    /** Minimizes the window. */
    Minimize,

    /** Maximizes the window. */
    Maximize,

    /** Restores a maximized window. */
    Restore,

    /** Closes the window. */
    Close,
}

/**
 * Controls how the window title bar is presented.
 * - [Native] keeps the system title bar.
 * - [Auto] extends the client area underneath the title bar and draws the caption controls with the
 *   platform default style (KDE-style icons on Linux, native-style icons on Windows).
 * - [Custom] extends the client area underneath the title bar and draws the caption controls with a
 *   caller-provided button style, see [rememberTitleBar].
 *
 * The window itself never draws title text or a title bar background. In fullscreen, the caption
 * controls are hidden and slide down while the pointer hovers over the top edge of the window.
 */
sealed interface TitleBar {
    /**
     * The color used for the caption glyphs, or [Color.Unspecified] to follow the system theme
     * (white in dark mode, black in light mode).
     */
    val foreground: Color

    /** Keep the native system title bar. */
    object Native : TitleBar {
        override val foreground: Color
            get() = Color.Unspecified
    }

    /** Compose-drawn caption controls with the platform default style. */
    @Immutable data class Auto(override val foreground: Color = Color.Unspecified) : TitleBar

    /**
     * Compose-drawn caption controls with a caller-provided button style.
     *
     * Prefer [rememberTitleBar], which resolves the defaults and keeps the value stable across
     * recompositions.
     *
     * @param foreground the color used for the caption glyphs, or [Color.Unspecified] to follow the
     *   system theme.
     * @param iconContainer draws one caption button. The container defines the button's width and
     *   height, so it should apply its own size modifier; the framework wraps it in a clickable,
     *   semantics-labeled area. [type] identifies which caption control is being drawn,
     *   [interaction] reports the hover and press state of the button, and [icon] draws the
     *   platform caption glyph in the color chosen for this button, filling the space the container
     *   gives it.
     */
    data class Custom(
        override val foreground: Color = Color.Unspecified,
        val iconContainer:
            @Composable
            (
                type: CaptionButtonType,
                interaction: MutableInteractionSource,
                icon: @Composable () -> Unit,
            ) -> Unit,
    ) : TitleBar
}

/**
 * Creates a custom [TitleBar.Custom] that draws the caption controls with a caller-provided button
 * style.
 *
 * The returned value is stable across recompositions as long as [foreground] and [iconContainer] do
 * not change.
 *
 * @param foreground the color used for the caption glyphs, or [Color.Unspecified] to follow the
 *   system theme.
 * @param iconContainer draws one caption button. The container defines the button's width and
 *   height, so it should apply its own size modifier; the framework wraps it in a clickable,
 *   semantics-labeled area. [type] identifies which caption control is being drawn, [interaction]
 *   reports the hover and press state of the button, and [icon] draws the platform caption glyph in
 *   the color chosen for this button, filling the space the container gives it.
 */
@Composable
fun rememberTitleBar(
    foreground: Color = Color.Unspecified,
    iconContainer:
        @Composable
        (
            type: CaptionButtonType,
            interaction: MutableInteractionSource,
            icon: @Composable () -> Unit,
        ) -> Unit,
): TitleBar {
    val resolvedForeground = if (foreground.isSpecified) foreground else defaultTitleBarForeground()
    return remember(resolvedForeground, iconContainer) {
        TitleBar.Custom(resolvedForeground, iconContainer)
    }
}

/**
 * The glyph color chosen for a [TitleBar], resolving [Color.Unspecified] against the system theme.
 */
@Composable
internal fun resolveTitleBarForeground(titleBar: TitleBar): Color =
    if (titleBar.foreground.isSpecified) titleBar.foreground else defaultTitleBarForeground()

@Composable
internal fun defaultTitleBarForeground(): Color =
    if (isUiSystemInDarkTheme()) Color.White else Color.Black

/** Whether the client title bar is drawn outside fullscreen on this platform. */
internal expect val PlatformClientTitleBarOutsideFullscreen: Boolean

/** Whether a maximized window retains its resizable style for native caption commands. */
internal expect val PlatformKeepsResizableStyleWhenMaximized: Boolean

/** The width of the clickable caption-button hit area on this platform. */
internal expect val PlatformCaptionButtonWidth: Dp

/** The height of the clickable caption-button hit area on this platform. */
internal expect val PlatformCaptionButtonHeight: Dp

/** Draws the platform-specific content after the last caption button. */
@Composable internal expect fun PlatformTitleBarEndPadding()

/** Draws the caption glyph for [type] on this platform. */
@Composable
internal expect fun CaptionButtonIcon(
    type: CaptionButtonType,
    color: Color,
    modifier: Modifier = Modifier,
)

/** Draws the [TitleBar.Auto] button visuals for [type] on this platform. */
@Composable
internal expect fun AutoCaptionButtonContent(
    type: CaptionButtonType,
    interaction: MutableInteractionSource,
    foreground: Color,
)

/**
 * The row of caption controls used by the client title bar. It spans the full window width and
 * provides a draggable strip, followed by the minimize, maximize/restore, and close buttons.
 */
@Composable
internal fun FrameWindowScope.ClientTitleBar(
    state: WindowState,
    resizable: Boolean,
    titleBar: TitleBar,
    onCloseRequest: () -> Unit,
) {
    val foreground = resolveTitleBarForeground(titleBar)
    val titleBarHeight =
        if (titleBar is TitleBar.Custom) {
            window.host.titleBarHeightDp()
        } else {
            maxOf(window.host.titleBarHeightDp(), PlatformCaptionButtonHeight)
        }
    Row(
        Modifier.fillMaxWidth().height(titleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {}
        CaptionButton(
            type = CaptionButtonType.Minimize,
            titleBar = titleBar,
            foreground = foreground,
            enabled = true,
            onClick = { window.minimize() },
        )
        CaptionButton(
            type =
                if (state.placement == WindowPlacement.Maximized) {
                    CaptionButtonType.Restore
                } else {
                    CaptionButtonType.Maximize
                },
            titleBar = titleBar,
            foreground = foreground,
            enabled = resizable,
            onClick = { window.toggleMaximized() },
        )
        CaptionButton(
            type = CaptionButtonType.Close,
            titleBar = titleBar,
            foreground = foreground,
            enabled = true,
            onClick = onCloseRequest,
        )
        // Trailing spacing is part of the platform-default style; custom buttons define their
        // own geometry, so no framework padding is added after them.
        if (titleBar !is TitleBar.Custom) {
            PlatformTitleBarEndPadding()
        }
    }
}

@Composable
private fun FrameWindowScope.CaptionButton(
    type: CaptionButtonType,
    titleBar: TitleBar,
    foreground: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description =
        when (type) {
            CaptionButtonType.Minimize -> "Minimize"
            CaptionButtonType.Maximize -> "Maximize"
            CaptionButtonType.Restore -> "Restore"
            CaptionButtonType.Close -> "Close"
        }
    val interaction = remember { MutableInteractionSource() }
    val iconColor = if (enabled) foreground else foreground.copy(alpha = 0.38f)
    // Custom buttons define their own width and height; only the platform-default (Auto) buttons
    // use the fixed platform hit area. Both are centered inside the title bar row.
    val sizeModifier =
        if (titleBar is TitleBar.Custom) {
            Modifier
        } else {
            Modifier.width(PlatformCaptionButtonWidth).height(PlatformCaptionButtonHeight)
        }
    DisposableEffect(window, type) {
        onDispose { window.host.updateCaptionButtonBounds(type, null) }
    }
    val measuredSizeModifier =
        sizeModifier.onGloballyPositioned {
            window.host.updateCaptionButtonBounds(type, it.boundsInRoot())
        }
    Box(
        measuredSizeModifier
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (titleBar) {
            is TitleBar.Native -> Unit
            is TitleBar.Auto ->
                AutoCaptionButtonContent(
                    type = type,
                    interaction = interaction,
                    foreground = iconColor,
                )
            is TitleBar.Custom ->
                titleBar.iconContainer(type, interaction) {
                    CaptionButtonIcon(
                        type = type,
                        color = iconColor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
        }
    }
}

/**
 * The caption controls overlay for fullscreen windows. The controls are hidden by default and slide
 * down while the pointer hovers over the top reveal strip, sliding back up once the pointer leaves
 * it. The captionBar/systemBars top inset is animated in lockstep so content that pads for window
 * insets shifts together with the controls.
 */
@Composable
internal fun FrameWindowScope.FullscreenClientTitleBarReveal(
    state: WindowState,
    resizable: Boolean,
    titleBar: TitleBar,
    onCloseRequest: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isRevealed by hoverInteraction.collectIsHoveredAsState()
    val revealedInsetPx = window.host.titleBarHeightPx()
    LaunchedEffect(isRevealed, revealedInsetPx) {
        // The value is pushed to the platform on every animation frame directly from the
        // animation coroutine: a SideEffect reading the animated state would not be invalidated
        // by the animation, because reads inside its lambda are not tracked by the composition.
        animate(
            initialValue = window.host.titleBarInsetPx.toFloat(),
            targetValue = if (isRevealed) revealedInsetPx.toFloat() else 0f,
            animationSpec = tween(durationMillis = 220),
        ) { value, _ ->
            window.host.updateTitleBarInsetPx(value.roundToInt())
        }
    }
    Column(Modifier.fillMaxWidth().wrapContentHeight().hoverable(hoverInteraction)) {
        AnimatedVisibility(visible = isRevealed, modifier = Modifier.fillMaxWidth()) {
            ClientTitleBar(
                state = state,
                resizable = resizable,
                titleBar = titleBar,
                onCloseRequest = onCloseRequest,
            )
        }
        Spacer(Modifier.fillMaxWidth().height(16.dp))
    }
}
