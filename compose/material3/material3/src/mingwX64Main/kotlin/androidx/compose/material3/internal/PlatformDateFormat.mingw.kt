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

@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import kotlin.time.Instant
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.windows.DATE_LONGDATE
import platform.windows.GetDateFormatEx
import platform.windows.GetLocaleInfoEx
import platform.windows.LOCALE_IFIRSTDAYOFWEEK
import platform.windows.LOCALE_SDAYNAME1
import platform.windows.LOCALE_SDAYNAME2
import platform.windows.LOCALE_SDAYNAME3
import platform.windows.LOCALE_SDAYNAME4
import platform.windows.LOCALE_SDAYNAME5
import platform.windows.LOCALE_SDAYNAME6
import platform.windows.LOCALE_SDAYNAME7
import platform.windows.LOCALE_SSHORTDATE
import platform.windows.LOCALE_SSHORTESTDAYNAME1
import platform.windows.LOCALE_SSHORTESTDAYNAME2
import platform.windows.LOCALE_SSHORTESTDAYNAME3
import platform.windows.LOCALE_SSHORTESTDAYNAME4
import platform.windows.LOCALE_SSHORTESTDAYNAME5
import platform.windows.LOCALE_SSHORTESTDAYNAME6
import platform.windows.LOCALE_SSHORTESTDAYNAME7
import platform.windows.LOCALE_STIMEFORMAT
import platform.windows.SYSTEMTIME
import platform.windows.WCHARVar

internal actual class PlatformDateFormat actual constructor(private val locale: CalendarLocale) {
    private val localeName = locale.toLanguageTag()

    actual val firstDayOfWeek: Int
        get() = localeInfo(LOCALE_IFIRSTDAYOFWEEK).toIntOrNull()?.plus(1) ?: 1

    actual val weekdayNames: List<Pair<String, String>>
        get() =
            DayNameTypes.zip(ShortDayNameTypes) { longName, shortName ->
                localeInfo(longName) to localeInfo(shortName)
            }

    actual fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        cache: MutableMap<String, Any>,
    ): String = formatDate(utcTimeMillis, pattern.toWindowsDatePattern())

    actual fun formatWithSkeleton(
        utcTimeMillis: Long,
        skeleton: String,
        cache: MutableMap<String, Any>,
    ): String =
        when (skeleton) {
            "yMMMd" -> formatDate(utcTimeMillis, "MMM d, yyyy")
            "yMMMMEEEEd" -> formatDate(utcTimeMillis, pattern = null, flags = DATE_LONGDATE)
            "yMMMM" -> formatDate(utcTimeMillis, "MMMM yyyy")
            else -> formatDate(utcTimeMillis, skeleton.toWindowsDatePattern())
        }

    actual fun parse(
        date: String,
        pattern: String,
        locale: CalendarLocale,
        cache: MutableMap<String, Any>,
    ): CalendarDate? =
        try {
            parseNumericDate(date, pattern)
                .atTime(Midnight)
                .toInstant(TimeZone.UTC)
                .toCalendarDate(TimeZone.UTC)
        } catch (_: Throwable) {
            null
        }

    actual fun getDateInputFormat(): DateInputFormat =
        datePatternAsInputFormat(localeInfo(LOCALE_SSHORTDATE))

    actual fun is24HourFormat(): Boolean = 'H' in localeInfo(LOCALE_STIMEFORMAT)

    private fun localeInfo(type: Int): String = memScoped {
        val size = GetLocaleInfoEx(localeName, type.toUInt(), null, 0)
        if (size <= 0) return@memScoped ""
        val value = allocArray<WCHARVar>(size)
        if (GetLocaleInfoEx(localeName, type.toUInt(), value, size) <= 0) "" else value.toKString()
    }

    private fun formatDate(utcTimeMillis: Long, pattern: String?, flags: Int = 0): String =
        memScoped {
            val date = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)
            val systemTime = alloc<SYSTEMTIME>()
            systemTime.wYear = date.year.toUShort()
            systemTime.wMonth = date.monthNumber.toUShort()
            systemTime.wDay = date.dayOfMonth.toUShort()
            systemTime.wDayOfWeek = (date.dayOfWeek.ordinal + 1).rem(7).toUShort()
            systemTime.wHour = date.hour.toUShort()
            systemTime.wMinute = date.minute.toUShort()
            systemTime.wSecond = date.second.toUShort()
            systemTime.wMilliseconds = date.nanosecond.div(1_000_000).toUShort()

            val size =
                GetDateFormatEx(localeName, flags.toUInt(), systemTime.ptr, pattern, null, 0, null)
            if (size <= 0) return@memScoped ""
            val value = allocArray<WCHARVar>(size)
            if (
                GetDateFormatEx(
                    localeName,
                    flags.toUInt(),
                    systemTime.ptr,
                    pattern,
                    value,
                    size,
                    null,
                ) <= 0
            ) {
                ""
            } else {
                value.toKString()
            }
        }
}

private val DayNameTypes =
    listOf(
        LOCALE_SDAYNAME1,
        LOCALE_SDAYNAME2,
        LOCALE_SDAYNAME3,
        LOCALE_SDAYNAME4,
        LOCALE_SDAYNAME5,
        LOCALE_SDAYNAME6,
        LOCALE_SDAYNAME7,
    )

private val ShortDayNameTypes =
    listOf(
        LOCALE_SSHORTESTDAYNAME1,
        LOCALE_SSHORTESTDAYNAME2,
        LOCALE_SSHORTESTDAYNAME3,
        LOCALE_SSHORTESTDAYNAME4,
        LOCALE_SSHORTESTDAYNAME5,
        LOCALE_SSHORTESTDAYNAME6,
        LOCALE_SSHORTESTDAYNAME7,
    )

private fun String.toWindowsDatePattern(): String =
    replace("EEEE", "dddd").replace("EEE", "ddd").replace('L', 'M')

private fun parseNumericDate(value: String, pattern: String): LocalDate {
    require(value.length == pattern.length)
    var year: Int? = null
    var month: Int? = null
    var day: Int? = null
    var index = 0
    while (index < pattern.length) {
        val directive = pattern[index]
        if (directive == 'y' || directive == 'M' || directive == 'd') {
            var end = index + 1
            while (end < pattern.length && pattern[end] == directive) end++
            val number = value.substring(index, end).also { require(it.all(Char::isDigit)) }.toInt()
            when (directive) {
                'y' -> year = number
                'M' -> month = number
                'd' -> day = number
            }
            index = end
        } else {
            require(value[index] == directive)
            index++
        }
    }
    return LocalDate(checkNotNull(year), checkNotNull(month), checkNotNull(day))
}
