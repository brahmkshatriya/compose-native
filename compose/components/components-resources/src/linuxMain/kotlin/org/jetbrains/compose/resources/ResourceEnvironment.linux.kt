package org.jetbrains.compose.resources

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun getSystemEnvironment(): ResourceEnvironment {
    val locale =
        sequenceOf("LC_ALL", "LC_MESSAGES", "LANG")
            .mapNotNull { key -> getenv(key)?.toKString()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            .orEmpty()
            .substringBefore('.')
            .substringBefore('@')
            .replace('-', '_')
    val language = locale.substringBefore('_').takeIf { it.length in 2..3 }.orEmpty()
    val region = locale.substringAfter('_', "").takeIf { it.length == 2 }.orEmpty()
    return ResourceEnvironment(
        language = LanguageQualifier(language),
        region = RegionQualifier(region),
        theme = ThemeQualifier.selectByValue(false),
        density = DensityQualifier.MDPI,
    )
}
