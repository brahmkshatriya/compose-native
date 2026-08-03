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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.InteropKeyEvent
import androidx.compose.ui.viewinterop.InteropPointerEvent
import androidx.compose.ui.viewinterop.InteropPointerEventType
import androidx.compose.ui.viewinterop.LinuxInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.webview.app_webview_create
import app.webview.app_webview_can_go_back
import app.webview.app_webview_can_go_forward
import app.webview.app_webview_destroy
import app.webview.app_webview_error
import app.webview.app_webview_key
import app.webview.app_webview_load_uri
import app.webview.app_webview_go_back
import app.webview.app_webview_go_forward
import app.webview.app_webview_reload
import app.webview.app_webview_media_seek
import app.webview.app_webview_media_set_playing
import app.webview.app_webview_media_set_volume
import app.webview.app_webview_pointer_button
import app.webview.app_webview_pointer_motion
import app.webview.app_webview_render
import app.webview.app_webview_scroll
import app.webview.app_webview_set_focused
import app.mpv.app_mpv_create
import app.mpv.app_mpv_destroy
import app.mpv.app_mpv_render
import app.mpv.app_mpv_seek_percent
import app.mpv.app_mpv_set_playing
import app.mpv.app_mpv_set_position_update_callback
import app.mpv.app_mpv_set_render_update_callback
import app.mpv.app_mpv_set_volume
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.Channel
import platform.posix.getenv

private const val YouTubeUrl = "https://www.youtube.com/"

private fun normalizeUrl(value: String): String {
    val url = value.trim()
    if (url.isEmpty()) return ""
    return if ("://" in url) url else "https://$url"
}

internal class WpeBrowser(uri: String) {
    private var handle = app_webview_create(uri)

    val nativeView =
        LinuxInteropView.openGl(
            renderer = ::render,
            continuousRendering = true,
            releaser = ::close,
            pointerHandler = ::pointer,
            keyHandler = ::key,
            focusHandler = ::focus,
        )

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

    private fun render(target: OpenGlInteropRenderTarget): Boolean {
        val browser = handle ?: return false
        return app_webview_render(
            browser,
            target.framebuffer,
            target.width,
            target.height,
            target.density,
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
        LinuxInteropView.openGl(
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
    val browser = remember { WpeBrowser(initialUrl) }
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
                Button(onClick = ::navigate) {
                    Text("Go")
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            NativeView(
                factory = { browser.nativeView },
                modifier = Modifier.fillMaxSize(),
            )
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

fun main() {
    application {
        var previewWindow by remember { mutableStateOf(false) }
        val state = rememberWindowState(size = DpSize(1240.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Compose Linux · Component Catalogue",
        ) {
            DisposableEffect(window) {
                window.minimumSize = DpSize(640.dp, 420.dp)
                onDispose {}
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CatalogueApp(onOpenPreview = { previewWindow = true })
            }
        }
        if (previewWindow) {
            Window(
                onCloseRequest = { previewWindow = false },
                title = "Live preview",
                state = rememberWindowState(size = DpSize(520.dp, 420.dp)),
                alwaysOnTop = true,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) { PreviewWindow() }
            }
        }
    }
}
