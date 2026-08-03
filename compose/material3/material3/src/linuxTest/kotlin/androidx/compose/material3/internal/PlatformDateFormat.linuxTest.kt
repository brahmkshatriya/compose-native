/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(kotlin.time.ExperimentalTime::class)

package androidx.compose.material3.internal

import androidx.compose.ui.text.intl.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal class PlatformDateFormatLinuxTest {
    private val formatter = PlatformDateFormat(Locale("en-US"))
    private val date = LocalDateTime(2022, 1, 1, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    @Test
    fun formatsLocalizedMonthAndWeekdayDirectives() {
        assertEquals(
            "Saturday, January 1, 2022",
            formatter.formatWithPattern(date, "EEEE, MMMM d, yyyy", mutableMapOf()),
        )
        assertEquals(
            "Jan 1, 2022",
            formatter.formatWithPattern(date, "MMM d, yyyy", mutableMapOf()),
        )
    }

    @Test
    fun formatsDatePickerMonthSkeleton() {
        assertEquals("January 2022", formatter.formatWithSkeleton(date, "yMMMM", mutableMapOf()))
    }

    @Test
    fun parsesDatePickerInputWithoutDelimiters() {
        val parsed = formatter.parse("20260803", "yyyyMMdd", Locale("en-US"), mutableMapOf())

        assertEquals(2026, parsed?.year)
        assertEquals(8, parsed?.month)
        assertEquals(3, parsed?.dayOfMonth)
    }
}
