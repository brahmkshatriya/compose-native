/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.viewinterop

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.COpaquePointer

/** Pixel formats accepted by a [NativeInteropView]. */
enum class InteropPixelFormat {
    /** Native-endian premultiplied ARGB32; BGRA bytes on little-endian desktop targets. */
    Argb8888Premultiplied
}

/** Rendering backend used by a [NativeInteropView]. */
enum class InteropRenderBackend {
    /** Renders into a Compose-owned CPU pixel buffer. */
    Cpu,

    /** Renders into an OpenGL framebuffer owned by the current Compose window. */
    OpenGl,
}

/**
 * A framebuffer owned by Compose and temporarily lent to native code.
 *
 * The pointer is valid only for the duration of [NativeInteropView.render]. Native integrations
 * must not retain it. [stride] is measured in bytes.
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

/** Type of pointer input forwarded to a [NativeInteropView]. */
enum class InteropPointerEventType {
    /** Pointer movement without an associated button transition. */
    Move,

    /** Pointer-button press or release. */
    Button,

    /** Pointer-wheel or trackpad scrolling. */
    Scroll,
}

/**
 * Pointer input forwarded from Compose to a [NativeInteropView].
 *
 * Fields that are not relevant for [type] keep their default values.
 */
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

/** Keyboard input forwarded from Compose to a [NativeInteropView]. */
data class InteropKeyEvent(
    val keyCode: Long,
    val codePoint: Int,
    val pressed: Boolean,
    val modifiers: Int = 0,
)

/**
 * A lifecycle-managed native renderer that can be placed in the Compose hierarchy by the native
 * desktop host's `NativeView` composable.
 *
 * Rendering into a Compose-owned framebuffer preserves Compose transforms, clipping, and sibling
 * composition without relying on platform child-window embedding.
 */
class NativeInteropView
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

    /**
     * Creates an interop view that renders into a Compose-owned CPU framebuffer.
     *
     * [renderer] is called when Compose needs a frame and should return `true` when it modified the
     * target. [releaser] is called when the view leaves composition. Input callbacks return `true`
     * when the event was consumed.
     *
     * @param renderer renders the current frame into the supplied target
     * @param continuousRendering whether Compose should request frames continuously
     * @param releaser releases native resources owned by this view
     * @param pointerHandler optional pointer-input handler
     * @param keyHandler optional keyboard-input handler
     * @param focusHandler optional callback invoked when focus changes
     */
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

    /** Whether this view has a pointer or keyboard input handler. */
    val acceptsInput: Boolean
        get() = pointerHandler != null || keyHandler != null

    /** Render the current native frame, returning true when the target was changed. */
    fun render(target: InteropRenderTarget): Boolean = checkNotNull(cpuRenderer)(target)

    /** Renders an OpenGL frame, returning `true` when the target was changed. */
    fun renderOpenGl(target: OpenGlInteropRenderTarget): Boolean =
        checkNotNull(openGlRenderer)(target)

    /** Dispatches [event] to the pointer handler, returning whether it was consumed. */
    fun sendPointerEvent(event: InteropPointerEvent): Boolean =
        pointerHandler?.invoke(event) == true

    /** Dispatches [event] to the keyboard handler, returning whether it was consumed. */
    fun sendKeyEvent(event: InteropKeyEvent): Boolean = keyHandler?.invoke(event) == true

    /** Notifies the native integration that its Compose host focus changed. */
    fun setFocused(focused: Boolean) = focusHandler?.invoke(focused)

    /** Requests another native render pass from the hosting [NativeView]. */
    fun requestRender() {
        renderInvalidationCallback.value?.invoke()
    }

    /**
     * Installs the callback used by the platform host to invalidate the Compose draw node. A
     * [NativeInteropView] is owned by one [NativeView] at a time.
     */
    fun setRenderInvalidationCallback(callback: (() -> Unit)?) {
        renderInvalidationCallback.value = callback
    }

    /** Called once when the view leaves composition. */
    fun close() = releaser()

    companion object {
        /**
         * Creates an interop view that renders into an OpenGL framebuffer owned by Compose.
         *
         * [renderer] runs with the Compose window's OpenGL context current and should return `true`
         * when it modified the target. [releaser] is called when the view leaves composition.
         *
         * @param renderer renders the current frame into the supplied OpenGL target
         * @param continuousRendering whether Compose should request frames continuously
         * @param releaser releases native resources owned by this view
         * @param pointerHandler optional pointer-input handler
         * @param keyHandler optional keyboard-input handler
         * @param focusHandler optional callback invoked when focus changes
         */
        fun openGl(
            renderer: (OpenGlInteropRenderTarget) -> Boolean,
            continuousRendering: Boolean = false,
            releaser: () -> Unit = {},
            pointerHandler: ((InteropPointerEvent) -> Boolean)? = null,
            keyHandler: ((InteropKeyEvent) -> Boolean)? = null,
            focusHandler: ((Boolean) -> Unit)? = null,
        ): NativeInteropView =
            NativeInteropView(
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

actual typealias InteropView = NativeInteropView

internal actual typealias InteropViewGroup = Any
