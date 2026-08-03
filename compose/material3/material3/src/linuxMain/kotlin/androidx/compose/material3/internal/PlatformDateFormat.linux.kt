/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(kotlin.time.ExperimentalTime::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

internal actual class PlatformDateFormat actual constructor(private val locale: CalendarLocale) {
    actual val firstDayOfWeek: Int = 1

    actual val weekdayNames: List<Pair<String, String>> =
        listOf(
            "Monday" to "M",
            "Tuesday" to "T",
            "Wednesday" to "W",
            "Thursday" to "T",
            "Friday" to "F",
            "Saturday" to "S",
            "Sunday" to "S",
        )

    @OptIn(FormatStringsInDatetimeFormats::class)
    actual fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        cache: MutableMap<String, Any>,
    ): String {
        val date = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)
        val nativePattern =
            pattern
                .replace(
                    "EEEE",
                    date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.quoted(),
                )
                .replace(
                    "EEE",
                    date.dayOfWeek.name
                        .take(3)
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                        .quoted(),
                )
                .replace("MMMM", MonthNames[date.monthNumber - 1].quoted())
                .replace("MMM", MonthNames[date.monthNumber - 1].take(3).quoted())
        return date.format(LocalDateTime.Format { byUnicodePattern(nativePattern) })
    }

    actual fun formatWithSkeleton(
        utcTimeMillis: Long,
        skeleton: String,
        cache: MutableMap<String, Any>,
    ): String {
        val pattern =
            when (skeleton) {
                "yMMMd" -> "MMM d, yyyy"
                "yMMMMEEEEd" -> "EEEE, MMMM d, yyyy"
                "yMMMM" -> "MMMM yyyy"
                else -> skeleton
            }
        return formatWithPattern(utcTimeMillis, pattern, cache)
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

    actual fun getDateInputFormat(): DateInputFormat = datePatternAsInputFormat("yyyy-MM-dd")

    actual fun is24HourFormat(): Boolean = true
}

private val MonthNames =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

private fun String.quoted(): String = "'${replace("'", "''")}'"

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
