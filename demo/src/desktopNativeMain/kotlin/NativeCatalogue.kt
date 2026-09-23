@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.InteropRenderTarget
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.NotificationAction
import androidx.compose.ui.window.NotificationRequest
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.sendNotification
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.webview.app_demo_render_gl
import kotlin.math.roundToInt
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

internal val desktopPlatformName = if (hostOs == OS.Windows) "Windows" else "Linux"

private val isWindowsCatalogue = hostOs == OS.Windows

@Composable
fun WindowScope.CatalogueApp(
    onOpenPreview: () -> Unit,
    onOpenTransparentWindow: () -> Unit,
    onOpenDialog: (DialogModalityType) -> Unit,
    traySupported: Boolean,
) {
    CatalogueApp(
        CataloguePlatform(
            name = desktopPlatformName,
            helloPage = { PlatformHelloDemoPage() },
            webViewPage = { WebViewPage() },
            videoPage = { VideoPage() },
            nativeViewsPage = { NativeViewsPage() },
            windowsPage = {
                WindowsPage(
                    onOpenPreview = onOpenPreview,
                    onOpenTransparentWindow = onOpenTransparentWindow,
                    onOpenDialog = onOpenDialog,
                )
            },
            desktopPage = { DesktopPage(traySupported) },
        )
    )
}
@Composable
private fun WebViewPage() {
    val initial = "https://www.youtube.com/"
    val browser = remember { EmbeddedBrowser(initial) }
    var address by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ browser.back() }) { CircleGlyph("Back") }
            IconButton({ browser.forward() }) { CircleGlyph("Forward") }
            IconButton({ browser.reload() }) { CircleGlyph("Reload") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true)
            Button({ browser.load(if ("://" in address) address else "https://$address") }) {
                Text("Go")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            NativeView({ browser.nativeView }, Modifier.fillMaxSize())
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(14.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
            ) {
                Text(
                    if (isWindowsCatalogue) "Compose overlay · WebView2"
                    else "Compose overlay · WPE WebKit",
                    Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoPage() {
    val player = remember { MpvPlayer("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") }
    var playing by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(.8f) }
    LaunchedEffect(player) {
        for (position in player.positionUpdates) {
            if (!seeking) seek = position
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NativeView({ player.nativeView }, Modifier.fillMaxSize())
        Surface(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            color = Color.Black.copy(alpha = .72f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Native MPV · HLS stream", Modifier.padding(10.dp), color = Color.White)
        }
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
            color = Color.Black.copy(alpha = .82f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        playing = !playing
                        player.setPlaying(playing)
                    }) {
                        Text(if (playing) "Pause" else "Play")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Mux HLS test stream", Modifier.weight(1f))
                    Text("CC  ⋮  ⛶")
                }
                Slider(
                    value = seek,
                    onValueChange = {
                        seeking = true
                        seek = it
                    },
                    onValueChangeFinished = {
                        player.seek(seek)
                        seeking = false
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume")
                    Slider(
                        volume,
                        {
                            volume = it
                            player.volume(it)
                        },
                        Modifier.width(220.dp),
                    )
                    Text("HLS · MPV")
                }
            }
        }
    }
}

@Composable
private fun UnavailableNativePage(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.widthIn(max = 620.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NativeViewsPage() {
    var size by remember { mutableFloatStateOf(190f) }
    val cpuView = remember { NativeInteropView(renderer = ::renderCpuDemo) }
    val phaseHolder = remember { FloatArray(1) { size / 30f } }
    val glView = remember {
        NativeInteropView.openGl(
            renderer = { target: OpenGlInteropRenderTarget ->
                app_demo_render_gl(target.framebuffer, target.width, target.height, phaseHolder[0])
                true
            }
        )
    }
    DemoSection("Two native surfaces in Compose") {
        val cornerRadius = with(LocalDensity.current) { 24.dp.toPx() }
        val nativeClip: (Size) -> Path = { viewSize ->
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = viewSize.width,
                        bottom = viewSize.height,
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NativeView(
                { cpuView },
                Modifier.weight(1f).height(size.dp).clip(RoundedCornerShape(24.dp)).graphicsLayer {
                    rotationZ = -2f
                },
                clipPath = nativeClip,
            )
            NativeView(
                { glView },
                Modifier.weight(1f).height(size.dp).clip(RoundedCornerShape(24.dp)).graphicsLayer {
                    rotationZ = 2f
                },
                clipPath = nativeClip,
            )
        }
        Row {
            AssistChip({}, { Text("CPU pixels") })
            Spacer(Modifier.width(8.dp))
            AssistChip({}, { Text("Native OpenGL") })
        }
        Text("Animated size / OpenGL color")
        Slider(
            size,
            {
                size = it
                phaseHolder[0] = it / 30f
                glView.requestRender()
            },
            valueRange = 120f..300f,
        )
    }
}

private fun renderCpuDemo(target: InteropRenderTarget): Boolean {
    val pixels = target.pixels.reinterpret<UIntVar>()
    val rowStride = target.stride / UInt.SIZE_BYTES
    val blueStep = (150 shl 16) / target.width
    val greenStep = (180 shl 16) / target.height
    var green = 40 shl 16
    for (y in 0 until target.height) {
        val row = y * rowStride
        val alphaRedGreen = 0xffd20000u or ((green ushr 16).toUInt() shl 8)
        var blue = 80 shl 16
        for (x in 0 until target.width) {
            pixels[row + x] = alphaRedGreen or (blue ushr 16).toUInt()
            blue += blueStep
        }
        green += greenStep
    }
    return true
}

@Composable
private fun WindowScope.WindowsPage(
    onOpenPreview: () -> Unit,
    onOpenTransparentWindow: () -> Unit,
    onOpenDialog: (DialogModalityType) -> Unit,
) {
    DemoSection("Native windows and icons") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onOpenPreview) { Text("Open preview window") }
            OutlinedButton(onOpenTransparentWindow) { Text("Open transparent window") }
        }
        Text(
            "Every catalogue surface uses a Painter-backed native window icon. The transparent " +
                "window has real per-pixel alpha and intentionally leaves its corners empty."
        )
        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Custom title-bar preview  ·  drag this area",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
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
            "Modeless dialogs leave every window interactive. Document modality blocks only the " +
                "owner tree; application modality blocks all other application windows."
        )
    }
    DemoSection("Window menu bar") {
        Text(
            "Use File, View, Window and Help above the catalogue. The menu tree includes nested " +
                "menus, check and radio items, an icon, mnemonics, and Ctrl+N / Ctrl+Shift+T / Ctrl+Q shortcuts."
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopPage(traySupported: Boolean) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val dragFile = remember { createDragDemoFile() }
    var copied by remember { mutableStateOf("Nothing copied yet") }
    var progress by remember { mutableFloatStateOf(.25f) }
    val dropState = remember { CatalogueDropState() }
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
                            "Compose $desktopPlatformName",
                            "The catalogue notification works.",
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
                        clipboard.setClipEntry(
                            ClipEntry.withPlainText("Hello from Compose $desktopPlatformName")
                        )
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
                    Text(
                        if (traySupported) "StatusNotifier watcher detected"
                        else "No tray watcher detected"
                    )
                },
            )
            Text(
                if (traySupported) {
                    "The catalogue icon is registered in the system tray. Primary activation opens the " +
                        "preview window; its dbusmenu contains live check items, notifications and Exit."
                } else {
                    "This desktop session does not expose a StatusNotifier watcher. The window menu bar " +
                        "and all other desktop integrations remain available."
                }
            )
        }
        DemoSection("Outgoing drag source") {
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
                                    DragAndDropTransferData(
                                        text =
                                            "Dragged from the Compose $desktopPlatformName catalogue"
                                    )
                                },
                            ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Drag text", fontWeight = FontWeight.Bold)
                            Text(
                                if (isWindowsCatalogue) "Native OLE offer"
                                else "Native Wayland / Xdnd offer"
                            )
                        }
                    }
                }
                Surface(
                    modifier =
                        Modifier.weight(1f).height(128.dp).dragAndDropSource {
                            DragAndDropTransferData(
                                files = listOf(dragFile),
                                text = "Compose $desktopPlatformName catalogue file",
                            )
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Drag file", fontWeight = FontWeight.Bold)
                            Text(dragFile, style = MaterialTheme.typography.labelSmall)
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
            Text("Drag either source to the target above or to another native application.")
        }
        DemoSection("Keyboard") {
            Text(
                "Menu shortcuts: Ctrl+N preview  ·  Ctrl+Shift+T transparency  ·  Ctrl+Q exit  ·  " +
                    "Tab focus  ·  Esc close"
            )
        }
    }
}

private class CatalogueDropState {
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
                val droppedText = transfer?.text
                status =
                    when {
                        transfer == null -> "Drop received"
                        transfer.files.size == 1 -> "Dropped file: ${transfer.files.single()}"
                        transfer.files.isNotEmpty() -> "Dropped ${transfer.files.size} files"
                        !droppedText.isNullOrBlank() -> "Dropped text: ${droppedText.take(96)}"
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

private fun createDragDemoFile(): String {
    val temporaryDirectory =
        getenv(if (isWindowsCatalogue) "TEMP" else "TMPDIR")
            ?.toKString()
            ?.trimEnd('\\', '/')
            ?.takeIf(String::isNotEmpty) ?: if (isWindowsCatalogue) "." else "/tmp"
    val separator = if (isWindowsCatalogue) "\\" else "/"
    val path = "$temporaryDirectory${separator}compose-native-catalogue.txt"
    fopen(path, "w")?.let { file ->
        fputs(
            "Compose $desktopPlatformName catalogue drag source\n" +
                "This file was created by the native desktop integration demo.\n",
            file,
        )
        fclose(file)
    }
    return path
}

@Composable
fun PreviewWindow() {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xff1f1635), Color(0xff073b4c)))),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(36.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Live preview", style = MaterialTheme.typography.headlineMedium)
                Text("A second native $desktopPlatformName window")
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun WindowScope.TransparentPreviewWindow(onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WindowDraggableArea(modifier = Modifier.weight(1f).height(64.dp)) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Per-pixel transparency",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "The empty corners reveal the desktop or window underneath.",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Drag only the title area above. Buttons and body content remain fully " +
                            "interactive, including when the window is maximized.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ModalityDemoDialog(modality: DialogModalityType, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("${modality.name} modal dialog", style = MaterialTheme.typography.headlineSmall)
            Text(
                when (modality) {
                    DialogModalityType.Modeless ->
                        "The catalogue and every other application window remain interactive."
                    DialogModalityType.DocumentModal ->
                        "The owning catalogue document is blocked while unrelated documents remain interactive."
                    else ->
                        "All other windows in this application are blocked until this dialog closes."
                }
            )
            Text(
                "This is a separate owned SDL window with the same native Painter icon.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClose) { Text("Close dialog") }
            }
        }
    }
}
