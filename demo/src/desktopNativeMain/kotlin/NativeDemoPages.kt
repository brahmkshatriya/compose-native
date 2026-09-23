package dev.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalPlatformAccentColor

@Composable
internal fun PlatformHelloDemoPage() {
    HelloDemoPage(LocalPlatformAccentColor.current)
}
