/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.window

import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoImage
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import cairo.kc_create
import cairo.kc_destroy
import cnames.structs.SDL_Window
import kotlin.math.ceil
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import linuxdesktop.kdrag_active
import linuxdesktop.kdrag_create
import linuxdesktop.kdrag_destroy
import linuxdesktop.kdrag_handle_syswm
import linuxdesktop.kdrag_pointer_motion
import linuxdesktop.kdrag_pointer_release
import linuxdesktop.kdrag_start
import linuxdesktop.kld_free_string

internal class SdlDragAndDropManager : PlatformDragAndDropManager, AutoCloseable {
    private var handle: COpaquePointer? = null
    private var closed = false

    override val isRequestDragAndDropTransferRequired: Boolean
        get() = handle != null

    /** Attaches to the native SDL backend. Unsupported/headless backends remain inert. */
    fun attach(window: CPointer<SDL_Window>) {
        if (closed || handle != null) return
        memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            handle = kdrag_create(window, error.ptr)
            error.value?.let {
                // Headless SDL drivers intentionally have no outgoing drag protocol.
                kld_free_string(it)
            }
        }
    }

    override fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset) {
        var started = false
        val scope =
            object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    val decoration = renderDecoration(decorationSize, drawDragDecoration)
                    try {
                        started = startTransfer(transferData, decoration)
                    } finally {
                        decoration?.close()
                    }
                    return started
                }
            }
        with(source) {
            scope.startDragAndDropTransfer(offset) { started }
        }
    }

    private fun startTransfer(
        data: DragAndDropTransferData,
        decoration: CairoImage?,
    ): Boolean {
        val native = handle ?: return false
        val text = data.text?.takeIf { it.isNotEmpty() }
        val uriList =
            data.files
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\r\n", postfix = "\r\n", transform = ::fileUri)
        if (text == null && uriList == null) return false
        return memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            error.value = null
            val started =
                kdrag_start(
                    native,
                    text,
                    uriList,
                    decoration?.surface?.data?.reinterpret(),
                    decoration?.width ?: 0,
                    decoration?.height ?: 0,
                    decoration?.surface?.stride ?: 0,
                    error.ptr,
                ) != 0
            error.value?.let {
                // Failure is reported through the Compose boolean contract.
                kld_free_string(it)
            }
            started
        }
    }

    private fun renderDecoration(
        size: Size,
        draw: DrawScope.() -> Unit,
    ): CairoImage? {
        val width = ceil(size.width).toInt()
        val height = ceil(size.height).toInt()
        if (width <= 0 || height <= 0) return null
        val image = CairoImage(width, height)
        image.surface.clear()
        val context = checkNotNull(kc_create(image.surface.handle))
        try {
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = CairoCanvas(context),
                size = Size(width.toFloat(), height.toFloat()),
                block = draw,
            )
        } finally {
            kc_destroy(context)
        }
        image.surface.flush()
        return image
    }

    fun pointerMotion() {
        handle?.let(::kdrag_pointer_motion)
    }

    fun pointerRelease() {
        handle?.let(::kdrag_pointer_release)
    }

    fun handleSysWm(message: COpaquePointer?) {
        val native = handle ?: return
        if (message != null) kdrag_handle_syswm(native, message)
    }

    val isActive: Boolean
        get() = handle?.let { kdrag_active(it) != 0 } == true

    override fun close() {
        if (closed) return
        closed = true
        handle?.let(::kdrag_destroy)
        handle = null
    }
}

internal fun fileUri(path: String): String {
    if (path.startsWith("file://")) return path
    val absolute = if (path.startsWith('/')) path else "/$path"
    return "file://" + percentEncodePath(absolute)
}

private fun percentEncodePath(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val safe =
            (unsigned in 'a'.code..'z'.code) ||
                (unsigned in 'A'.code..'Z'.code) ||
                (unsigned in '0'.code..'9'.code) ||
                unsigned == '/'.code ||
                unsigned == '-'.code ||
                unsigned == '_'.code ||
                unsigned == '.'.code ||
                unsigned == '~'.code
        if (safe) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(Hex[unsigned ushr 4])
            append(Hex[unsigned and 0x0f])
        }
    }
}

private const val Hex = "0123456789ABCDEF"
