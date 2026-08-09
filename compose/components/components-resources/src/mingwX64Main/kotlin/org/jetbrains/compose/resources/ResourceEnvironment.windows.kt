@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.compose.resources

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetUserDefaultLocaleName
import platform.windows.LOCALE_NAME_MAX_LENGTH
import platform.windows.WCHARVar

internal actual fun getSystemEnvironment(): ResourceEnvironment {
    val locale = currentLanguageTag().replace('_', '-')
    val subtags = locale.split('-')
    val language = subtags.firstOrNull()?.takeIf { it.length in 2..3 }.orEmpty()
    val region =
        subtags
            .drop(1)
            .firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
            .orEmpty()
    return ResourceEnvironment(
        language = LanguageQualifier(language),
        region = RegionQualifier(region),
        theme = ThemeQualifier.selectByValue(false),
        density = DensityQualifier.MDPI,
    )
}

private fun currentLanguageTag(): String = memScoped {
    val buffer = allocArray<WCHARVar>(LOCALE_NAME_MAX_LENGTH)
    val length = GetUserDefaultLocaleName(buffer, LOCALE_NAME_MAX_LENGTH)
    if (length > 0) buffer.toKString() else "en-US"
}
