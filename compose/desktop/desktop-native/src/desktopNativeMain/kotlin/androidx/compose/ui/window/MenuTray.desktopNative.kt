/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.paint
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal sealed interface NativeMenuEntry {
    val id: Int
    val enabled: Boolean

    data class Menu(
        override val id: Int,
        val text: String,
        override val enabled: Boolean,
        val mnemonic: Char?,
        val children: List<NativeMenuEntry>,
    ) : NativeMenuEntry

    data class Item(
        override val id: Int,
        val text: String,
        val icon: Painter?,
        override val enabled: Boolean,
        val mnemonic: Char?,
        val shortcut: KeyShortcut?,
        val checked: Boolean?,
        val radio: Boolean,
        val action: () -> Unit,
    ) : NativeMenuEntry

    data class Separator(override val id: Int) : NativeMenuEntry {
        override val enabled: Boolean = false
    }
}

internal data class NativeMenuModel(val entries: List<NativeMenuEntry>) {
    val presentationSignature: Int
        get() = entries.fold(1) { acc, entry -> 31 * acc + entry.presentationHash() }

    fun activateShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val item = entries.firstNotNullOfOrNull { it.findShortcut(event) } ?: return false
        item.action()
        return true
    }

    companion object {
        val Empty = NativeMenuModel(emptyList())
    }
}

private fun NativeMenuEntry.presentationHash(): Int =
    when (this) {
        is NativeMenuEntry.Separator -> 17
        is NativeMenuEntry.Menu -> {
            var result = text.hashCode()
            result = 31 * result + enabled.hashCode()
            result = 31 * result + (mnemonic?.hashCode() ?: 0)
            result = 31 * result + children.fold(1) { acc, child -> 31 * acc + child.presentationHash() }
            result
        }
        is NativeMenuEntry.Item -> {
            var result = text.hashCode()
            result = 31 * result + enabled.hashCode()
            result = 31 * result + (checked?.hashCode() ?: 0)
            result = 31 * result + radio.hashCode()
            result = 31 * result + (shortcut?.hashCode() ?: 0)
            result = 31 * result + (icon?.hashCode() ?: 0)
            result
        }
    }

private fun NativeMenuEntry.findShortcut(event: KeyEvent): NativeMenuEntry.Item? =
    when (this) {
        is NativeMenuEntry.Menu -> children.firstNotNullOfOrNull { it.findShortcut(event) }
        is NativeMenuEntry.Item ->
            takeIf { item ->
                item.enabled && item.shortcut?.let { shortcut ->
                    shortcut.key == event.key &&
                        shortcut.ctrl == event.isCtrlPressed &&
                        shortcut.meta == event.isMetaPressed &&
                        shortcut.alt == event.isAltPressed &&
                        shortcut.shift == event.isShiftPressed
                } == true
            }
        is NativeMenuEntry.Separator -> null
    }

internal class NativeMenuBuilder(private val ids: MenuIdAllocator = MenuIdAllocator()) {
    val entries = mutableListOf<NativeMenuEntry>()

    fun nextId(): Int = ids.next()

    fun child(): NativeMenuBuilder = NativeMenuBuilder(ids)

    fun build(): NativeMenuModel = NativeMenuModel(entries.toList())
}

internal class MenuIdAllocator {
    private var next = 1
    fun next(): Int = next++
}

class MenuBarScope internal constructor(private val builder: NativeMenuBuilder) {
    @Composable
    @MenuComposable
    fun Menu(
        text: String,
        mnemonic: Char? = null,
        enabled: Boolean = true,
        content: @Composable @MenuComposable MenuScope.() -> Unit,
    ) {
        val children = builder.child()
        MenuScope(children).content()
        builder.entries +=
            NativeMenuEntry.Menu(builder.nextId(), text, enabled, mnemonic, children.entries.toList())
    }
}

class MenuScope internal constructor(private val builder: NativeMenuBuilder) {
    @Composable
    @MenuComposable
    fun Menu(
        text: String,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        content: @Composable @MenuComposable MenuScope.() -> Unit,
    ) {
        val children = builder.child()
        MenuScope(children).content()
        builder.entries +=
            NativeMenuEntry.Menu(builder.nextId(), text, enabled, mnemonic, children.entries.toList())
    }

    @Composable
    @MenuComposable
    fun Separator() {
        builder.entries += NativeMenuEntry.Separator(builder.nextId())
    }

    @Composable
    @MenuComposable
    fun Item(
        text: String,
        icon: Painter? = null,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit,
    ) {
        builder.entries +=
            NativeMenuEntry.Item(
                builder.nextId(), text, icon, enabled, mnemonic, shortcut, null, false, onClick
            )
    }

    @Composable
    @MenuComposable
    fun CheckboxItem(
        text: String,
        checked: Boolean,
        icon: Painter? = null,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: KeyShortcut? = null,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        builder.entries +=
            NativeMenuEntry.Item(
                builder.nextId(), text, icon, enabled, mnemonic, shortcut, checked, false
            ) { onCheckedChange(!checked) }
    }

    @Composable
    @MenuComposable
    fun RadioButtonItem(
        text: String,
        selected: Boolean,
        icon: Painter? = null,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: KeyShortcut? = null,
        onClick: () -> Unit,
    ) {
        builder.entries +=
            NativeMenuEntry.Item(
                builder.nextId(), text, icon, enabled, mnemonic, shortcut, selected, true, onClick
            )
    }
}

@Composable
fun FrameWindowScope.MenuBar(
    content: @Composable @MenuComposable MenuBarScope.() -> Unit,
) {
    val builder = NativeMenuBuilder()
    MenuBarScope(builder).content()
    val model = builder.build()
    SideEffect { window.host.updateMenuBar(model) }
    DisposableEffect(window.host) {
        onDispose { window.host.updateMenuBar(NativeMenuModel.Empty) }
    }
}

private val MenuBarBackground = Color(0xff242227)
private val MenuPopupBackground = Color(0xff302d33)
private val MenuHoverBackground = Color(0xff4b4552)
private val MenuForeground = Color(0xfff0eaf4)
private val MenuDisabled = Color(0xff908994)
private val MenuTextStyle = TextStyle(fontSize = 14.sp)

@Composable
internal fun NativeWindowMenuBar(model: NativeMenuModel) {
    if (model.entries.isEmpty()) return
    val menuBarHeightPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    var openMenuId by remember { mutableIntStateOf(0) }
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(MenuBarBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        model.entries.filterIsInstance<NativeMenuEntry.Menu>().forEach { menu ->
            Box {
                BasicText(
                    text = menu.text,
                    modifier =
                        Modifier
                            .clickable(enabled = menu.enabled) {
                                openMenuId = if (openMenuId == menu.id) 0 else menu.id
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MenuTextStyle.copy(
                        color = if (menu.enabled) MenuForeground else MenuDisabled
                    ),
                )
                if (openMenuId == menu.id) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, menuBarHeightPx),
                        onDismissRequest = { openMenuId = 0 },
                    ) {
                        NativeMenuPopup(menu.children) { openMenuId = 0 }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeMenuPopup(entries: List<NativeMenuEntry>, closeAll: () -> Unit) {
    Column(
        Modifier.widthIn(min = 190.dp).background(MenuPopupBackground).padding(vertical = 4.dp)
    ) {
        entries.forEach { entry -> NativeMenuEntryView(entry, closeAll) }
    }
}

@Composable
private fun NativeMenuEntryView(entry: NativeMenuEntry, closeAll: () -> Unit) {
    val nestedMenuOffsetPx = with(LocalDensity.current) { 190.dp.roundToPx() }
    when (entry) {
        is NativeMenuEntry.Separator ->
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(MenuHoverBackground))
        is NativeMenuEntry.Menu -> {
            var expanded by remember(entry.id) { mutableStateOf(false) }
            Box {
                NativeMenuRow(
                    text = entry.text,
                    prefix = "",
                    suffix = "›",
                    icon = null,
                    enabled = entry.enabled,
                    onClick = { expanded = !expanded },
                )
                if (expanded) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(nestedMenuOffsetPx, 0),
                        onDismissRequest = { expanded = false },
                    ) {
                        NativeMenuPopup(entry.children, closeAll)
                    }
                }
            }
        }
        is NativeMenuEntry.Item -> {
            val prefix =
                when {
                    entry.checked == null -> ""
                    entry.radio && entry.checked -> "●"
                    entry.radio -> "○"
                    entry.checked -> "✓"
                    else -> ""
                }
            NativeMenuRow(
                text = entry.text,
                prefix = prefix,
                suffix = entry.shortcut?.toString().orEmpty(),
                icon = entry.icon,
                enabled = entry.enabled,
                onClick = {
                    entry.action()
                    closeAll()
                },
            )
        }
    }
}

@Composable
private fun NativeMenuRow(
    text: String,
    prefix: String,
    suffix: String,
    icon: Painter?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(prefix, Modifier.width(18.dp), MenuTextStyle.copy(color = MenuForeground))
        if (icon != null) {
            Box(Modifier.size(16.dp).paint(icon))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(
            text,
            Modifier.weight(1f),
            MenuTextStyle.copy(color = if (enabled) MenuForeground else MenuDisabled),
        )
        if (suffix.isNotEmpty()) {
            Spacer(Modifier.width(16.dp))
            BasicText(suffix, style = MenuTextStyle.copy(color = MenuDisabled))
        }
    }
}
