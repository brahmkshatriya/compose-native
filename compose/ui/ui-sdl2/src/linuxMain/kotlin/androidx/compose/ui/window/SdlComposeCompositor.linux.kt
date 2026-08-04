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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.window

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.cairo.CairoCanvas
import androidx.compose.ui.graphics.cairo.CairoLayerCompositor
import androidx.compose.ui.graphics.cairo.CairoLayerRegistration
import androidx.compose.ui.graphics.cairo.CairoSurface
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.GpuInteropCompositionRecorder
import androidx.compose.ui.viewinterop.GpuInteropLayerCommand
import cairo.kc_create
import cairo.kc_destroy
import cairo.kc_get_group_target
import cairo.kgpu_context_begin
import cairo.kgpu_context_create
import cairo.kgpu_context_destroy
import cairo.kgpu_context_make_current
import cairo.kgpu_context_present
import cairo.kgpu_context_renderer
import cairo.kgpu_texture_create
import cairo.kgpu_texture_destroy
import cairo.kgpu_texture_draw
import cairo.kgpu_texture_upload
import cairo.kgpu_texture_upload_region
import cnames.structs.SDL_Window
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString

internal data class SdlGpuUploadResult(
    val bytes: Int,
    val reallocated: Boolean,
)

/** Persistent OpenGL texture whose storage survives between compositor frames. */
internal class SdlGpuTexture(
    private val context: COpaquePointer,
    private val cpuTopDown: Boolean,
) : AutoCloseable {
    private val handle = checkNotNull(kgpu_texture_create())
    private var size = IntSize.Zero
    private var closed = false

    fun upload(
        surface: CairoSurface,
        width: Int,
        height: Int,
        damage: FrameDamage?,
    ): SdlGpuUploadResult {
        check(!closed) { "Texture is closed" }
        val nextSize = IntSize(width, height)
        val reallocated = size != nextSize
        size = nextSize
        val bytes =
            kgpu_texture_upload(
                context,
                handle,
                surface.data.reinterpret(),
                width,
                height,
                surface.stride,
                damage?.x ?: 0,
                damage?.y ?: 0,
                damage?.width ?: 0,
                damage?.height ?: 0,
            )
        return SdlGpuUploadResult(bytes, reallocated)
    }

    fun uploadRegion(
        surface: CairoSurface,
        textureWidth: Int,
        textureHeight: Int,
        destinationX: Int,
        destinationY: Int,
    ): SdlGpuUploadResult {
        check(!closed) { "Texture is closed" }
        val nextSize = IntSize(textureWidth, textureHeight)
        val reallocated = size != nextSize
        size = nextSize
        val bytes =
            kgpu_texture_upload_region(
                context,
                handle,
                surface.data.reinterpret(),
                surface.width,
                surface.height,
                surface.stride,
                textureWidth,
                textureHeight,
                destinationX,
                destinationY,
            )
        return SdlGpuUploadResult(bytes, reallocated)
    }

    fun draw(x: Float, y: Float, width: Float, height: Float) {
        check(!closed) { "Texture is closed" }
        kgpu_texture_draw(
            context,
            handle,
            x,
            y,
            width,
            height,
            if (cpuTopDown) 1 else 0,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        kgpu_texture_destroy(context, handle)
    }
}

internal data class SdlCompositorStatsSnapshot(
    val startedFrames: Long,
    val presentedFrames: Long,
    val rootTextureAllocations: Long,
    val rootUploads: Long,
    val rootUploadedBytes: Long,
    val rootCommands: Long,
    val externalCommands: Long,
    val createdLayers: Long,
    val activeLayers: Int,
    val layerContentChanges: Long,
    val layerPropertyChanges: Long,
)

internal class SdlCompositorCounters {
    private var startedFrames = 0L
    private var presentedFrames = 0L
    private var rootTextureAllocations = 0L
    private var rootUploads = 0L
    private var rootUploadedBytes = 0L
    private var rootCommands = 0L
    private var externalCommands = 0L
    private var createdLayers = 0L
    private var activeLayers = 0
    private var layerContentChanges = 0L
    private var layerPropertyChanges = 0L

    fun onFrameStarted() {
        startedFrames++
    }

    fun onFramePresented() {
        presentedFrames++
    }

    fun onRootTextureAllocated() {
        rootTextureAllocations++
    }

    fun onRootUploaded(bytes: Int) {
        if (bytes <= 0) return
        rootUploads++
        rootUploadedBytes += bytes
    }

    fun onRootCommand() {
        rootCommands++
    }

    fun onExternalCommands(count: Int) {
        if (count > 0) externalCommands += count
    }

    fun onLayerCreated() {
        createdLayers++
        activeLayers++
    }

    fun onLayerReleased() {
        check(activeLayers > 0) { "Compositor layer accounting underflow" }
        activeLayers--
    }

    fun onLayerContentChanged() {
        layerContentChanges++
    }

    fun onLayerPropertiesChanged() {
        layerPropertyChanges++
    }

    fun snapshot() =
        SdlCompositorStatsSnapshot(
            startedFrames = startedFrames,
            presentedFrames = presentedFrames,
            rootTextureAllocations = rootTextureAllocations,
            rootUploads = rootUploads,
            rootUploadedBytes = rootUploadedBytes,
            rootCommands = rootCommands,
            externalCommands = externalCommands,
            createdLayers = createdLayers,
            activeLayers = activeLayers,
            layerContentChanges = layerContentChanges,
            layerPropertyChanges = layerPropertyChanges,
        )
}

internal enum class SdlOrderedFallbackReason {
    None,
    NonRootCanvas,
    NestedCairoGroup,
}

internal class SdlOrderedFrameCapture(
    val width: Int,
    val height: Int,
    val damage: FrameDamage,
    val segments: List<CairoSurface>,
    val externalLayers: List<GpuInteropLayerCommand>,
    val fallbackReason: SdlOrderedFallbackReason,
) : AutoCloseable {
    val topology: List<Long> = externalLayers.map(GpuInteropLayerCommand::compositionId)

    val isFullFrame: Boolean =
        damage.x == 0 && damage.y == 0 && damage.width == width && damage.height == height

    override fun close() {
        segments.forEach(CairoSurface::close)
    }
}

/**
 * Splits one root Cairo draw into ordered CPU segments without replacing the active Cairo target.
 *
 * At a NativeView boundary the damaged rectangle is copied out, then cleared on the root scratch
 * surface. Compose continues on the same cairo_t, so every transform, clip, save, and saveLayer
 * remains intact. The captured segments are also replayed into the persistent root surface to keep
 * the conservative single-texture fallback valid.
 */
internal class SdlOrderedCompositionRecorder(
    private val rootCanvas: CairoCanvas,
    private val rootSurface: CairoSurface,
    private val width: Int,
    private val height: Int,
    private val damage: FrameDamage,
) : GpuInteropCompositionRecorder {
    private val segments = mutableListOf<CairoSurface>()
    private val externalLayers = mutableListOf<GpuInteropLayerCommand>()
    private val masksInRoot = mutableListOf<Path>()
    private var fallbackReason = SdlOrderedFallbackReason.None
    private var finished = false

    override fun emit(
        layer: GpuInteropLayerCommand,
        canvas: Canvas,
        maskInRoot: Path,
    ): Boolean {
        check(!finished) { "Ordered composition recording already finished" }
        val cairoCanvas = canvas as? CairoCanvas
        if (cairoCanvas == null || cairoCanvas.context != rootCanvas.context) {
            cairoCanvas?.markExternalBoundary()
            fallbackReason = SdlOrderedFallbackReason.NonRootCanvas
            return false
        }
        if (kc_get_group_target(cairoCanvas.context) != rootSurface.handle) {
            fallbackReason = SdlOrderedFallbackReason.NestedCairoGroup
            return false
        }
        captureSegment()
        externalLayers += layer
        masksInRoot += maskInRoot
        return true
    }

    private fun captureSegment() {
        rootSurface.flush()
        val snapshot = CairoSurface(damage.width, damage.height)
        snapshot.clear()
        val context = checkNotNull(kc_create(snapshot.handle))
        try {
            CairoCanvas(context)
                .drawSurface(
                    rootSurface,
                    -damage.x.toFloat(),
                    -damage.y.toFloat(),
                    1f,
                    BlendMode.Src,
                )
        } finally {
            kc_destroy(context)
        }
        snapshot.flush()
        segments += snapshot
        rootSurface.clear(damage.rect)
    }

    private fun rebuildFallbackRoot() {
        rootSurface.clear(damage.rect)
        val context = checkNotNull(kc_create(rootSurface.handle))
        try {
            val canvas = CairoCanvas(context)
            canvas.save()
            canvas.clipRect(
                damage.rect.left,
                damage.rect.top,
                damage.rect.right,
                damage.rect.bottom,
            )
            segments.forEachIndexed { index, segment ->
                canvas.drawSurface(
                    segment,
                    damage.x.toFloat(),
                    damage.y.toFloat(),
                    1f,
                    BlendMode.SrcOver,
                )
                if (index < masksInRoot.size) {
                    canvas.clearInteropPathInRoot(masksInRoot[index])
                }
            }
            canvas.restore()
        } finally {
            kc_destroy(context)
        }
        rootSurface.flush()
    }

    fun finish(): SdlOrderedFrameCapture {
        check(!finished) { "Ordered composition recording already finished" }
        finished = true
        if (externalLayers.isNotEmpty()) {
            captureSegment()
            rebuildFallbackRoot()
        }
        return SdlOrderedFrameCapture(
            width = width,
            height = height,
            damage = damage,
            segments = segments.toList(),
            externalLayers = externalLayers.toList(),
            fallbackReason = fallbackReason,
        )
    }
}

private class SdlOrderedCompositionState(
    val topology: List<Long>,
    val textures: List<SdlGpuTexture>,
    var externalLayers: List<GpuInteropLayerCommand>,
) : AutoCloseable {
    override fun close() {
        textures.forEach(SdlGpuTexture::close)
    }
}

/** Per-window owner of OpenGL composition resources and the ordered command stream. */
internal class SdlComposeCompositor private constructor(
    val context: COpaquePointer,
) : CairoLayerCompositor, AutoCloseable {
    private val rootTexture = SdlGpuTexture(context, cpuTopDown = true)
    private val counters = SdlCompositorCounters()
    private var orderedState: SdlOrderedCompositionState? = null
    private var orderedThisFrame = false
    private var rootTextureDirty = false
    private var nextLayerId = 1L
    private var closed = false

    val renderer: String = kgpu_context_renderer(context)?.toKString() ?: "unknown renderer"

    var lastRootUploadBytes: Int = 0
        private set

    var lastFrameCommands: Int = 0
        private set

    var lastOrderedFallbackReason = SdlOrderedFallbackReason.None
        private set

    fun makeCurrent() {
        check(!closed) { "Compositor is closed" }
        kgpu_context_make_current(context)
    }

    fun beginFrame(width: Int, height: Int) {
        check(!closed) { "Compositor is closed" }
        counters.onFrameStarted()
        lastRootUploadBytes = 0
        lastFrameCommands = 0
        lastOrderedFallbackReason = SdlOrderedFallbackReason.None
        orderedThisFrame = orderedState != null
        currentWidth = width
        currentHeight = height
        kgpu_context_begin(context, width, height)
    }

    fun beginOrderedRecording(
        rootCanvas: CairoCanvas,
        rootSurface: CairoSurface,
        width: Int,
        height: Int,
        damage: FrameDamage,
    ): SdlOrderedCompositionRecorder =
        SdlOrderedCompositionRecorder(rootCanvas, rootSurface, width, height, damage)

    /**
     * Commits a captured stream. Returns true when topology changed during bounded damage and a
     * conservative full redraw is required before the new segment textures can become authoritative.
     */
    fun commitOrderedRecording(capture: SdlOrderedFrameCapture): Boolean {
        check(!closed) { "Compositor is closed" }
        try {
            lastOrderedFallbackReason = capture.fallbackReason
            if (capture.fallbackReason != SdlOrderedFallbackReason.None) {
                orderedState?.close()
                orderedState = null
                orderedThisFrame = false
                if (capture.segments.isNotEmpty()) rootTextureDirty = true
                return capture.fallbackReason == SdlOrderedFallbackReason.NonRootCanvas
            }

            if (capture.externalLayers.isEmpty()) {
                val hadOrderedState = orderedState != null
                if (capture.isFullFrame) {
                    orderedState?.close()
                    orderedState = null
                }
                orderedThisFrame = false
                return hadOrderedState && !capture.isFullFrame
            }

            rootTextureDirty = true
            val current = orderedState
            val topologyMatches =
                current != null &&
                    current.topology == capture.topology &&
                    current.textures.size == capture.segments.size
            if (!topologyMatches && !capture.isFullFrame) {
                orderedThisFrame = false
                return true
            }

            val state =
                if (topologyMatches) {
                    checkNotNull(current)
                } else {
                    current?.close()
                    SdlOrderedCompositionState(
                            topology = capture.topology,
                            textures =
                                List(capture.segments.size) {
                                    SdlGpuTexture(context, cpuTopDown = true)
                                },
                            externalLayers = capture.externalLayers,
                        )
                        .also { orderedState = it }
                }
            state.externalLayers = capture.externalLayers

            var uploadedBytes = 0
            state.textures.zip(capture.segments).forEach { (texture, segment) ->
                val upload =
                    texture.uploadRegion(
                        surface = segment,
                        textureWidth = capture.width,
                        textureHeight = capture.height,
                        destinationX = capture.damage.x,
                        destinationY = capture.damage.y,
                    )
                if (upload.reallocated) counters.onRootTextureAllocated()
                counters.onRootUploaded(upload.bytes)
                uploadedBytes += upload.bytes
            }
            lastRootUploadBytes = uploadedBytes
            orderedThisFrame = true
            return false
        } finally {
            capture.close()
        }
    }

    fun drawOrderedContent(): Boolean {
        check(!closed) { "Compositor is closed" }
        if (!orderedThisFrame) return false
        val state = orderedState ?: return false
        state.textures.forEachIndexed { index, texture ->
            texture.draw(0f, 0f, textureWidth().toFloat(), textureHeight().toFloat())
            lastFrameCommands++
            counters.onRootCommand()
            if (index < state.externalLayers.size) {
                if (state.externalLayers[index].draw(context)) {
                    lastFrameCommands++
                    counters.onExternalCommands(1)
                }
            }
        }
        return true
    }

    private var currentWidth = 1
    private var currentHeight = 1

    private fun textureWidth(): Int = currentWidth

    private fun textureHeight(): Int = currentHeight

    /** Executes commands produced outside an ordered stream, currently conservative NativeView fallback. */
    fun drawExternalContent(block: () -> Int) {
        check(!closed) { "Compositor is closed" }
        val count = block()
        lastFrameCommands += count
        counters.onExternalCommands(count)
    }

    fun uploadAndDrawRoot(
        surface: CairoSurface,
        width: Int,
        height: Int,
        damage: FrameDamage?,
    ) {
        check(!closed) { "Compositor is closed" }
        val effectiveDamage =
            if (rootTextureDirty) FrameDamage(0, 0, width, height) else damage
        val upload = rootTexture.upload(surface, width, height, effectiveDamage)
        if (upload.reallocated) counters.onRootTextureAllocated()
        lastRootUploadBytes = upload.bytes
        counters.onRootUploaded(upload.bytes)
        rootTexture.draw(0f, 0f, width.toFloat(), height.toFloat())
        rootTextureDirty = false
        lastFrameCommands++
        counters.onRootCommand()
    }

    fun present() {
        check(!closed) { "Compositor is closed" }
        kgpu_context_present(context)
        counters.onFramePresented()
    }

    fun statsSnapshot(): SdlCompositorStatsSnapshot = counters.snapshot()

    override fun createLayerRegistration(): CairoLayerRegistration {
        check(!closed) { "Compositor is closed" }
        val id = nextLayerId++
        counters.onLayerCreated()
        return object : CairoLayerRegistration {
            private var released = false

            override val id: Long = id

            override fun onContentChanged() {
                if (!released) counters.onLayerContentChanged()
            }

            override fun onPropertiesChanged() {
                if (!released) counters.onLayerPropertiesChanged()
            }

            override fun close() {
                if (released) return
                released = true
                counters.onLayerReleased()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        orderedState?.close()
        orderedState = null
        rootTexture.close()
        kgpu_context_destroy(context)
    }

    companion object {
        fun create(window: CPointer<SDL_Window>): SdlComposeCompositor? {
            val context = kgpu_context_create(window) ?: return null
            return SdlComposeCompositor(context)
        }
    }
}
