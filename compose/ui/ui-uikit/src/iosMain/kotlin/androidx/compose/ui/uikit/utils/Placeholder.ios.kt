/*
 * Copyright 2023 The Android Open Source Project
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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.uikit.utils

/** Initializes the process-wide Compose UI OS trace logger. */
fun initializeAppTraceLogger(name: String) {
    CMPOSInitializeAppTraceLogger(name)
}

/** Starts an OS trace interval without exposing the Objective-C cinterop type to consumers. */
fun beginAppTraceInterval(name: String): Any? = CMPOSAppTraceLogger()?.beginIntervalNamed(name)

/** Ends an OS trace interval previously returned by [beginAppTraceInterval]. */
fun endAppTraceInterval(interval: Any?) {
    (interval as? CMPOSLoggerInterval)?.let { CMPOSAppTraceLogger()?.endInterval(it) }
}
