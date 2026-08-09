package dev.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalPlatformAccentColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import demo.generated.resources.GoogleSansFlex
import demo.generated.resources.Res
import demo.generated.resources.app_name
import demo.generated.resources.ic_home_filled
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HelloDemoPage() {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val accentColor =
                    LocalPlatformAccentColor.current ?: MaterialTheme.colorScheme.primary
                Button(
                    onClick = {},
                    modifier = Modifier.padding(16.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor =
                                if (accentColor.luminance() > 0.5f) Color.Black else Color.White,
                        ),
                ) {
                    Text("hi")
                }
            }
        }
    }
}

@Composable
internal fun ResourceDemoPage() {
    val font = Font(Res.font.GoogleSansFlex)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(painter = painterResource(Res.drawable.ic_home_filled), contentDescription = null)
        Text(text = stringResource(Res.string.app_name), fontFamily = FontFamily(font))
        Text("String, vector, and byte-backed font resources are loaded by the Linux runtime.")
    }
}
