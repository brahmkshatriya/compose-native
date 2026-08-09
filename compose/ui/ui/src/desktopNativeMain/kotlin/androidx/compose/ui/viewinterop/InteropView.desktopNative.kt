/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.viewinterop

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.COpaquePointer

/** Pixel formats accepted by a [LinuxInteropView]. */
enum class InteropPixelFormat {
    /** Native-endian premultiplied ARGB32; BGRA bytes on little-endian Linux. */
    Argb8888Premultiplied
}

enum class InteropRenderBackend {
    Cpu,
    OpenGl,
}

/**
 * A framebuffer owned by Compose and temporarily lent to native code.
 *
 * The pointer is valid only for the duration of [LinuxInteropView.render]. Native integrations must
 * not retain it. [stride] is measured in bytes.
 */
class InteropRenderTarget(
    val pixels: COpaquePointer,
    val width: Int,
    val height: Int,
    val stride: Int,
    val format: InteropPixelFormat = InteropPixelFormat.Argb8888Premultiplied,
)

/** An OpenGL framebuffer owned by the Compose window's current GL context. */
class OpenGlInteropRenderTarget(
    val framebuffer: Int,
    val width: Int,
    val height: Int,
    val internalFormat: Int,
    val renderer: String,
    val density: Float = 1f,
)

enum class InteropPointerEventType {
    Move,
    Button,
    Scroll,
}

data class InteropPointerEvent(
    val type: InteropPointerEventType,
    val x: Float,
    val y: Float,
    val timeMillis: Long,
    val button: Int = 0,
    val pressed: Boolean = false,
    val scrollDeltaX: Float = 0f,
    val scrollDeltaY: Float = 0f,
    val modifiers: Int = 0,
)

data class InteropKeyEvent(
    val keyCode: Long,
    val codePoint: Int,
    val pressed: Boolean,
    val modifiers: Int = 0,
)

/**
 * A lifecycle-managed native renderer that can be placed in the Compose hierarchy by ui-sdl2's
 * `NativeView` composable.
 *
 * Rendering into a Compose-owned framebuffer works consistently on Wayland and X11, unlike foreign
 * child-window embedding, which is not a portable Wayland facility.
 */
class LinuxInteropView
private constructor(
    val backend: InteropRenderBackend,
    val continuousRendering: Boolean,
    private val cpuRenderer: ((InteropRenderTarget) -> Boolean)?,
    private val openGlRenderer: ((OpenGlInteropRenderTarget) -> Boolean)?,
    private val releaser: () -> Unit = {},
    private val pointerHandler: ((InteropPointerEvent) -> Boolean)? = null,
    private val keyHandler: ((InteropKeyEvent) -> Boolean)? = null,
    private val focusHandler: ((Boolean) -> Unit)? = null,
) {
    private val renderInvalidationCallback = atomic<(() -> Unit)?>(null)

    constructor(
        renderer: (InteropRenderTarget) -> Boolean,
        continuousRendering: Boolean = false,
        releaser: () -> Unit = {},
        pointerHandler: ((InteropPointerEvent) -> Boolean)? = null,
        keyHandler: ((InteropKeyEvent) -> Boolean)? = null,
        focusHandler: ((Boolean) -> Unit)? = null,
    ) : this(
        InteropRenderBackend.Cpu,
        continuousRendering,
        renderer,
        null,
        releaser,
        pointerHandler,
        keyHandler,
        focusHandler,
    )

    val acceptsInput: Boolean
        get() = pointerHandler != null || keyHandler != null

    /** Render the current native frame, returning true when the target was changed. */
    fun render(target: InteropRenderTarget): Boolean = checkNotNull(cpuRenderer)(target)

    fun renderOpenGl(target: OpenGlInteropRenderTarget): Boolean =
        checkNotNull(openGlRenderer)(target)

    fun sendPointerEvent(event: InteropPointerEvent): Boolean =
        pointerHandler?.invoke(event) == true

    fun sendKeyEvent(event: InteropKeyEvent): Boolean = keyHandler?.invoke(event) == true

    fun setFocused(focused: Boolean) = focusHandler?.invoke(focused)

    /** Requests another native render pass from the hosting [NativeView]. */
    fun requestRender() {
        renderInvalidationCallback.value?.invoke()
    }

    /**
     * Installs the callback used by the platform host to invalidate the Compose draw node. A
     * [LinuxInteropView] is owned by one [NativeView] at a time.
     */
    fun setRenderInvalidationCallback(callback: (() -> Unit)?) {
        renderInvalidationCallback.value = callback
    }

    /** Called once when the view leaves composition. */
    fun close() = releaser()

    companion object {
        fun openGl(
            renderer: (OpenGlInteropRenderTarget) -> Boolean,
            continuousRendering: Boolean = false,
            releaser: () -> Unit = {},
            pointerHandler: ((InteropPointerEvent) -> Boolean)? = null,
            keyHandler: ((InteropKeyEvent) -> Boolean)? = null,
            focusHandler: ((Boolean) -> Unit)? = null,
        ): LinuxInteropView =
            LinuxInteropView(
                InteropRenderBackend.OpenGl,
                continuousRendering,
                null,
                renderer,
                releaser,
                pointerHandler,
                keyHandler,
                focusHandler,
            )
    }
}

actual typealias InteropView = LinuxInteropView

internal actual typealias InteropViewGroup = Any
