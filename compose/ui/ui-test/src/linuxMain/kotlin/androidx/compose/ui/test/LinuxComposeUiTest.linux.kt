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

package androidx.compose.ui.test

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.test.TestResult

/** Runs a Compose UI test against the real SDL3/Skia Linux window host. */
@ExperimentalTestApi
fun runLinuxComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    runTestContext: CoroutineContext = EmptyCoroutineContext,
    testTimeout: Duration = Duration.INFINITE,
    block: suspend ComposeUiTest.() -> Unit,
): TestResult = runDesktopNativeComposeUiTest(effectContext, runTestContext, testTimeout, block)
