package org.jetbrains.compose.resources

import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import platform.Foundation.*
import platform.Foundation.countryCode
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages

internal actual fun getSystemEnvironment(): ResourceEnvironment {
    val localeIdentifier = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
    val locale = NSLocale(localeIdentifier)
    return ResourceEnvironment(
        language = LanguageQualifier(locale.languageCode.orEmpty()),
        region = RegionQualifier(locale.countryCode.orEmpty()),
        theme = ThemeQualifier.selectByValue(currentSystemTheme == SystemTheme.DARK),
        density = DensityQualifier.MDPI,
    )
}
