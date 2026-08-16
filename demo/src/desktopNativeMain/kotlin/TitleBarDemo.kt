package dev.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.CaptionButtonType
import androidx.compose.ui.window.TitleBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberTitleBar
import androidx.compose.ui.window.rememberWindowState

private enum class TitleBarChoice(val label: String) {
    Native("Native"),
    Auto("Auto (platform style)"),
    Custom("Custom"),
}

@Composable
fun TitleBarDemoWindow(onCloseRequest: () -> Unit) {
    var titleBarChoice by remember { mutableStateOf(TitleBarChoice.Auto) }
    var isDarkTheme by remember { mutableStateOf(true) }
    val state = rememberWindowState(size = DpSize(760.dp, 520.dp))
    val colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
    val foreground = if (isDarkTheme) Color.White else Color.Black

    // A custom caption-button style. The container is composed in the window scene, outside the
    // window content, so it cannot see composition locals provided by the content (such as
    // MaterialTheme). Use plain colors or the system theme instead. The container defines its own
    // size; `icon()` draws the platform caption glyph and fills whatever space the container
    // gives it.
    val customTitleBar =
        rememberTitleBar(foreground = foreground) { type, interaction, icon ->
            val hovered by interaction.collectIsHoveredAsState()
            val background =
                when {
                    type == CaptionButtonType.Close && hovered -> Color(0xFFC42B1C)
                    hovered -> foreground.copy(alpha = 0.2f)
                    else -> Color.Transparent
                }
            Box(Modifier.size(32.dp).background(background), contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp)) { icon() }
            }
        }

    val titleBar =
        when (titleBarChoice) {
            TitleBarChoice.Native -> TitleBar.Native
            TitleBarChoice.Auto -> TitleBar.Auto(foreground = foreground)
            TitleBarChoice.Custom -> customTitleBar
        }

    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        title = "Compose Native title bar demo",
        titleBar = titleBar,
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .safeDrawingPadding()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Native window controls", style = MaterialTheme.typography.headlineMedium)
                    Text("Current placement: ${state.placement}")

                    Text("Title bar", style = MaterialTheme.typography.titleMedium)
                    TitleBarChoice.entries.forEach { choice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = titleBarChoice == choice,
                                onClick = { titleBarChoice = choice },
                            )
                            Text(choice.label)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark theme")
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = isDarkTheme, onCheckedChange = { isDarkTheme = it })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                state.placement =
                                    if (state.placement == WindowPlacement.Maximized) {
                                        WindowPlacement.Floating
                                    } else {
                                        WindowPlacement.Maximized
                                    }
                            }
                        ) {
                            Text(
                                if (state.placement == WindowPlacement.Maximized) {
                                    "Restore"
                                } else {
                                    "Maximize"
                                }
                            )
                        }
                        Button(
                            onClick = {
                                state.placement =
                                    if (state.placement == WindowPlacement.Fullscreen) {
                                        WindowPlacement.Floating
                                    } else {
                                        WindowPlacement.Fullscreen
                                    }
                            }
                        ) {
                            Text(
                                if (state.placement == WindowPlacement.Fullscreen) {
                                    "Exit fullscreen"
                                } else {
                                    "Fullscreen"
                                }
                            )
                        }
                    }

                    Text(
                        "The colored area extends behind the title bar. The window draws only " +
                            "caption controls there; no title text or background is rendered. " +
                            "The content uses WindowInsets.safeDrawing, while Window provides " +
                            "dragging and caption controls.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
