/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import androidx.compose.ui.text.intl.Locale

actual fun calendarLocale(language: String, country: String): CalendarLocale =
    Locale("$language-$country")

actual val supportsDateSkeleton: Boolean
    get() = true

actual fun setTimeZone(id: String) = Unit

actual fun getTimeZone(): String = "UTC"
