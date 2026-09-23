@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalPlatformAccentColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.isOpenGlInteropAvailable
import androidx.compose.ui.window.CaptionButtonType
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.NotificationAction
import androidx.compose.ui.window.NotificationRequest
import androidx.compose.ui.window.TitleBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberTitleBar
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.sendNotification
import kotlinx.cinterop.toKString
import kotlinx.coroutines.launch
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

private const val MacosPlatformName = "macOS SDL Native"

private object MacosCatalogueIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        drawRoundRect(
            color = Color(0xff6750a4),
            cornerRadius = CornerRadius(size.minDimension * .22f),
        )
        drawCircle(
            color = Color(0xffd0bcff),
            radius = size.minDimension * .23f,
            center = androidx.compose.ui.geometry.Offset(size.width * .36f, size.height * .38f),
        )
        drawCircle(
            color = Color(0xff4fd8c8),
            radius = size.minDimension * .18f,
            center = androidx.compose.ui.geometry.Offset(size.width * .67f, size.height * .67f),
        )
    }
}

fun main() = application {
    var previewWindow by remember { mutableStateOf(false) }
    var transparentWindow by remember { mutableStateOf(false) }
    var titleBarDemoWindow by remember { mutableStateOf(false) }
    var previewAlwaysOnTop by remember { mutableStateOf(true) }
    var dialogModality by remember { mutableStateOf<DialogModalityType?>(null) }
    val trayAvailable = isTraySupported
    val trayState = rememberTrayState()

    if (trayAvailable) {
        Tray(
            icon = MacosCatalogueIcon,
            state = trayState,
            tooltip = "Compose macOS Component Catalogue",
            onAction = { previewWindow = true },
        ) {
            CheckboxItem(
                text = "Preview window",
                checked = previewWindow,
                onCheckedChange = { previewWindow = it },
            )
            CheckboxItem(
                text = "Transparent surface",
                checked = transparentWindow,
                onCheckedChange = { transparentWindow = it },
            )
            Separator()
            Item("Send notification") {
                sendNotification(
                    Notification(
                        title = "Compose macOS",
                        message = "The native macOS tray integration is active.",
                        type = Notification.Type.Info,
                    )
                )
            }
            Item("Exit", onClick = ::exitApplication)
        }
    }

    val state = rememberWindowState(size = DpSize(1240.dp, 800.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Compose macOS SDL · Component Catalogue",
        icon = MacosCatalogueIcon,
    ) {
        DisposableEffect(window) {
            window.minimumSize = DpSize(640.dp, 420.dp)
            onDispose {}
        }
        MenuBar {
            Menu("File") {
                CheckboxItem(
                    text = "Preview window",
                    checked = previewWindow,
                    icon = MacosCatalogueIcon,
                    shortcut = KeyShortcut(Key.N, meta = true),
                    onCheckedChange = { previewWindow = it },
                )
                CheckboxItem(
                    text = "Transparent surface",
                    checked = transparentWindow,
                    shortcut = KeyShortcut(Key.T, meta = true, shift = true),
                    onCheckedChange = { transparentWindow = it },
                )
                Separator()
                Item(
                    text = "Exit",
                    shortcut = KeyShortcut(Key.Q, meta = true),
                    onClick = ::exitApplication,
                )
            }
            Menu("Window") {
                Item("Open title bar demo") { titleBarDemoWindow = true }
                CheckboxItem(
                    text = "Preview always on top",
                    checked = previewAlwaysOnTop,
                    onCheckedChange = { previewAlwaysOnTop = it },
                )
                Separator()
                Item("Open modeless dialog") { dialogModality = DialogModalityType.Modeless }
                Item("Open document-modal dialog") {
                    dialogModality = DialogModalityType.DocumentModal
                }
                Item("Open application-modal dialog") {
                    dialogModality = DialogModalityType.ApplicationModal
                }
            }
            Menu("Help") {
                Item("Desktop integration status") {
                    sendNotification(
                        Notification(
                            title = "macOS desktop integration",
                            message =
                                "Windows, menus, tray, notifications, transparency, modality, " +
                                    "drag and drop, clipboard and URLs are enabled.",
                            type = Notification.Type.Info,
                        )
                    )
                }
            }
        }
        MaterialTheme {
            CatalogueApp(
                CataloguePlatform(
                    name = MacosPlatformName,
                    helloPage = { HelloDemoPage(LocalPlatformAccentColor.current) },
                    webViewPage = {
                        UnavailablePlatformPage(
                            "WebView",
                            "The WPE WebKit demo is Linux-specific. The macOS host exercises the " +
                                "shared Compose desktop APIs instead.",
                        )
                    },
                    videoPage = {
                        UnavailablePlatformPage(
                            "Video Player",
                            "The MPV/OpenGL demo is Linux-specific. The macOS host uses Skiko's " +
                                "native macOS renderer.",
                        )
                    },
                    nativeViewsPage = {
                        val openGlInteropAvailable = isOpenGlInteropAvailable()
                        DemoSection("SDL + Skiko") {
                            Text(
                                "SDL owns the NSWindow and graphics view. Compose renders through " +
                                    "SDL Metal when available, with SDL OpenGL as the fallback."
                            )
                            AssistChip(onClick = {}, label = { Text("Kotlin/Native macOS host") })
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (openGlInteropAvailable) {
                                            "OpenGL native interop available"
                                        } else {
                                            "OpenGL native interop unavailable with this renderer"
                                        }
                                    )
                                },
                            )
                        }
                    },
                    windowsPage = {
                        MacosWindowsPage(
                            onOpenPreview = { previewWindow = true },
                            onOpenTransparentWindow = { transparentWindow = true },
                            onOpenTitleBarDemo = { titleBarDemoWindow = true },
                            onOpenDialog = { dialogModality = it },
                        )
                    },
                    desktopPage = { MacosDesktopPage(trayAvailable) },
                )
            )
            dialogModality?.let { modality ->
                DialogWindow(
                    onCloseRequest = { dialogModality = null },
                    state = rememberDialogState(size = DpSize(460.dp, 300.dp)),
                    title = "${modality.name} modal dialog",
                    icon = MacosCatalogueIcon,
                    modalityType = modality,
                ) {
                    MaterialTheme {
                        MacosModalityDemoDialog(
                            modality = modality,
                            onClose = { dialogModality = null },
                        )
                    }
                }
            }
        }
    }

    if (previewWindow) {
        Window(
            onCloseRequest = { previewWindow = false },
            title = "Live macOS preview",
            icon = MacosCatalogueIcon,
            state = rememberWindowState(size = DpSize(520.dp, 420.dp)),
            alwaysOnTop = previewAlwaysOnTop,
        ) {
            MaterialTheme { MacosPreviewWindow() }
        }
    }

    if (transparentWindow) {
        Window(
            onCloseRequest = { transparentWindow = false },
            title = "Transparent Compose surface",
            icon = MacosCatalogueIcon,
            state = rememberWindowState(size = DpSize(680.dp, 480.dp)),
            undecorated = true,
            transparent = true,
            alwaysOnTop = true,
        ) {
            MaterialTheme { MacosTransparentPreviewWindow(onClose = { transparentWindow = false }) }
        }
    }

    if (titleBarDemoWindow) {
        MacosTitleBarDemoWindow(onCloseRequest = { titleBarDemoWindow = false })
    }
}

@Composable
private fun WindowScope.MacosWindowsPage(
    onOpenPreview: () -> Unit,
    onOpenTransparentWindow: () -> Unit,
    onOpenTitleBarDemo: () -> Unit,
    onOpenDialog: (DialogModalityType) -> Unit,
) {
    DemoSection("Native windows") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onOpenPreview) { Text("Open preview window") }
            OutlinedButton(onOpenTransparentWindow) { Text("Open transparent window") }
            OutlinedButton(onOpenTitleBarDemo) { Text("Title bar demo") }
        }
        Text(
            "The same Window API now drives multiple Cocoa windows, minimum sizes, always-on-top, " +
                "per-pixel transparency, fullscreen/maximize state and native-style title bars."
        )
    }
    DemoSection("Dialog modality") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton({ onOpenDialog(DialogModalityType.Modeless) }) { Text("Modeless") }
            OutlinedButton({ onOpenDialog(DialogModalityType.DocumentModal) }) {
                Text("Document modal")
            }
            Button({ onOpenDialog(DialogModalityType.ApplicationModal) }) {
                Text("Application modal")
            }
        }
        Text(
            "Document modality blocks only the owner tree; application modality blocks the other " +
                "Compose application windows."
        )
    }
    DemoSection("Window menu bar") {
        Text(
            "The shared Compose MenuBar is active above this catalogue. macOS shortcuts use " +
                "Command+N, Command+Shift+T and Command+Q."
        )
    }
}

@Composable
private fun MacosDesktopPage(traySupported: Boolean) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val dragFile = remember { createMacosDragDemoFile() }
    var copied by remember { mutableStateOf("Nothing copied yet") }
    var progress by remember { mutableFloatStateOf(.25f) }
    val dropState = remember { MacosDropState() }

    Column(
        Modifier.fillMaxWidth()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropState.target)
    ) {
        DemoSection("Desktop actions") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button({
                    sendNotification(
                        Notification(
                            "Compose macOS",
                            "Native macOS notifications are connected.",
                            Notification.Type.Info,
                        )
                    )
                }) {
                    Text("Send notification")
                }
                OutlinedButton({
                    sendNotification(
                        NotificationRequest(
                            title = "Copying files",
                            message = "Project assets",
                            progress = progress,
                            actions = listOf(NotificationAction("cancel", "Cancel")),
                        )
                    )
                }) {
                    Text("Progress notification")
                }
                OutlinedButton({
                    coroutineScope.launch {
                        clipboard.setClipEntry(ClipEntry.withPlainText("Hello from Compose macOS"))
                        copied = "Copied to clipboard"
                    }
                }) {
                    Text("Copy text")
                }
                OutlinedButton({ uri.openUri("https://kotlinlang.org") }) { Text("Open URL") }
            }
            Text(copied)
            Slider(progress, { progress = it })
        }
        DemoSection("System tray and menus") {
            AssistChip(
                onClick = {},
                label = {
                    Text(if (traySupported) "macOS status item active" else "Tray unavailable")
                },
            )
            Text(
                if (traySupported) {
                    "The catalogue icon is registered through SDL 3's Cocoa tray backend. Its menu " +
                        "can open windows, send a notification and exit the application."
                } else {
                    "This SDL runtime did not expose its Cocoa tray backend."
                }
            )
        }
        DemoSection("Native drag and drop") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier =
                        Modifier.weight(1f)
                            .height(128.dp)
                            .dragAndDropSource(
                                drawDragDecoration = {
                                    drawRoundRect(
                                        color = Color(0xff6750a4),
                                        cornerRadius = CornerRadius(22f),
                                    )
                                },
                                transferData = {
                                    DragAndDropTransferData(text = "Dragged from Compose macOS")
                                },
                            ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Drag text")
                            Text("Native NSPasteboard offer")
                        }
                    }
                }
                Surface(
                    modifier =
                        Modifier.weight(1f).height(128.dp).dragAndDropSource {
                            DragAndDropTransferData(
                                files = listOf(dragFile),
                                text = "Compose macOS catalogue file",
                            )
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Drag file")
                            Text(dragFile)
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .height(96.dp)
                    .background(
                        if (dropState.active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(16.dp),
                    )
                    .border(
                        if (dropState.active) 4.dp else 2.dp,
                        if (dropState.active) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(dropState.status, Modifier.padding(horizontal = 16.dp))
            }
            Text("Drag either source to Finder/TextEdit, or drop text/files from another app here.")
        }
    }
}

private class MacosDropState {
    var active by mutableStateOf(false)
    var status by mutableStateOf("Drop text or files here")

    val target =
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                active = true
                status = "Release to drop here"
            }

            override fun onEntered(event: DragAndDropEvent) {
                active = true
                status = "Release to drop here"
            }

            override fun onExited(event: DragAndDropEvent) {
                active = false
                status = "Drop text or files here"
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transfer = event.transferData
                status =
                    when {
                        transfer == null -> "Drop received"
                        transfer.files.size == 1 -> "Dropped file: ${transfer.files.single()}"
                        transfer.files.isNotEmpty() -> "Dropped ${transfer.files.size} files"
                        !transfer.text.isNullOrBlank() -> "Dropped text: ${transfer.text?.take(96)}"
                        else -> "Drop received"
                    }
                active = false
                return true
            }

            override fun onEnded(event: DragAndDropEvent) {
                active = false
            }
        }
}

private fun createMacosDragDemoFile(): String {
    val temporaryDirectory =
        getenv("TMPDIR")?.toKString()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: "/tmp"
    val path = "$temporaryDirectory/compose-native-macos-catalogue.txt"
    fopen(path, "w")?.let { file ->
        fputs(
            "Compose macOS native desktop drag source\n" +
                "This file was created by the Compose desktop integration demo.\n",
            file,
        )
        fclose(file)
    }
    return path
}

@Composable
private fun MacosPreviewWindow() {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xff1f1635), Color(0xff073b4c)))),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(36.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Live preview", style = MaterialTheme.typography.headlineMedium)
                Text("A second native Cocoa/SDL window")
            }
        }
    }
}

@Composable
private fun WindowScope.MacosTransparentPreviewWindow(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            tonalElevation = 8.dp,
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WindowDraggableArea(Modifier.weight(1f).height(64.dp)) {
                        Row(
                            Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Per-pixel transparency",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                Text(
                    "The empty rounded corners reveal the macOS desktop or windows underneath.",
                    Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun MacosModalityDemoDialog(modality: DialogModalityType, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("${modality.name} modal dialog", style = MaterialTheme.typography.headlineSmall)
            Text(
                when (modality) {
                    DialogModalityType.Modeless ->
                        "Every other application window stays interactive."
                    DialogModalityType.DocumentModal -> "Only this dialog's owner tree is blocked."
                    else -> "All other Compose application windows are blocked until this closes."
                }
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClose) { Text("Close dialog") }
            }
        }
    }
}

private enum class MacosTitleBarChoice(val label: String) {
    Native("Native AppKit"),
    Auto("Compose macOS style"),
    Custom("Custom Compose controls"),
}

@Composable
private fun MacosTitleBarDemoWindow(onCloseRequest: () -> Unit) {
    var choice by remember { mutableStateOf(MacosTitleBarChoice.Auto) }
    var dark by remember { mutableStateOf(true) }
    var placementBeforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
    val state = rememberWindowState(size = DpSize(760.dp, 520.dp))
    val foreground = if (dark) Color.White else Color.Black
    val customTitleBar =
        rememberTitleBar(foreground = foreground) { type, interaction, icon ->
            val hovered by interaction.collectIsHoveredAsState()
            val background =
                when {
                    type == CaptionButtonType.Close && hovered -> Color(0xFFFF5F57)
                    hovered -> foreground.copy(alpha = .16f)
                    else -> Color.Transparent
                }
            Box(
                Modifier.size(28.dp).background(background, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(14.dp)) { icon() }
            }
        }
    val titleBar =
        when (choice) {
            MacosTitleBarChoice.Native -> TitleBar.Native
            MacosTitleBarChoice.Auto -> TitleBar.Auto(foreground)
            MacosTitleBarChoice.Custom -> customTitleBar
        }

    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        title = "Compose macOS title bar demo",
        icon = MacosCatalogueIcon,
        titleBar = titleBar,
    ) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("macOS Window title bars", style = MaterialTheme.typography.headlineMedium)
                    MacosTitleBarChoice.entries.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == item, onClick = { choice = item })
                            Text(item.label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark theme")
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = dark, onCheckedChange = { dark = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({
                            state.placement =
                                if (state.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                        }) {
                            Text("Toggle maximize")
                        }
                        OutlinedButton({
                            if (state.placement == WindowPlacement.Fullscreen) {
                                state.placement = placementBeforeFullscreen
                            } else {
                                placementBeforeFullscreen = state.placement
                                state.placement = WindowPlacement.Fullscreen
                            }
                        }) {
                            Text("Toggle fullscreen")
                        }
                    }
                    Text(
                        "Auto and Custom hide AppKit's traffic lights while Compose owns the " +
                            "extended title bar. Native restores the normal AppKit title bar."
                    )
                }
            }
        }
    }
}
