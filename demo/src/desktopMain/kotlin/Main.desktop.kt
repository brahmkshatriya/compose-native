package dev.demo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

fun main() = singleWindowApplication(
    title = "Compose JVM · Component Catalogue",
    state = WindowState(size = DpSize(1240.dp, 800.dp)),
) {
    MaterialTheme {
        CatalogueApp(
            CataloguePlatform(
                name = "JVM Desktop",
                helloPage = { PlatformHelloDemoPage() },
                webViewPage = {
                    UnavailablePlatformPage(
                        "WebView",
                        "The shared catalogue is running on Compose Desktop/JVM. The embedded WPE/WebView2 " +
                            "surface is specific to the Kotlin/Native desktop host.",
                    )
                },
                videoPage = {
                    UnavailablePlatformPage(
                        "Video Player",
                        "The MPV/OpenGL interop demo is specific to the Kotlin/Native desktop host.",
                    )
                },
                nativeViewsPage = {
                    DemoSection("JVM interop") {
                        Text("JVM desktop uses AWT/Swing interop rather than the native SDL interop surface.")
                    }
                },
                windowsPage = {
                    DemoSection("Compose Desktop JVM window") {
                        Text(
                            "This catalogue uses the standard Compose Desktop/AWT window host while sharing " +
                                "the same component catalogue code with the Kotlin/Native target."
                        )
                    }
                },
                desktopPage = { JvmDesktopPage() },
            )
        )
    }
}

@Composable
private fun JvmDesktopPage() {
    val uriHandler = LocalUriHandler.current
    DemoSection("JVM runtime") {
        Text("Java runtime: ${System.getProperty("java.runtime.version")}")
        Text("Host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        AssistChip(onClick = {}, label = { Text("AWT / Skiko desktop host") })
    }
    DemoSection("Desktop URI integration") {
        Row {
            Button(onClick = { uriHandler.openUri("https://kotlinlang.org") }) { Text("Open Kotlin") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { uriHandler.openUri("https://www.jetbrains.com/compose-multiplatform/") }) {
                Text("Open Compose")
            }
        }
    }
}
