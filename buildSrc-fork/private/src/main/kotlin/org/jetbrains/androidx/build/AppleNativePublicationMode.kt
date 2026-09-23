/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jetbrains.androidx.build

import androidx.build.ProjectLayoutType.Companion.isJetBrainsFork
import org.gradle.api.Project

/** True for an isolated iOS/macOS publication graph used by the fork publication checks. */
internal fun Project.isJetBrainsAppleNativeOnlyPublication(): Boolean {
    if (!isJetBrainsFork(this)) return false
    val requested = providers.gradleProperty("compose.platforms").orNull ?: return false
    val platforms = requested.split(",").map(String::trim).filter(String::isNotEmpty)
    return platforms.isNotEmpty() &&
        platforms.all { it.startsWith("Ios") || it.startsWith("Macos") }
}

/** True when the isolated Apple publication contains only macOS targets. */
internal fun Project.isJetBrainsMacosNativeOnlyPublication(): Boolean {
    if (!isJetBrainsFork(this)) return false
    val requested = providers.gradleProperty("compose.platforms").orNull ?: return false
    val platforms = requested.split(",").map(String::trim).filter(String::isNotEmpty)
    return platforms.isNotEmpty() && platforms.all { it.startsWith("Macos") }
}
