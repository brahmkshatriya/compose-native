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

@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package androidx.compose.ui.test

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.atomic
import platform.posix.usleep

@OptIn(ExperimentalTestApi::class)
class LinuxComposeUiTestTest {
    @Test
    fun discoversAndInteractsWithRealSdlScene() =
        runLinuxComposeUiTest(testTimeout = 10.seconds) {
            setContent {
                var clicks by remember { mutableStateOf(0) }
                BasicText(
                    text = "Clicks: $clicks",
                    modifier = Modifier.testTag("counter").clickable { clicks += 1 },
                )
            }

            onNodeWithTag("counter").assertTextEquals("Clicks: 0").performClick()
            onNodeWithTag("counter").assertTextEquals("Clicks: 1")
        }

    @Test
    fun registersRootsFromSecondaryWindows() =
        runLinuxComposeUiTest(testTimeout = 10.seconds) {
            setContent {
                var showSecondary by remember { mutableStateOf(true) }
                BasicText(
                    text = "Toggle",
                    modifier = Modifier.testTag("toggle").clickable { showSecondary = false },
                )
                if (showSecondary) {
                    Window(
                        onCloseRequest = { showSecondary = false },
                        state = rememberWindowState(size = DpSize(160.dp, 100.dp)),
                        title = "Secondary test window",
                    ) {
                        BasicText("Secondary", Modifier.testTag("secondary"))
                    }
                }
            }

            onNodeWithTag("secondary").assertTextEquals("Secondary")
            onNodeWithTag("toggle").performClick()
            onNodeWithTag("secondary").assertDoesNotExist()
        }

    @Test
    fun registersRootsFromDialogWindows() =
        runLinuxComposeUiTest(testTimeout = 10.seconds) {
            setContent {
                DialogWindow(
                    onCloseRequest = {},
                    state =
                        androidx.compose.ui.window.rememberDialogState(
                            size = DpSize(160.dp, 100.dp)
                        ),
                    title = "Dialog test window",
                ) {
                    BasicText("Dialog", Modifier.testTag("dialog-node"))
                }
            }

            onNodeWithTag("dialog-node").assertTextEquals("Dialog")
        }

    @Test
    fun capturesNodePixelsFromOwningWindow() =
        runLinuxComposeUiTest(testTimeout = 10.seconds) {
            setContent { Box(Modifier.size(24.dp).background(Color.Red).testTag("red")) }

            val image = onNodeWithTag("red").captureToImage()
            val pixels = image.toPixelMap()
            assertTrue(image.width > 0 && image.height > 0)
            assertEquals(Color.Red, pixels[image.width / 2, image.height / 2])
        }

    @Test
    fun waitsForRegisteredIdlingResources() =
        runLinuxComposeUiTest(testTimeout = 10.seconds) {
            setContent { BasicText("Ready", Modifier.testTag("ready")) }
            val idle = atomic(false)
            val resource =
                object : IdlingResource {
                    override val isIdleNow: Boolean
                        get() = idle.value
                }
            assertTrue(registerIdlingResource(resource))
            val worker = Worker.start(name = "Linux idling-resource test")
            val future =
                worker.execute(TransferMode.UNSAFE, { idle }) {
                    usleep(50_000u)
                    it.value = true
                }
            try {
                waitForIdle()
                assertTrue(idle.value)
                onNodeWithTag("ready").assertTextEquals("Ready")
            } finally {
                future.result
                worker.requestTermination().result
                assertTrue(unregisterIdlingResource(resource))
            }
        }
}
