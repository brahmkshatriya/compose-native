@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.InteropKeyEvent
import androidx.compose.ui.viewinterop.InteropPointerEvent
import androidx.compose.ui.viewinterop.InteropPointerEventType
import androidx.compose.ui.viewinterop.InteropRenderTarget
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.sendNotification
import app.mpv.app_mpv_create
import app.mpv.app_mpv_destroy
import app.mpv.app_mpv_render
import app.mpv.app_mpv_seek_percent
import app.mpv.app_mpv_set_playing
import app.mpv.app_mpv_set_position_update_callback
import app.mpv.app_mpv_set_render_update_callback
import app.mpv.app_mpv_set_volume
import app.webview.app_webview_can_go_back
import app.webview.app_webview_can_go_forward
import app.webview.app_webview_create
import app.webview.app_webview_destroy
import app.webview.app_webview_error
import app.webview.app_webview_go_back
import app.webview.app_webview_go_forward
import app.webview.app_webview_key
import app.webview.app_webview_load_uri
import app.webview.app_webview_media_seek
import app.webview.app_webview_media_set_playing
import app.webview.app_webview_media_set_volume
import app.webview.app_webview_pointer_button
import app.webview.app_webview_pointer_motion
import app.webview.app_webview_reload
import app.webview.app_webview_render
import app.webview.app_webview_render_pixels
import app.webview.app_webview_scroll
import app.webview.app_webview_set_focused
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import platform.posix.getenv

private const val YouTubeUrl = "https://www.youtube.com/"

private fun normalizeUrl(value: String): String {
    val url = value.trim()
    if (url.isEmpty()) return ""
    return if ("://" in url) url else "https://$url"
}

internal class EmbeddedBrowser(uri: String) {
    private var handle = app_webview_create(uri)

    val nativeView =
        if (hostOs == OS.Windows) {
            NativeInteropView(
                renderer = ::renderPixels,
                continuousRendering = true,
                releaser = ::close,
                pointerHandler = ::pointer,
                keyHandler = ::key,
                focusHandler = ::focus,
            )
        } else {
            NativeInteropView.openGl(
                renderer = ::renderOpenGl,
                continuousRendering = true,
                releaser = ::close,
                pointerHandler = ::pointer,
                keyHandler = ::key,
                focusHandler = ::focus,
            )
        }

    val error: String?
        get() = handle?.let(::app_webview_error)?.toKString()

    fun load(url: String) {
        handle?.let { app_webview_load_uri(it, url) }
    }

    fun back() = handle?.let { app_webview_go_back(it) } ?: Unit

    fun forward() = handle?.let { app_webview_go_forward(it) } ?: Unit

    fun reload() = handle?.let { app_webview_reload(it) } ?: Unit

    val canGoBack: Boolean
        get() = handle?.let { app_webview_can_go_back(it) != 0 } == true

    val canGoForward: Boolean
        get() = handle?.let { app_webview_can_go_forward(it) != 0 } == true

    fun setPlaying(playing: Boolean) =
        handle?.let { app_webview_media_set_playing(it, if (playing) 1 else 0) } ?: Unit

    fun seek(seconds: Double) = handle?.let { app_webview_media_seek(it, seconds) } ?: Unit

    fun volume(value: Double) = handle?.let { app_webview_media_set_volume(it, value) } ?: Unit

    private fun renderOpenGl(target: OpenGlInteropRenderTarget): Boolean {
        val browser = handle ?: return false
        return app_webview_render(
            browser,
            target.framebuffer,
            target.width,
            target.height,
            target.density,
        ) != 0
    }

    private fun renderPixels(target: InteropRenderTarget): Boolean {
        val browser = handle ?: return false
        return app_webview_render_pixels(
            browser,
            target.pixels,
            target.width,
            target.height,
            target.stride,
            1f,
        ) != 0
    }

    private fun pointer(event: InteropPointerEvent): Boolean {
        val browser = handle ?: return false
        val time = event.timeMillis.toUInt()
        val modifiers = event.modifiers.toUInt()
        when (event.type) {
            InteropPointerEventType.Move ->
                app_webview_pointer_motion(
                    browser,
                    event.x.toInt(),
                    event.y.toInt(),
                    time,
                    modifiers,
                )
            InteropPointerEventType.Button ->
                app_webview_pointer_button(
                    browser,
                    event.x.toInt(),
                    event.y.toInt(),
                    time,
                    event.button.toUInt(),
                    if (event.pressed) 1 else 0,
                    modifiers,
                )
            InteropPointerEventType.Scroll ->
                app_webview_scroll(
                    browser,
                    event.x.toInt(),
                    event.y.toInt(),
                    time,
                    event.scrollDeltaX.toDouble(),
                    event.scrollDeltaY.toDouble(),
                    modifiers,
                )
        }
        return true
    }

    private fun key(event: InteropKeyEvent): Boolean {
        val browser = handle ?: return false
        app_webview_key(
            browser,
            event.keyCode,
            event.codePoint.toUInt(),
            if (event.pressed) 1 else 0,
            event.modifiers.toUInt(),
        )
        return true
    }

    private fun focus(focused: Boolean) {
        handle?.let { app_webview_set_focused(it, if (focused) 1 else 0) }
    }

    private fun close() {
        val browser = handle ?: return
        handle = null
        app_webview_destroy(browser)
    }
}

private fun requestMpvRender(context: COpaquePointer?) {
    context?.asStableRef<MpvPlayer>()?.get()?.requestRender()
}

private fun updateMpvPosition(context: COpaquePointer?, position: Double) {
    context?.asStableRef<MpvPlayer>()?.get()?.updatePosition(position)
}

internal class MpvPlayer(uri: String) {
    private var handle = app_mpv_create(uri)
    private var renderCallbackRef: StableRef<MpvPlayer>? = null
    val positionUpdates = Channel<Float>(Channel.CONFLATED)

    val nativeView =
        NativeInteropView.openGl(
            renderer = ::render,
            continuousRendering = false,
            releaser = ::close,
        )

    init {
        handle?.let { player ->
            val callbackRef = StableRef.create(this)
            renderCallbackRef = callbackRef
            app_mpv_set_render_update_callback(
                player,
                staticCFunction(::requestMpvRender),
                callbackRef.asCPointer(),
            )
            app_mpv_set_position_update_callback(
                player,
                staticCFunction(::updateMpvPosition),
                callbackRef.asCPointer(),
            )
        }
    }

    internal fun requestRender() {
        nativeView.requestRender()
    }

    fun setPlaying(playing: Boolean) {
        handle?.let { app_mpv_set_playing(it, if (playing) 1 else 0) }
    }

    fun seek(percent: Float) {
        handle?.let { app_mpv_seek_percent(it, (percent * 100.0f).toDouble()) }
    }

    internal fun updatePosition(position: Double) {
        positionUpdates.trySend(position.toFloat())
    }

    fun volume(value: Float) {
        handle?.let { app_mpv_set_volume(it, (value * 100.0f).toDouble()) }
    }

    private fun render(target: OpenGlInteropRenderTarget): Boolean {
        val player = handle ?: return false
        return app_mpv_render(player, target.framebuffer, target.width, target.height) != 0
    }

    private fun close() {
        val player = handle ?: return
        handle = null
        app_mpv_set_render_update_callback(player, null, null)
        app_mpv_set_position_update_callback(player, null, null)
        positionUpdates.close()
        renderCallbackRef?.dispose()
        renderCallbackRef = null
        app_mpv_destroy(player)
    }
}

@Composable
private fun YouTube() {
    val initialUrl = remember {
        getenv("KTNATIVE_WEBVIEW_URL")?.toKString()?.takeIf(String::isNotBlank) ?: YouTubeUrl
    }
    val browser = remember { EmbeddedBrowser(initialUrl) }
    var address by remember { mutableStateOf(initialUrl) }

    fun navigate() {
        val url = normalizeUrl(address)
        if (url.isNotEmpty()) {
            address = url
            browser.load(url)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(88.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Native WebView", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Website address") },
                )
                Button(onClick = ::navigate) { Text("Go") }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            NativeView(factory = { browser.nativeView }, modifier = Modifier.fillMaxSize())
            browser.error?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private object CatalogueIcon : Painter() {
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

fun main() {
    application {
        var previewWindow by remember { mutableStateOf(false) }
        var transparentWindow by remember { mutableStateOf(false) }
        var previewAlwaysOnTop by remember { mutableStateOf(true) }
        var dialogModality by remember { mutableStateOf<DialogModalityType?>(null) }
        var accentChoice by remember { mutableStateOf(0) }
        val trayAvailable = isTraySupported
        val trayState = rememberTrayState()
        val colorScheme =
            if (accentChoice == 0) {
                darkColorScheme(
                    primary = Color(0xffd0bcff),
                    secondary = Color(0xffccc2dc),
                    tertiary = Color(0xffefb8c8),
                )
            } else {
                darkColorScheme(
                    primary = Color(0xff4fd8c8),
                    secondary = Color(0xffb4ccc7),
                    tertiary = Color(0xffffb59d),
                )
            }

        if (trayAvailable) {
            Tray(
                icon = CatalogueIcon,
                state = trayState,
                tooltip = "Compose $desktopPlatformName Component Catalogue",
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
                    trayState.sendNotification(
                        Notification(
                            title = "Compose $desktopPlatformName",
                            message = "The catalogue tray is active.",
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
            title = "Compose $desktopPlatformName · Component Catalogue",
            icon = CatalogueIcon,
        ) {
            DisposableEffect(window) {
                window.minimumSize = DpSize(640.dp, 420.dp)
                onDispose {}
            }
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    CheckboxItem(
                        text = "Preview window",
                        checked = previewWindow,
                        icon = CatalogueIcon,
                        shortcut = KeyShortcut(Key.N, ctrl = true),
                        onCheckedChange = { previewWindow = it },
                    )
                    CheckboxItem(
                        text = "Transparent surface",
                        checked = transparentWindow,
                        shortcut = KeyShortcut(Key.T, ctrl = true, shift = true),
                        onCheckedChange = { transparentWindow = it },
                    )
                    Separator()
                    Item(
                        text = "Exit",
                        shortcut = KeyShortcut(Key.Q, ctrl = true),
                        onClick = ::exitApplication,
                    )
                }
                Menu("View", mnemonic = 'V') {
                    CheckboxItem(
                        text = "Preview always on top",
                        checked = previewAlwaysOnTop,
                        onCheckedChange = { previewAlwaysOnTop = it },
                    )
                    Menu("Accent") {
                        RadioButtonItem(
                            text = "Purple",
                            selected = accentChoice == 0,
                            onClick = { accentChoice = 0 },
                        )
                        RadioButtonItem(
                            text = "Teal",
                            selected = accentChoice == 1,
                            onClick = { accentChoice = 1 },
                        )
                    }
                }
                Menu("Window", mnemonic = 'W') {
                    Item("Open modeless dialog") { dialogModality = DialogModalityType.Modeless }
                    Item("Open document-modal dialog") {
                        dialogModality = DialogModalityType.DocumentModal
                    }
                    Item("Open application-modal dialog") {
                        dialogModality = DialogModalityType.ApplicationModal
                    }
                }
                Menu("Help", mnemonic = 'H') {
                    Item("Desktop integration status") {
                        trayState.sendNotification(
                            Notification(
                                title = "Desktop integration",
                                message =
                                    if (trayAvailable) {
                                        "Window icons, menus, tray, transparency, modality and drag source are enabled."
                                    } else {
                                        "Window icons, menus, transparency, modality and drag source are enabled. No tray watcher was detected."
                                    },
                                type = Notification.Type.Info,
                            )
                        )
                    }
                }
            }
            MaterialTheme(colorScheme = colorScheme) {
                CatalogueApp(
                    onOpenPreview = { previewWindow = true },
                    onOpenTransparentWindow = { transparentWindow = true },
                    onOpenDialog = { dialogModality = it },
                    traySupported = trayAvailable,
                )
                dialogModality?.let { modality ->
                    DialogWindow(
                        onCloseRequest = { dialogModality = null },
                        state = rememberDialogState(size = DpSize(460.dp, 300.dp)),
                        title = "${modality.name} modal dialog",
                        icon = CatalogueIcon,
                        modalityType = modality,
                    ) {
                        MaterialTheme(colorScheme = colorScheme) {
                            ModalityDemoDialog(
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
                title = "Live preview",
                icon = CatalogueIcon,
                state = rememberWindowState(size = DpSize(520.dp, 420.dp)),
                alwaysOnTop = previewAlwaysOnTop,
            ) {
                MaterialTheme(colorScheme = colorScheme) { PreviewWindow() }
            }
        }

        if (transparentWindow) {
            Window(
                onCloseRequest = { transparentWindow = false },
                title = "Transparent Compose surface",
                icon = CatalogueIcon,
                state = rememberWindowState(size = DpSize(680.dp, 480.dp)),
                undecorated = true,
                transparent = true,
                alwaysOnTop = true,
            ) {
                MaterialTheme(colorScheme = colorScheme) {
                    TransparentPreviewWindow(onClose = { transparentWindow = false })
                }
            }
        }
    }
}
