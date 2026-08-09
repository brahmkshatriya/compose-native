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

@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package androidx.compose.ui.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.PlatformRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.platform.makeSynchronizedObject
import androidx.compose.ui.test.platform.synchronized
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

private const val DesktopNativeTestFrameMillis = 16L
private const val DesktopNativeIdlePollMillis = 1L

/** Runs a Compose UI test against the real SDL3/Skia desktop-native window host. */
@ExperimentalTestApi
@Suppress("UNUSED_PARAMETER")
internal fun runDesktopNativeComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    runTestContext: CoroutineContext = EmptyCoroutineContext,
    testTimeout: Duration = Duration.INFINITE,
    block: suspend ComposeUiTest.() -> Unit,
): TestResult {
    val environment = DesktopNativeComposeUiTestEnvironment(testTimeout)
    val worker = Worker.start(name = "Compose desktop-native UI test host")
    val applicationFuture =
        worker.execute(TransferMode.UNSAFE, { environment }) { it.runApplication() }

    val testResult = runCatching {
        environment.awaitHost()
        // The production SDL recomposer is not yet driven by effectContext. The parameter is
        // retained for API parity until deterministic frame-clock integration is added.
        runTest(context = runTestContext, timeout = testTimeout) { block(environment.test) }
    }

    environment.close()
    val applicationResult = runCatching { applicationFuture.result }
    worker.requestTermination().result
    environment.dispose()

    environment.applicationFailure?.let { throw it }
    applicationResult.getOrThrow()
    return testResult.getOrThrow()
}

private class DesktopNativeComposeUiTestEnvironment(private val timeout: Duration) {
    private val stateLock = makeSynchronizedObject()
    private val hostReady = atomic(false)
    private val closed = atomic(false)
    private var composeWindow: ComposeWindow? = null
    private var updateContent: ((@Composable () -> Unit) -> Unit)? = null
    private var exitApplication: (() -> Unit)? = null
    private var contentSet = false

    val rootRegistry = ComposeRootRegistry()
    val test = DesktopNativeComposeUiTest(this, timeout)
    var applicationFailure: Throwable? = null
        private set

    init {
        rootRegistry.setupRegistry()
    }

    fun runApplication() {
        try {
            application(exitProcessOnExit = false) {
                val applicationScope = this
                Window(
                    onCloseRequest = applicationScope::exitApplication,
                    state = rememberWindowState(size = DpSize(640.dp, 480.dp)),
                    visible = true,
                    title = "Compose desktop-native UI test",
                ) {
                    var content by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
                    val windowForTest = window
                    SideEffect {
                        attach(
                            window = windowForTest,
                            updateContent = { content = it },
                            exitApplication = applicationScope::exitApplication,
                        )
                    }
                    content?.invoke()
                }
            }
        } catch (failure: Throwable) {
            applicationFailure = failure
            hostReady.value = true
        }
    }

    private fun attach(
        window: ComposeWindow,
        updateContent: (@Composable () -> Unit) -> Unit,
        exitApplication: () -> Unit,
    ) {
        if (hostReady.value) return
        synchronized(stateLock) {
            if (hostReady.value) return@synchronized
            window.rootForTestListener = rootRegistry
            composeWindow = window
            this.updateContent = updateContent
            this.exitApplication = exitApplication
            hostReady.value = true
        }
        if (closed.value) exitApplication()
    }

    fun awaitHost() {
        val started = TimeSource.Monotonic.markNow()
        while (!hostReady.value) {
            applicationFailure?.let { throw it }
            checkTimeout(started, "starting the SDL test host")
            sleep(DesktopNativeIdlePollMillis)
        }
        applicationFailure?.let { throw it }
    }

    fun setContent(content: @Composable () -> Unit) {
        awaitHost()
        synchronized(stateLock) {
            check(!contentSet) { "setContent may only be called once per Compose UI test" }
            contentSet = true
        }
        runOnUiThread {
            checkNotNull(updateContent) { "The SDL test window is not attached" }.invoke(content)
        }
        test.waitForIdle()
    }

    fun <T> runOnUiThread(action: () -> T): T {
        awaitHost()
        return checkNotNull(composeWindow) { "The SDL test window is not attached" }
            .runOnUiThread(action)
    }

    fun hasPendingWork(): Boolean = runOnUiThread {
        Snapshot.sendApplyNotifications()
        rootRegistry.getComposeRoots().any { it.hasPendingMeasureOrLayout } ||
            checkNotNull(composeWindow).hasPendingTestWork ||
            Snapshot.current.hasPendingChanges() ||
            Snapshot.isApplyObserverNotificationPending
    }

    fun captureToImage(
        root: PlatformRootForTest,
        boundsInWindow: androidx.compose.ui.geometry.Rect,
    ): ImageBitmap =
        checkNotNull(composeWindow) { "The SDL test window is not attached" }
            .captureToImage(root, boundsInWindow)

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        if (!hostReady.value) return
        composeWindow?.runOnUiThread { exitApplication?.invoke() }
    }

    fun dispose() {
        rootRegistry.tearDownRegistry()
    }

    fun checkTimeout(started: TimeMark, operation: String) {
        if (timeout != Duration.INFINITE && started.elapsedNow() > timeout) {
            throw ComposeTimeoutException("Timed out while $operation after $timeout")
        }
    }
}

@ExperimentalTestApi
private class DesktopNativeComposeUiTest(
    private val environment: DesktopNativeComposeUiTestEnvironment,
    private val timeout: Duration,
) : ComposeUiTest, IdlingResourceOwner {
    override val density: Density = Density(1f)
    private val clock = DesktopNativeMainTestClock(::waitForIdle)
    override val mainClock: MainTestClock
        get() = clock

    private val owner = DesktopNativeTestOwner()
    private val context = TestContext(owner)
    private val idlingResourceLock = makeSynchronizedObject()
    private val idlingResources = mutableSetOf<IdlingResource>()

    override fun <T> runOnUiThread(action: () -> T): T = environment.runOnUiThread(action)

    override fun <T> runOnIdle(action: () -> T): T {
        waitForIdle()
        return runOnUiThread(action)
    }

    override fun <T> runWithoutImplicitWait(block: () -> T): T {
        val previous = owner.isImplicitWaitSuppressed
        owner.isImplicitWaitSuppressed = true
        return try {
            block()
        } finally {
            owner.isImplicitWaitSuppressed = previous
        }
    }

    override fun waitForIdle() {
        val started = TimeSource.Monotonic.markNow()
        while (hasPendingWork()) {
            if (timeout != Duration.INFINITE && started.elapsedNow() > timeout) {
                throw ComposeTimeoutException("waitForIdle timed out after $timeout")
            }
            sleep(DesktopNativeIdlePollMillis)
        }
    }

    override suspend fun awaitIdle() {
        waitForIdle()
    }

    override fun waitUntil(
        conditionDescription: String?,
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (started.elapsedNow().inWholeMilliseconds > timeoutMillis) {
                throw ComposeTimeoutException(
                    buildWaitUntilTimeoutMessage(timeoutMillis, conditionDescription)
                )
            }
            if (mainClock.autoAdvance) waitForIdle()
            sleep(DesktopNativeIdlePollMillis)
        }
    }

    override fun setContent(composable: @Composable () -> Unit) {
        environment.setContent(composable)
    }

    override fun hasPendingWork(): Boolean =
        environment.hasPendingWork() || !areAllIdlingResourcesIdle()

    private fun areAllIdlingResourcesIdle(): Boolean =
        synchronized(idlingResourceLock) { idlingResources.all { it.isIdleNow } }

    override fun registerIdlingResource(idlingResource: IdlingResource) {
        synchronized(idlingResourceLock) { idlingResources += idlingResource }
    }

    override fun unregisterIdlingResource(idlingResource: IdlingResource) {
        synchronized(idlingResourceLock) { idlingResources -= idlingResource }
    }

    override fun onNode(
        matcher: SemanticsMatcher,
        useUnmergedTree: Boolean,
    ): SemanticsNodeInteraction = SemanticsNodeInteraction(context, useUnmergedTree, matcher)

    override fun onAllNodes(
        matcher: SemanticsMatcher,
        useUnmergedTree: Boolean,
    ): SemanticsNodeInteractionCollection =
        SemanticsNodeInteractionCollection(context, useUnmergedTree, matcher)

    private inner class DesktopNativeTestOwner : TestOwner, DesktopNativeScreenshotTestOwner {
        override var isImplicitWaitSuppressed: Boolean = false
        override val mainClock: MainTestClock
            get() = clock

        override fun <T> runOnUiThread(action: () -> T): T = environment.runOnUiThread(action)

        override fun getRoots(atLeastOneRootExpected: Boolean): Set<RootForTest> {
            if (!isImplicitWaitSuppressed) waitForIdle()
            val roots = environment.rootRegistry.getComposeRoots()
            if (atLeastOneRootExpected && roots.isEmpty()) {
                throw AssertionError("No Compose roots were registered by the SDL test host")
            }
            return roots
        }

        override fun runCurrent() {
            clock.scheduler.runCurrent()
        }

        override fun captureToImage(semanticsNode: SemanticsNode): ImageBitmap {
            if (!isImplicitWaitSuppressed) waitForIdle()
            val root =
                semanticsNode.root as? PlatformRootForTest
                    ?: error(
                        "The semantics node is not attached to a desktop-native Compose test root"
                    )
            return environment.captureToImage(root, semanticsNode.boundsInWindow)
        }
    }
}

private interface DesktopNativeScreenshotTestOwner {
    fun captureToImage(semanticsNode: SemanticsNode): ImageBitmap
}

/** Captures the composed SDL window region occupied by this semantics node. */
fun SemanticsNodeInteraction.captureToImage(): ImageBitmap {
    val semanticsNode = fetchSemanticsNode("Failed to capture a node to bitmap.")
    return (testContext.testOwner as? DesktopNativeScreenshotTestOwner)?.captureToImage(
        semanticsNode
    ) ?: error("captureToImage is only available inside runComposeUiTest")
}

private class DesktopNativeMainTestClock(private val waitForIdle: () -> Unit) : MainTestClock {
    override val scheduler = TestCoroutineScheduler()
    override val currentTime: Long
        get() = scheduler.currentTime

    override var autoAdvance: Boolean = true

    override fun advanceTimeByFrame() {
        advanceTimeBy(DesktopNativeTestFrameMillis)
    }

    override fun advanceTimeBy(milliseconds: Long, ignoreFrameDuration: Boolean) {
        require(milliseconds >= 0) { "milliseconds must be non-negative" }
        val duration =
            if (ignoreFrameDuration || milliseconds == 0L) {
                milliseconds
            } else {
                ((milliseconds + DesktopNativeTestFrameMillis - 1) / DesktopNativeTestFrameMillis) *
                    DesktopNativeTestFrameMillis
            }
        scheduler.advanceTimeBy(duration)
        if (duration > 0) sleep(duration)
        waitForIdle()
    }

    override fun advanceTimeUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val start = currentTime
        while (!condition()) {
            if (currentTime - start >= timeoutMillis) {
                throw ComposeTimeoutException(
                    buildWaitUntilTimeoutMessage(timeoutMillis, "main clock condition")
                )
            }
            advanceTimeByFrame()
        }
    }
}
