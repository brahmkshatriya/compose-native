/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package androidx.compose.ui.window

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowPlatformFeaturesTest {
    @Test
    fun clientFrameInsetsLiveOutsideFloatingWindowContent() {
        val insets = clientFrameInsets(drawingInsideTitleBar = true, WindowPlacement.Floating)

        assertEquals(ClientFrameInsets(6, 6, 6, 6), insets)
        assertEquals(800, insets.contentWidth(812))
        assertEquals(600, insets.contentHeight(612))
        assertEquals(806, insets.contentX(812))
        assertEquals(606, insets.contentY(612))

        assertEquals(
            ClientFrameInsets.Zero,
            clientFrameInsets(drawingInsideTitleBar = true, WindowPlacement.Maximized),
        )
        assertEquals(
            ClientFrameInsets.Zero,
            clientFrameInsets(drawingInsideTitleBar = true, WindowPlacement.Fullscreen),
        )
        assertEquals(
            ClientFrameInsets.Zero,
            clientFrameInsets(drawingInsideTitleBar = false, WindowPlacement.Floating),
        )
    }

    @Test
    fun titleBarInsetIsOnlyPresentForExtendedNonFullscreenWindows() {
        assertEquals(
            54,
            titleBarInsetHeightPx(
                drawingInsideTitleBar = true,
                placement = WindowPlacement.Floating,
                titleBarHeightPx = 54,
            ),
        )
        assertEquals(
            0,
            titleBarInsetHeightPx(
                drawingInsideTitleBar = false,
                placement = WindowPlacement.Floating,
                titleBarHeightPx = 54,
            ),
        )
        assertEquals(
            0,
            titleBarInsetHeightPx(
                drawingInsideTitleBar = true,
                placement = WindowPlacement.Fullscreen,
                titleBarHeightPx = 54,
            ),
        )
    }

    @Test
    fun onlyFloatingPlacementPersistsWindowGeometry() {
        assertTrue(WindowPlacement.Floating.persistsFloatingGeometry())
        assertFalse(WindowPlacement.Maximized.persistsFloatingGeometry())
        assertFalse(WindowPlacement.Fullscreen.persistsFloatingGeometry())
    }

    @Test
    fun maximizedWindowRetainsNativeResizableStyle() {
        assertTrue(nativeResizeStyleEnabled(resizable = true, WindowPlacement.Floating))
        assertTrue(nativeResizeStyleEnabled(resizable = true, WindowPlacement.Maximized))
        assertFalse(nativeResizeStyleEnabled(resizable = true, WindowPlacement.Fullscreen))
        assertFalse(nativeResizeStyleEnabled(resizable = false, WindowPlacement.Maximized))
    }

    @Test
    fun mapsPortalColorSchemeToDarkPreference() {
        assertNull(portalColorSchemePrefersDark(0))
        assertTrue(portalColorSchemePrefersDark(1) == true)
        assertFalse(portalColorSchemePrefersDark(2) == true)
        assertNull(portalColorSchemePrefersDark(99))
    }

    @Test
    fun mapsPortalAccentColorToComposeColor() {
        assertNull(portalAccentColor(0))
        assertEquals(Color(0xFF000000), portalAccentColor(0x01000000))
        assertEquals(Color(0xFF336699), portalAccentColor(0x01336699))
    }

    @Test
    fun rasterizesPainterForWindowIcon() {
        registerSkikoComposeImplementation()
        val image = rasterizeWindowIcon(ColorPainter(Color.Red), size = 8)
        try {
            val pixels = image.toPixelMap()
            assertEquals(8, image.width)
            assertEquals(8, image.height)
            assertEquals(Color.Red, pixels[4, 4])
        } finally {
            image.close()
        }
    }

    @Test
    fun modelessDialogNeverBlocksInput() {
        assertFalse(
            modalityBlocksInput(
                DialogModalityType.Modeless,
                modalActive = true,
                sameDocument = true,
                targetIsModalOrDescendant = false,
            )
        )
    }

    @Test
    fun documentModalBlocksOnlyItsDocument() {
        assertTrue(
            modalityBlocksInput(
                DialogModalityType.DocumentModal,
                modalActive = true,
                sameDocument = true,
                targetIsModalOrDescendant = false,
            )
        )
        assertFalse(
            modalityBlocksInput(
                DialogModalityType.DocumentModal,
                modalActive = true,
                sameDocument = false,
                targetIsModalOrDescendant = false,
            )
        )
    }

    @Test
    fun applicationModalBlocksOtherDocumentsButNotDescendants() {
        assertTrue(
            modalityBlocksInput(
                DialogModalityType.ApplicationModal,
                modalActive = true,
                sameDocument = false,
                targetIsModalOrDescendant = false,
            )
        )
        assertFalse(
            modalityBlocksInput(
                DialogModalityType.ApplicationModal,
                modalActive = true,
                sameDocument = false,
                targetIsModalOrDescendant = true,
            )
        )
    }

    @Test
    fun menuShortcutRequiresExactModifiersAndInvokesCurrentAction() {
        var activations = 0
        val model =
            NativeMenuModel(
                listOf(
                    NativeMenuEntry.Menu(
                        id = 1,
                        text = "File",
                        enabled = true,
                        mnemonic = null,
                        children =
                            listOf(
                                NativeMenuEntry.Item(
                                    id = 2,
                                    text = "Save",
                                    icon = null,
                                    enabled = true,
                                    mnemonic = null,
                                    shortcut = KeyShortcut(Key.S, ctrl = true),
                                    checked = null,
                                    radio = false,
                                    action = { activations++ },
                                )
                            ),
                    )
                )
            )

        assertFalse(model.activateShortcut(KeyEvent(Key.S, KeyEventType.KeyDown)))
        assertFalse(
            model.activateShortcut(
                KeyEvent(Key.S, KeyEventType.KeyDown, isCtrlPressed = true, isShiftPressed = true)
            )
        )
        assertTrue(
            model.activateShortcut(KeyEvent(Key.S, KeyEventType.KeyDown, isCtrlPressed = true))
        )
        assertEquals(1, activations)
    }

    @Test
    fun menuPresentationSignatureTracksCheckStateButNotActionIdentity() {
        fun model(checked: Boolean, action: () -> Unit) =
            NativeMenuModel(
                listOf(
                    NativeMenuEntry.Item(
                        id = 1,
                        text = "Enabled",
                        icon = null,
                        enabled = true,
                        mnemonic = null,
                        shortcut = null,
                        checked = checked,
                        radio = false,
                        action = action,
                    )
                )
            )

        assertEquals(
            model(checked = true) {}.presentationSignature,
            model(checked = true) { error("different action") }.presentationSignature,
        )
        assertTrue(
            model(checked = true) {}.presentationSignature !=
                model(checked = false) {}.presentationSignature
        )
    }
}
