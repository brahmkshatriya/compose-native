package org.jetbrains.compose.resources

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.Locale
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

internal actual fun getSystemEnvironment(): ResourceEnvironment {
    val locale = Locale.getDefault()
    val isDarkTheme = currentSystemTheme == SystemTheme.DARK
    val dpi =
        if (GraphicsEnvironment.isHeadless()) DensityQualifier.MDPI.dpi
        else Toolkit.getDefaultToolkit().screenResolution
    return ResourceEnvironment(
        language = LanguageQualifier(locale.language),
        region = RegionQualifier(locale.country),
        theme = ThemeQualifier.selectByValue(isDarkTheme),
        density = DensityQualifier.selectByValue(dpi),
    )
}
