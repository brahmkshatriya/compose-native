/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.graphics

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.interpretCPointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkiaCanvas

internal class SkiaRasterImage(val width: Int, val height: Int) : AutoCloseable {
    val bitmap = Bitmap()
    private val skiaCanvas: SkiaCanvas
    private val pixmap: org.jetbrains.skia.Pixmap

    val imageBitmap: ImageBitmap
    val canvas: Canvas
    val pixels: CPointer<UByteVar>
    val stride: Int

    init {
        require(width > 0 && height > 0)
        check(bitmap.allocN32Pixels(width, height)) { "Could not allocate Skia raster pixels" }
        bitmap.erase(0)
        pixmap = checkNotNull(bitmap.peekPixels()) { "Could not access Skia raster pixels" }
        pixels = checkNotNull(interpretCPointer<UByteVar>(pixmap.addr))
        stride = pixmap.rowBytes
        imageBitmap = bitmap.asComposeImageBitmap()
        skiaCanvas = SkiaCanvas(bitmap)
        canvas = skiaCanvas.asComposeCanvas()
    }

    fun notifyPixelsChanged() {
        bitmap.notifyPixelsChanged()
    }

    override fun close() {
        skiaCanvas.close()
        pixmap.close()
        bitmap.close()
    }
}
