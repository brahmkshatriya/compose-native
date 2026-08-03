/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.text.intl

import androidx.compose.runtime.Immutable
import androidx.compose.ui.InternalComposeUiApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@Immutable
actual class Locale actual constructor(languageTag: String) {
    private val languageTag = normalizeLanguageTag(languageTag)
    private val subtags = this.languageTag.split('-')

    actual val language: String = subtags.firstOrNull().orEmpty().ifEmpty { "en" }
    actual val script: String = subtags.firstOrNull { it.length == 4 }.orEmpty()
    actual val region: String =
        subtags
            .drop(1)
            .firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
            .orEmpty()

    actual fun toLanguageTag(): String = languageTag

    actual override fun equals(other: Any?): Boolean =
        other is Locale && languageTag.equals(other.languageTag, ignoreCase = true)

    actual override fun hashCode(): Int = languageTag.lowercase().hashCode()

    actual override fun toString(): String = languageTag

    actual companion object {
        actual val current: Locale
            get() = platformLocaleDelegate.current[0]
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentLanguageTag(): String =
    getenv("LC_ALL")?.toKString()?.takeIf(String::isNotBlank)
        ?: getenv("LC_MESSAGES")?.toKString()?.takeIf(String::isNotBlank)
        ?: getenv("LANG")?.toKString()?.takeIf(String::isNotBlank)
        ?: "en-US"

private fun normalizeLanguageTag(value: String): String =
    value.substringBefore('.').substringBefore('@').replace('_', '-').ifBlank { "en-US" }

internal actual fun createPlatformLocaleDelegate(): PlatformLocaleDelegate =
    object : PlatformLocaleDelegate {
        override val current: LocaleList
            get() = LocaleList(listOf(Locale(currentLanguageTag())))
    }

private val rtlLanguages = setOf("ar", "fa", "he", "iw", "ji", "ur", "yi")

@InternalComposeUiApi actual fun Locale.isRtl(): Boolean = language in rtlLanguages
