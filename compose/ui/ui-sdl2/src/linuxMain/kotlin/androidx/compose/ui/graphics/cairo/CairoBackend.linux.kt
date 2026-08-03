@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package androidx.compose.ui.graphics.cairo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.platform.PlatformBlurFilter
import androidx.compose.ui.graphics.platform.PlatformColorFilter
import androidx.compose.ui.graphics.platform.PlatformGraphics
import androidx.compose.ui.graphics.platform.PlatformGraphicsContext
import androidx.compose.ui.graphics.platform.PlatformGraphicsLayer
import androidx.compose.ui.graphics.platform.PlatformRenderEffect
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.platform.platformColorFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformParagraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.PlatformFont
import androidx.compose.ui.text.platform.PlatformText
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import cairo.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

internal class CairoSurface(
    val width: Int,
    val height: Int,
    val handle: COpaquePointer = checkNotNull(kc_surface_create(width, height)),
) : AutoCloseable {
    val stride: Int
        get() = kc_surface_stride(handle)

    val data: COpaquePointer
        get() = checkNotNull(kc_surface_data(handle))

    init {
        check(kc_surface_status(handle) == 0) {
            "Cairo could not allocate ${width}x$height surface"
        }
    }

    fun clear() {
        val cr = checkNotNull(kc_create(handle))
        kc_set_operator(cr, 1) // CAIRO_OPERATOR_SOURCE
        kc_set_source_rgba(cr, 0.0, 0.0, 0.0, 0.0)
        kc_paint(cr)
        kc_destroy(cr)
    }

    fun flush() = kc_surface_flush(handle)

    fun markDirty() = kc_surface_dirty(handle)

    override fun close() = kc_surface_destroy(handle)
}

private sealed interface CairoPathCommand {
    data class Move(val x: Float, val y: Float) : CairoPathCommand

    data class Line(val x: Float, val y: Float) : CairoPathCommand

    data class Cubic(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x3: Float,
        val y3: Float,
    ) : CairoPathCommand

    data object Close : CairoPathCommand
}

private class CairoPath : Path {
    val commands = mutableListOf<CairoPathCommand>()
    private var current = Offset.Zero
    private var contourStart = Offset.Zero
    override var fillType: PathFillType = PathFillType.NonZero
    override val isConvex: Boolean
        get() = false

    override val isEmpty: Boolean
        get() = commands.isEmpty()

    override fun moveTo(x: Float, y: Float) {
        commands += CairoPathCommand.Move(x, y)
        current = Offset(x, y)
        contourStart = current
    }

    override fun relativeMoveTo(dx: Float, dy: Float) = moveTo(current.x + dx, current.y + dy)

    override fun lineTo(x: Float, y: Float) {
        commands += CairoPathCommand.Line(x, y)
        current = Offset(x, y)
    }

    override fun relativeLineTo(dx: Float, dy: Float) = lineTo(current.x + dx, current.y + dy)

    @Suppress("DEPRECATION")
    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        val c1 =
            Offset(current.x + (x1 - current.x) * 2f / 3f, current.y + (y1 - current.y) * 2f / 3f)
        val c2 = Offset(x2 + (x1 - x2) * 2f / 3f, y2 + (y1 - y2) * 2f / 3f)
        cubicTo(c1.x, c1.y, c2.x, c2.y, x2, y2)
    }

    @Suppress("DEPRECATION")
    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) =
        quadraticBezierTo(current.x + dx1, current.y + dy1, current.x + dx2, current.y + dy2)

    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        commands += CairoPathCommand.Cubic(x1, y1, x2, y2, x3, y3)
        current = Offset(x3, y3)
    }

    override fun relativeCubicTo(
        dx1: Float,
        dy1: Float,
        dx2: Float,
        dy2: Float,
        dx3: Float,
        dy3: Float,
    ) =
        cubicTo(
            current.x + dx1,
            current.y + dy1,
            current.x + dx2,
            current.y + dy2,
            current.x + dx3,
            current.y + dy3,
        )

    override fun arcTo(
        rect: Rect,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
        forceMoveTo: Boolean,
    ) {
        addEllipseArc(rect, startAngleDegrees, sweepAngleDegrees, forceMoveTo)
    }

    override fun addRect(rect: Rect, direction: Path.Direction) {
        if (direction == Path.Direction.CounterClockwise) {
            moveTo(rect.left, rect.top)
            lineTo(rect.left, rect.bottom)
            lineTo(rect.right, rect.bottom)
            lineTo(rect.right, rect.top)
        } else {
            moveTo(rect.left, rect.top)
            lineTo(rect.right, rect.top)
            lineTo(rect.right, rect.bottom)
            lineTo(rect.left, rect.bottom)
        }
        close()
    }

    @Suppress("DEPRECATION")
    override fun addRect(rect: Rect) = addRect(rect, Path.Direction.CounterClockwise)

    override fun addOval(oval: Rect, direction: Path.Direction) {
        addEllipseArc(oval, 0f, if (direction == Path.Direction.Clockwise) 360f else -360f, true)
        close()
    }

    @Suppress("DEPRECATION")
    override fun addOval(oval: Rect) = addOval(oval, Path.Direction.CounterClockwise)

    override fun addRoundRect(roundRect: RoundRect, direction: Path.Direction) {
        val reverse = direction == Path.Direction.CounterClockwise
        if (reverse) {
            addRoundRectCounterClockwise(roundRect)
        } else {
            addRoundRectClockwise(roundRect)
        }
    }

    @Suppress("DEPRECATION")
    override fun addRoundRect(roundRect: RoundRect) =
        addRoundRect(roundRect, Path.Direction.CounterClockwise)

    private fun addRoundRectClockwise(r: RoundRect) {
        moveTo(r.left + r.topLeftCornerRadius.x, r.top)
        lineTo(r.right - r.topRightCornerRadius.x, r.top)
        corner(
            r.right - r.topRightCornerRadius.x,
            r.top + r.topRightCornerRadius.y,
            r.topRightCornerRadius.x,
            r.topRightCornerRadius.y,
            -90f,
            90f,
        )
        lineTo(r.right, r.bottom - r.bottomRightCornerRadius.y)
        corner(
            r.right - r.bottomRightCornerRadius.x,
            r.bottom - r.bottomRightCornerRadius.y,
            r.bottomRightCornerRadius.x,
            r.bottomRightCornerRadius.y,
            0f,
            90f,
        )
        lineTo(r.left + r.bottomLeftCornerRadius.x, r.bottom)
        corner(
            r.left + r.bottomLeftCornerRadius.x,
            r.bottom - r.bottomLeftCornerRadius.y,
            r.bottomLeftCornerRadius.x,
            r.bottomLeftCornerRadius.y,
            90f,
            90f,
        )
        lineTo(r.left, r.top + r.topLeftCornerRadius.y)
        corner(
            r.left + r.topLeftCornerRadius.x,
            r.top + r.topLeftCornerRadius.y,
            r.topLeftCornerRadius.x,
            r.topLeftCornerRadius.y,
            180f,
            90f,
        )
        close()
    }

    private fun addRoundRectCounterClockwise(r: RoundRect) {
        moveTo(r.left + r.topLeftCornerRadius.x, r.top)
        corner(
            r.left + r.topLeftCornerRadius.x,
            r.top + r.topLeftCornerRadius.y,
            r.topLeftCornerRadius.x,
            r.topLeftCornerRadius.y,
            -90f,
            -90f,
        )
        lineTo(r.left, r.bottom - r.bottomLeftCornerRadius.y)
        corner(
            r.left + r.bottomLeftCornerRadius.x,
            r.bottom - r.bottomLeftCornerRadius.y,
            r.bottomLeftCornerRadius.x,
            r.bottomLeftCornerRadius.y,
            180f,
            -90f,
        )
        lineTo(r.right - r.bottomRightCornerRadius.x, r.bottom)
        corner(
            r.right - r.bottomRightCornerRadius.x,
            r.bottom - r.bottomRightCornerRadius.y,
            r.bottomRightCornerRadius.x,
            r.bottomRightCornerRadius.y,
            90f,
            -90f,
        )
        lineTo(r.right, r.top + r.topRightCornerRadius.y)
        corner(
            r.right - r.topRightCornerRadius.x,
            r.top + r.topRightCornerRadius.y,
            r.topRightCornerRadius.x,
            r.topRightCornerRadius.y,
            0f,
            -90f,
        )
        close()
    }

    private fun corner(cx: Float, cy: Float, rx: Float, ry: Float, start: Float, sweep: Float) {
        if (rx <= 0f || ry <= 0f) return
        ellipseCubic(cx, cy, rx, ry, start, sweep)
    }

    override fun addArcRad(oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float) =
        addEllipseArc(
            oval,
            startAngleRadians * 180f / PI.toFloat(),
            sweepAngleRadians * 180f / PI.toFloat(),
            true,
        )

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) =
        addEllipseArc(oval, startAngleDegrees, sweepAngleDegrees, true)

    override fun addPath(path: Path, offset: Offset) {
        val source = path as CairoPath
        source.commands.forEach { command ->
            when (command) {
                is CairoPathCommand.Move -> moveTo(command.x + offset.x, command.y + offset.y)
                is CairoPathCommand.Line -> lineTo(command.x + offset.x, command.y + offset.y)
                is CairoPathCommand.Cubic ->
                    cubicTo(
                        command.x1 + offset.x,
                        command.y1 + offset.y,
                        command.x2 + offset.x,
                        command.y2 + offset.y,
                        command.x3 + offset.x,
                        command.y3 + offset.y,
                    )
                CairoPathCommand.Close -> close()
            }
        }
    }

    override fun close() {
        commands += CairoPathCommand.Close
        current = contourStart
    }

    override fun reset() {
        commands.clear()
        current = Offset.Zero
        contourStart = Offset.Zero
    }

    override fun translate(offset: Offset) =
        transform(Matrix().also { it.translate(offset.x, offset.y) })

    override fun transform(matrix: Matrix) {
        fun mapped(x: Float, y: Float): Offset = matrix.map(Offset(x, y))
        val copy = commands.toList()
        reset()
        copy.forEach { command ->
            when (command) {
                is CairoPathCommand.Move -> mapped(command.x, command.y).let { moveTo(it.x, it.y) }
                is CairoPathCommand.Line -> mapped(command.x, command.y).let { lineTo(it.x, it.y) }
                is CairoPathCommand.Cubic -> {
                    val a = mapped(command.x1, command.y1)
                    val b = mapped(command.x2, command.y2)
                    val c = mapped(command.x3, command.y3)
                    cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
                }
                CairoPathCommand.Close -> close()
            }
        }
    }

    override fun getBounds(): Rect {
        val values =
            commands.flatMap {
                when (it) {
                    is CairoPathCommand.Move -> listOf(it.x, it.y)
                    is CairoPathCommand.Line -> listOf(it.x, it.y)
                    is CairoPathCommand.Cubic -> listOf(it.x1, it.y1, it.x2, it.y2, it.x3, it.y3)
                    CairoPathCommand.Close -> emptyList()
                }
            }
        if (values.isEmpty()) return Rect.Zero
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in values.indices step 2) {
            left = min(left, values[i])
            right = max(right, values[i])
            top = min(top, values[i + 1])
            bottom = max(bottom, values[i + 1])
        }
        return Rect(left, top, right, bottom)
    }

    override fun op(path1: Path, path2: Path, operation: PathOperation): Boolean {
        fun CairoPath.toNative(): COpaquePointer {
            val native = checkNotNull(kg_path_create())
            commands.forEach { command ->
                when (command) {
                    is CairoPathCommand.Move ->
                        kg_path_move_to(native, command.x.toDouble(), command.y.toDouble())
                    is CairoPathCommand.Line ->
                        kg_path_line_to(native, command.x.toDouble(), command.y.toDouble())
                    is CairoPathCommand.Cubic ->
                        kg_path_curve_to(
                            native,
                            command.x1.toDouble(),
                            command.y1.toDouble(),
                            command.x2.toDouble(),
                            command.y2.toDouble(),
                            command.x3.toDouble(),
                            command.y3.toDouble(),
                        )
                    CairoPathCommand.Close -> kg_path_close(native)
                }
            }
            return native
        }
        val first = (path1 as CairoPath).toNative()
        val second = (path2 as CairoPath).toNative()
        val kind =
            when (operation) {
                PathOperation.Difference -> 0
                PathOperation.Intersect -> 1
                PathOperation.Union -> 2
                PathOperation.Xor -> 3
                PathOperation.ReverseDifference -> 4
                else -> -1
            }
        val result =
            if (kind >= 0)
                kg_path_op(
                    first,
                    second,
                    kind,
                    if (
                        (path1 as CairoPath).fillType == PathFillType.EvenOdd ||
                            (path2 as CairoPath).fillType == PathFillType.EvenOdd
                    )
                        1
                    else 0,
                )
            else null
        kg_path_destroy(first)
        kg_path_destroy(second)
        if (result == null) return false
        reset()
        for (index in 0 until kg_path_command_count(result)) {
            fun value(offset: Int) = kg_path_command_value(result, index, offset).toFloat()
            when (kg_path_command_type(result, index)) {
                0 -> moveTo(value(0), value(1))
                1 -> lineTo(value(0), value(1))
                2 -> cubicTo(value(0), value(1), value(2), value(3), value(4), value(5))
                3 -> close()
            }
        }
        kg_path_destroy(result)
        return true
    }

    private fun addEllipseArc(rect: Rect, start: Float, sweep: Float, move: Boolean) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val rx = rect.width / 2f
        val ry = rect.height / 2f
        val radians = start * PI / 180.0
        val first = Offset((cx + cos(radians) * rx).toFloat(), (cy + sin(radians) * ry).toFloat())
        if (move) moveTo(first.x, first.y) else lineTo(first.x, first.y)
        var remaining = sweep
        var angle = start
        while (abs(remaining) > 0.001f) {
            val part = remaining.coerceIn(-90f, 90f)
            ellipseCubic(cx, cy, rx, ry, angle, part)
            angle += part
            remaining -= part
        }
    }

    private fun ellipseCubic(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        start: Float,
        sweep: Float,
    ) {
        val a0 = start * PI / 180.0
        val a1 = (start + sweep) * PI / 180.0
        val k = 4.0 / 3.0 * kotlin.math.tan((a1 - a0) / 4.0)
        val x0 = cos(a0)
        val y0 = sin(a0)
        val x3 = cos(a1)
        val y3 = sin(a1)
        cubicTo(
            (cx + rx * (x0 - k * y0)).toFloat(),
            (cy + ry * (y0 + k * x0)).toFloat(),
            (cx + rx * (x3 + k * y3)).toFloat(),
            (cy + ry * (y3 - k * x3)).toFloat(),
            (cx + rx * x3).toFloat(),
            (cy + ry * y3).toFloat(),
        )
    }
}

internal class CairoImage(
    override val width: Int,
    override val height: Int,
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888,
    override val hasAlpha: Boolean = true,
    override val colorSpace: ColorSpace = ColorSpaces.Srgb,
    val surface: CairoSurface = CairoSurface(width, height),
) : ImageBitmap, AutoCloseable {
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) {
        val source = surface.data.reinterpret<IntVar>()
        val sourceStride = surface.stride / 4
        for (y in 0 until height) for (x in 0 until width) {
            buffer[bufferOffset + y * stride + x] = source[(startY + y) * sourceStride + startX + x]
        }
    }

    override fun prepareToDraw() = Unit

    override fun close() = surface.close()
}

private sealed interface CairoShader : PlatformShader

private class CairoPatternShader(val pattern: COpaquePointer) : CairoShader

private data class CairoCompositeShader(
    val destination: CairoShader,
    val source: CairoShader,
    val blendMode: BlendMode,
) : CairoShader

private sealed interface CairoEffect : PlatformRenderEffect {
    val input: CairoEffect?
}

private data class CairoBlurEffect(
    override val input: CairoEffect?,
    val radiusX: Float,
    val radiusY: Float,
    val edgeTreatment: TileMode,
) : CairoEffect

private data class CairoOffsetEffect(override val input: CairoEffect?, val offset: Offset) :
    CairoEffect

private sealed class CairoFilter : PlatformColorFilter()

private class CairoMatrixFilter(val matrix: FloatArray) : CairoFilter()

private data class CairoTintFilter(val color: Color, val blendMode: BlendMode) : CairoFilter()

private class CairoBlur(val radius: Float) : PlatformBlurFilter()

private sealed interface CairoPathEffect : PathEffect

private data class CairoDash(val intervals: FloatArray, val phase: Float) : CairoPathEffect

private data class CairoCorner(val radius: Float) : CairoPathEffect

private data class CairoChain(val outer: PathEffect, val inner: PathEffect) : CairoPathEffect

private data class CairoStamp(val shape: Path, val advance: Float, val phase: Float) :
    CairoPathEffect

private fun triangleMesh(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    c0: Color,
    c1: Color,
    c2: Color,
): COpaquePointer =
    checkNotNull(kc_pattern_mesh()).also { mesh ->
        kc_mesh_begin(mesh)
        kc_mesh_move_to(mesh, p0.x.toDouble(), p0.y.toDouble())
        kc_mesh_line_to(mesh, p1.x.toDouble(), p1.y.toDouble())
        kc_mesh_line_to(mesh, p2.x.toDouble(), p2.y.toDouble())
        kc_mesh_line_to(mesh, p0.x.toDouble(), p0.y.toDouble())
        listOf(c0, c1, c2, c0).forEachIndexed { corner, color ->
            kc_mesh_color(
                mesh,
                corner.toUInt(),
                color.red.toDouble(),
                color.green.toDouble(),
                color.blue.toDouble(),
                color.alpha.toDouble(),
            )
        }
        kc_mesh_end(mesh)
    }

private fun affineMatrix(
    source0: Offset,
    source1: Offset,
    source2: Offset,
    destination0: Offset,
    destination1: Offset,
    destination2: Offset,
): Matrix {
    val denominator =
        source0.x * (source1.y - source2.y) +
            source1.x * (source2.y - source0.y) +
            source2.x * (source0.y - source1.y)
    if (abs(denominator) < 0.0001f) return Matrix()
    fun coefficient(v0: Float, v1: Float, v2: Float): FloatArray =
        floatArrayOf(
            (v0 * (source1.y - source2.y) +
                v1 * (source2.y - source0.y) +
                v2 * (source0.y - source1.y)) / denominator,
            (v0 * (source2.x - source1.x) +
                v1 * (source0.x - source2.x) +
                v2 * (source1.x - source0.x)) / denominator,
            (v0 * (source1.x * source2.y - source2.x * source1.y) +
                v1 * (source2.x * source0.y - source0.x * source2.y) +
                v2 * (source0.x * source1.y - source1.x * source0.y)) / denominator,
        )
    val x = coefficient(destination0.x, destination1.x, destination2.x)
    val y = coefficient(destination0.y, destination1.y, destination2.y)
    return Matrix().also {
        it[0, 0] = x[0]
        it[1, 0] = x[1]
        it[3, 0] = x[2]
        it[0, 1] = y[0]
        it[1, 1] = y[1]
        it[3, 1] = y[2]
    }
}

private fun transformCairoShader(shader: CairoShader, matrix: Matrix): CairoShader =
    when (shader) {
        is CairoPatternShader ->
            CairoPatternShader(
                checkNotNull(kc_pattern_reference(shader.pattern)).also { pattern ->
                    kc_pattern_matrix(
                        pattern,
                        matrix[0, 0].toDouble(),
                        matrix[1, 0].toDouble(),
                        matrix[0, 1].toDouble(),
                        matrix[1, 1].toDouble(),
                        matrix[3, 0].toDouble(),
                        matrix[3, 1].toDouble(),
                    )
                }
            )
        is CairoCompositeShader ->
            CairoCompositeShader(
                transformCairoShader(shader.destination, matrix),
                transformCairoShader(shader.source, matrix),
                shader.blendMode,
            )
    }

private class CairoPaint : Paint {
    var blur: CairoBlur? = null
    override var alpha: Float = 1f
    override var isAntiAlias: Boolean = true
    override var color: Color = Color.Black
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var style: PaintingStyle = PaintingStyle.Fill
    override var strokeWidth: Float = 0f
    override var strokeCap: StrokeCap = StrokeCap.Butt
    override var strokeJoin: StrokeJoin = StrokeJoin.Miter
    override var strokeMiterLimit: Float = 4f
    override var filterQuality: FilterQuality = FilterQuality.Low
    override var shader: Shader? = null
    override var colorFilter: ColorFilter? = null
    override var pathEffect: PathEffect? = null
}

internal class CairoCanvas(val context: COpaquePointer) : Canvas {
    private data class SavedLayer(val paint: CairoPaint?)

    private val saves = mutableListOf<SavedLayer>()

    override fun save() {
        kc_save(context)
        saves += SavedLayer(null)
    }

    override fun restore() {
        val layer = saves.removeLastOrNull() ?: return
        if (layer.paint != null) {
            finishGroup(layer.paint)
        }
        kc_restore(context)
    }

    override fun saveLayer(bounds: Rect, paint: Paint) {
        kc_save(context)
        kc_rectangle(
            context,
            bounds.left.toDouble(),
            bounds.top.toDouble(),
            bounds.width.toDouble(),
            bounds.height.toDouble(),
        )
        kc_clip(context)
        kc_push_group(context)
        saves += SavedLayer(paint as CairoPaint)
    }

    override fun translate(dx: Float, dy: Float) =
        kc_translate(context, dx.toDouble(), dy.toDouble())

    override fun scale(sx: Float, sy: Float) {
        if (sx == 0f || sy == 0f) {
            // Cairo rejects non-invertible transforms and permanently puts the context into an
            // error state. A zero scale has no visible output, so represent it with an empty clip.
            kc_new_path(context)
            kc_clip(context)
        } else {
            kc_scale(context, sx.toDouble(), sy.toDouble())
        }
    }

    override fun rotate(degrees: Float) = kc_rotate(context, degrees * PI / 180.0)

    override fun skew(sx: Float, sy: Float) =
        concat(
            Matrix().also {
                it[0, 1] = kotlin.math.tan(sx * PI.toFloat() / 180f)
                it[1, 0] = kotlin.math.tan(sy * PI.toFloat() / 180f)
            }
        )

    override fun concat(matrix: Matrix) =
        kc_transform(
            context,
            matrix[0, 0].toDouble(),
            matrix[1, 0].toDouble(),
            matrix[0, 1].toDouble(),
            matrix[1, 1].toDouble(),
            matrix[3, 0].toDouble(),
            matrix[3, 1].toDouble(),
        )

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        when (clipOp) {
            ClipOp.Intersect -> {
                kc_rectangle(
                    context,
                    left.toDouble(),
                    top.toDouble(),
                    (right - left).toDouble(),
                    (bottom - top).toDouble(),
                )
                kc_clip(context)
            }
            ClipOp.Difference ->
                kc_clip_difference_rect(
                    context,
                    left.toDouble(),
                    top.toDouble(),
                    (right - left).toDouble(),
                    (bottom - top).toDouble(),
                )
        }
    }

    override fun clipPath(path: Path, clipOp: ClipOp) {
        when (clipOp) {
            ClipOp.Intersect -> {
                append(path as CairoPath)
                kc_clip(context)
            }
            ClipOp.Difference -> {
                val wasEvenOdd = kc_clip_difference_begin(context)
                appendCommands(path as CairoPath)
                kc_clip_difference_end(context, wasEvenOdd)
            }
        }
    }

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        kc_new_path(context)
        kc_move_to(context, p1.x.toDouble(), p1.y.toDouble())
        kc_line_to(context, p2.x.toDouble(), p2.y.toDouble())
        stroke(paint as CairoPaint)
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        kc_new_path(context)
        kc_rectangle(
            context,
            left.toDouble(),
            top.toDouble(),
            (right - left).toDouble(),
            (bottom - top).toDouble(),
        )
        finish(paint as CairoPaint)
    }

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: Paint,
    ) {
        val path =
            CairoPath().apply {
                addRoundRect(RoundRect(left, top, right, bottom, radiusX, radiusY))
            }
        drawPath(path, paint)
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        save()
        translate((left + right) / 2f, (top + bottom) / 2f)
        scale((right - left) / 2f, (bottom - top) / 2f)
        kc_new_path(context)
        kc_arc(context, 0.0, 0.0, 1.0, 0.0, PI * 2)
        finish(paint as CairoPaint)
        restore()
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        kc_new_path(context)
        kc_arc(context, center.x.toDouble(), center.y.toDouble(), radius.toDouble(), 0.0, PI * 2)
        finish(paint as CairoPaint)
    }

    override fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint,
    ) {
        val path =
            CairoPath().apply {
                if (useCenter) moveTo((left + right) / 2f, (top + bottom) / 2f)
                arcTo(Rect(left, top, right, bottom), startAngle, sweepAngle, !useCenter)
                if (useCenter) close()
            }
        drawPath(path, paint)
    }

    override fun drawPath(path: Path, paint: Paint) {
        append(path as CairoPath)
        finish(paint as CairoPaint)
    }

    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) =
        drawImageRect(
            image,
            IntOffset.Zero,
            IntSize(image.width, image.height),
            IntOffset(topLeftOffset.x.toInt(), topLeftOffset.y.toInt()),
            IntSize(image.width, image.height),
            paint,
        )

    override fun drawImageRect(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        paint: Paint,
    ) {
        image as CairoImage
        val cairoPaint = paint as CairoPaint
        save()
        kc_rectangle(
            context,
            dstOffset.x.toDouble(),
            dstOffset.y.toDouble(),
            dstSize.width.toDouble(),
            dstSize.height.toDouble(),
        )
        kc_clip(context)
        translate(dstOffset.x.toFloat(), dstOffset.y.toFloat())
        scale(dstSize.width.toFloat() / srcSize.width, dstSize.height.toFloat() / srcSize.height)
        val grouped = cairoPaint.needsGroup()
        if (grouped) kc_push_group(context)
        kc_set_operator(
            context,
            if (grouped) operator(BlendMode.SrcOver) else operator(cairoPaint.blendMode),
        )
        kc_set_source_surface(
            context,
            image.surface.handle,
            -srcOffset.x.toDouble(),
            -srcOffset.y.toDouble(),
        )
        kc_paint_alpha(context, if (grouped) 1.0 else cairoPaint.alpha.toDouble())
        if (grouped) finishGroup(cairoPaint)
        restore()
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        val raw = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            raw[i * 2] = p.x
            raw[i * 2 + 1] = p.y
        }
        drawRawPoints(pointMode, raw, paint)
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        if (points.size < 2) return
        kc_new_path(context)
        if (pointMode == PointMode.Points) {
            for (i in points.indices step 2) {
                kc_arc(
                    context,
                    points[i].toDouble(),
                    points[i + 1].toDouble(),
                    max(0.5f, (paint as CairoPaint).strokeWidth / 2f).toDouble(),
                    0.0,
                    PI * 2,
                )
                fill(paint)
            }
        } else {
            kc_move_to(context, points[0].toDouble(), points[1].toDouble())
            for (i in 2 until points.size step 2) kc_line_to(
                context,
                points[i].toDouble(),
                points[i + 1].toDouble(),
            )
            stroke(paint as CairoPaint)
        }
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        val count = vertices.positions.size / 2
        if (count < 3) return
        val order =
            if (vertices.indices.isNotEmpty()) {
                IntArray(vertices.indices.size) { vertices.indices[it].toInt() and 0xffff }
            } else {
                IntArray(count) { it }
            }
        val triangles = mutableListOf<IntArray>()
        when (vertices.vertexMode) {
            VertexMode.Triangles ->
                for (i in 0..order.size - 3 step 3) triangles +=
                    intArrayOf(order[i], order[i + 1], order[i + 2])
            VertexMode.TriangleStrip ->
                for (i in 0 until order.size - 2) {
                    triangles +=
                        if (i % 2 == 0) intArrayOf(order[i], order[i + 1], order[i + 2])
                        else intArrayOf(order[i + 1], order[i], order[i + 2])
                }
            VertexMode.TriangleFan ->
                for (i in 1 until order.size - 1) triangles +=
                    intArrayOf(order[0], order[i], order[i + 1])
        }
        val original = paint as CairoPaint
        triangles.forEach { triangle ->
            fun position(index: Int) =
                Offset(vertices.positions[index * 2], vertices.positions[index * 2 + 1])
            fun texture(index: Int) =
                Offset(
                    vertices.textureCoordinates[index * 2],
                    vertices.textureCoordinates[index * 2 + 1],
                )
            val p0 = position(triangle[0])
            val p1 = position(triangle[1])
            val p2 = position(triangle[2])
            val mesh =
                triangleMesh(
                    p0,
                    p1,
                    p2,
                    Color(vertices.colors[triangle[0]]),
                    Color(vertices.colors[triangle[1]]),
                    Color(vertices.colors[triangle[2]]),
                )
            val shader = original.shader?.platformShader as? CairoShader
            val vertexShader = CairoPatternShader(mesh)
            val combined =
                if (shader == null) {
                    vertexShader
                } else {
                    val transformed =
                        transformCairoShader(
                            shader,
                            affineMatrix(
                                texture(triangle[0]),
                                texture(triangle[1]),
                                texture(triangle[2]),
                                p0,
                                p1,
                                p2,
                            ),
                        )
                    CairoCompositeShader(transformed, vertexShader, blendMode)
                }
            val vertexPaint =
                CairoPaint().also {
                    it.alpha = original.alpha
                    it.blendMode = original.blendMode
                    it.colorFilter = original.colorFilter
                    it.shader = Shader(combined)
                }
            drawPath(
                CairoPath().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                },
                vertexPaint,
            )
            kc_pattern_destroy(mesh)
        }
    }

    override fun enableZ() = Unit

    override fun disableZ() = Unit

    private fun append(path: CairoPath) {
        kc_new_path(context)
        kc_set_fill_rule(context, if (path.fillType == PathFillType.EvenOdd) 1 else 0)
        appendCommands(path)
    }

    private fun appendCommands(path: CairoPath) {
        path.commands.forEach {
            when (it) {
                is CairoPathCommand.Move -> kc_move_to(context, it.x.toDouble(), it.y.toDouble())
                is CairoPathCommand.Line -> kc_line_to(context, it.x.toDouble(), it.y.toDouble())
                is CairoPathCommand.Cubic ->
                    kc_curve_to(
                        context,
                        it.x1.toDouble(),
                        it.y1.toDouble(),
                        it.x2.toDouble(),
                        it.y2.toDouble(),
                        it.x3.toDouble(),
                        it.y3.toDouble(),
                    )
                CairoPathCommand.Close -> kc_close_path(context)
            }
        }
    }

    private fun CairoPaint.needsGroup(): Boolean =
        blur != null || colorFilter != null || alpha < 0.9999f

    private fun setSource(shader: CairoShader?, paint: CairoPaint) {
        when (shader) {
            is CairoPatternShader -> kc_set_source(context, shader.pattern)
            null ->
                kc_set_source_rgba(
                    context,
                    paint.color.red.toDouble(),
                    paint.color.green.toDouble(),
                    paint.color.blue.toDouble(),
                    paint.color.alpha.toDouble(),
                )
            is CairoCompositeShader ->
                error("Composite shaders must be rendered through renderPath")
        }
    }

    private fun renderPath(
        paint: CairoPaint,
        stroke: Boolean,
        preserve: Boolean,
        blendMode: BlendMode,
    ) {
        fun draw(preservePath: Boolean) {
            if (stroke) {
                if (preservePath) kc_stroke_preserve(context) else kc_stroke(context)
            } else {
                if (preservePath) kc_fill_preserve(context) else kc_fill(context)
            }
        }
        fun render(shader: CairoShader?, mode: BlendMode, preservePath: Boolean) {
            if (shader is CairoCompositeShader) {
                kc_push_group(context)
                render(shader.destination, BlendMode.SrcOver, true)
                render(shader.source, shader.blendMode, true)
                kc_pop_group_source(context)
                kc_set_operator(context, operator(mode))
                draw(preservePath)
            } else {
                setSource(shader, paint)
                kc_set_operator(context, operator(mode))
                draw(preservePath)
            }
        }
        render(paint.shader?.platformShader as? CairoShader, blendMode, preserve)
    }

    private fun finishGroup(paint: CairoPaint) {
        val filter = paint.colorFilter?.platformColorFilter as? CairoFilter
        if (paint.blur != null) {
            kc_pop_group_blur_source(
                context,
                paint.blur!!.radius.toDouble(),
                paint.blur!!.radius.toDouble(),
                3,
            )
            if (filter != null) {
                kc_push_group(context)
                kc_paint(context)
            }
        }
        when (filter) {
            is CairoMatrixFilter ->
                filter.matrix.usePinned {
                    kc_pop_group_color_matrix_source(context, it.addressOf(0))
                }
            is CairoTintFilter ->
                kc_pop_group_tint_source(
                    context,
                    filter.color.red.toDouble(),
                    filter.color.green.toDouble(),
                    filter.color.blue.toDouble(),
                    filter.color.alpha.toDouble(),
                    operator(filter.blendMode),
                )
            null -> if (paint.blur == null) kc_pop_group_source(context)
        }
        kc_set_operator(context, operator(paint.blendMode))
        kc_paint_alpha(context, paint.alpha.toDouble())
    }

    private fun source(paint: CairoPaint) {
        fun compositeSource(shader: CairoShader?) {
            if (shader is CairoCompositeShader) {
                kc_push_group(context)
                compositeSource(shader.destination)
                kc_set_operator(context, operator(BlendMode.SrcOver))
                kc_paint(context)
                compositeSource(shader.source)
                kc_set_operator(context, operator(shader.blendMode))
                kc_paint(context)
                kc_pop_group_source(context)
            } else {
                setSource(shader, paint)
            }
        }
        compositeSource(paint.shader?.platformShader as? CairoShader)
        kc_set_operator(context, operator(paint.blendMode))
    }

    private fun finish(paint: CairoPaint) {
        if (paint.style == PaintingStyle.Fill) fill(paint) else stroke(paint)
    }

    private fun fill(paint: CairoPaint) {
        val grouped = paint.needsGroup()
        if (grouped) kc_push_group(context)
        renderPath(
            paint,
            stroke = false,
            preserve = false,
            blendMode = if (grouped) BlendMode.SrcOver else paint.blendMode,
        )
        if (grouped) finishGroup(paint)
    }

    private fun stroke(paint: CairoPaint) {
        val grouped = paint.needsGroup()
        if (grouped) kc_push_group(context)
        kc_set_line_width(context, max(1f, paint.strokeWidth).toDouble())
        kc_set_line_cap(
            context,
            when (paint.strokeCap) {
                StrokeCap.Round -> 1
                StrokeCap.Square -> 2
                else -> 0
            },
        )
        kc_set_line_join(
            context,
            when (paint.strokeJoin) {
                StrokeJoin.Round -> 1
                StrokeJoin.Bevel -> 2
                else -> 0
            },
        )
        kc_set_miter_limit(context, paint.strokeMiterLimit.toDouble())
        val dash = paint.pathEffect as? CairoDash
        if (dash != null) {
            val values = dash.intervals.map(Float::toDouble).toDoubleArray()
            values.usePinned {
                kc_set_dash(context, it.addressOf(0), values.size, dash.phase.toDouble())
            }
        }
        renderPath(
            paint,
            stroke = true,
            preserve = false,
            blendMode = if (grouped) BlendMode.SrcOver else paint.blendMode,
        )
        if (grouped) finishGroup(paint)
    }

    internal fun drawSurface(
        surface: CairoSurface,
        x: Float,
        y: Float,
        alpha: Float,
        blendMode: BlendMode = BlendMode.SrcOver,
        colorFilter: ColorFilter? = null,
    ) {
        if (colorFilter == null) {
            kc_set_operator(context, operator(blendMode))
            kc_set_source_surface(context, surface.handle, x.toDouble(), y.toDouble())
            kc_paint_alpha(context, alpha.toDouble())
        } else {
            val paint =
                CairoPaint().also {
                    it.alpha = alpha
                    it.blendMode = blendMode
                    it.colorFilter = colorFilter
                }
            kc_push_group(context)
            kc_set_operator(context, operator(BlendMode.SrcOver))
            kc_set_source_surface(context, surface.handle, x.toDouble(), y.toDouble())
            kc_paint(context)
            finishGroup(paint)
        }
    }

    internal fun applyPaint(paint: Paint) = source(paint as CairoPaint)

    internal fun drawSurfaceTriangle(
        surface: CairoSurface,
        source0: Offset,
        source1: Offset,
        source2: Offset,
        destination0: Offset,
        destination1: Offset,
        destination2: Offset,
        alpha: Float,
        blendMode: BlendMode,
    ) {
        val denominator =
            source0.x * (source1.y - source2.y) +
                source1.x * (source2.y - source0.y) +
                source2.x * (source0.y - source1.y)
        if (abs(denominator) < 0.0001f) return
        fun coefficient(v0: Float, v1: Float, v2: Float): FloatArray =
            floatArrayOf(
                (v0 * (source1.y - source2.y) +
                    v1 * (source2.y - source0.y) +
                    v2 * (source0.y - source1.y)) / denominator,
                (v0 * (source2.x - source1.x) +
                    v1 * (source0.x - source2.x) +
                    v2 * (source1.x - source0.x)) / denominator,
                (v0 * (source1.x * source2.y - source2.x * source1.y) +
                    v1 * (source2.x * source0.y - source0.x * source2.y) +
                    v2 * (source0.x * source1.y - source1.x * source0.y)) / denominator,
            )
        val x = coefficient(destination0.x, destination1.x, destination2.x)
        val y = coefficient(destination0.y, destination1.y, destination2.y)
        save()
        // Adjacent antialiased triangle clips blend with transparency independently, exposing the
        // tessellation as dark seams. The projected surface has its own antialiased outer clip, so
        // internal triangle edges need exact, non-overlapping coverage.
        kc_set_antialias_enabled(context, 0)
        clipPath(
            CairoPath().apply {
                moveTo(destination0.x, destination0.y)
                lineTo(destination1.x, destination1.y)
                lineTo(destination2.x, destination2.y)
                close()
            }
        )
        kc_transform(
            context,
            x[0].toDouble(),
            y[0].toDouble(),
            x[1].toDouble(),
            y[1].toDouble(),
            x[2].toDouble(),
            y[2].toDouble(),
        )
        drawSurface(surface, 0f, 0f, alpha, blendMode)
        restore()
    }

    internal fun clipOutline(outline: Outline?) {
        when (outline) {
            is Outline.Rectangle -> clipRect(outline.rect)
            is Outline.Rounded -> {
                val p = CairoPath().apply { addRoundRect(outline.roundRect) }
                clipPath(p)
            }
            is Outline.Generic -> clipPath(outline.path)
            null -> Unit
        }
    }

    internal fun clearInteropPathInRoot(path: Path) {
        save()
        kc_identity_matrix(context)
        drawPath(
            path,
            CairoPaint().also {
                it.color = Color.Transparent
                it.blendMode = BlendMode.Clear
                it.isAntiAlias = true
            },
        )
        restore()
    }

    private fun operator(mode: BlendMode): Int =
        when (mode) {
            BlendMode.Clear -> 0
            BlendMode.Src -> 1
            BlendMode.SrcOver -> 2
            BlendMode.SrcIn -> 3
            BlendMode.SrcOut -> 4
            BlendMode.SrcAtop -> 5
            BlendMode.Dst -> 6
            BlendMode.DstOver -> 7
            BlendMode.DstIn -> 8
            BlendMode.DstOut -> 9
            BlendMode.DstAtop -> 10
            BlendMode.Xor -> 11
            BlendMode.Plus -> 12
            BlendMode.Modulate,
            BlendMode.Multiply -> 14
            BlendMode.Screen -> 15
            BlendMode.Overlay -> 16
            BlendMode.Darken -> 17
            BlendMode.Lighten -> 18
            BlendMode.ColorDodge -> 19
            BlendMode.ColorBurn -> 20
            BlendMode.Hardlight -> 21
            BlendMode.Softlight -> 22
            BlendMode.Difference -> 23
            BlendMode.Exclusion -> 24
            BlendMode.Hue -> 25
            BlendMode.Saturation -> 26
            BlendMode.Color -> 27
            BlendMode.Luminosity -> 28
            else -> 2
        }
}

private class CairoPathIterator(
    override val path: Path,
    override val conicEvaluation: PathIterator.ConicEvaluation,
    override val tolerance: Float,
) : PathIterator {
    private var index = 0

    override fun calculateSize(includeConvertedConics: Boolean) = (path as CairoPath).commands.size

    override fun hasNext() = index < (path as CairoPath).commands.size

    override fun next(outPoints: FloatArray, offset: Int): PathSegment.Type {
        if (!hasNext()) return PathSegment.Type.Done
        val c = (path as CairoPath).commands[index++]
        fun put(vararg p: Float) {
            p.forEachIndexed { i, v -> outPoints[offset + i] = v }
        }
        return when (c) {
            is CairoPathCommand.Move -> {
                put(c.x, c.y)
                PathSegment.Type.Move
            }
            is CairoPathCommand.Line -> {
                put(c.x, c.y, c.x, c.y)
                PathSegment.Type.Line
            }
            is CairoPathCommand.Cubic -> {
                put(c.x1, c.y1, c.x2, c.y2, c.x3, c.y3, c.x3, c.y3)
                PathSegment.Type.Cubic
            }
            CairoPathCommand.Close -> PathSegment.Type.Close
        }
    }

    override fun next() = androidx.compose.ui.graphics.DoneSegment
}

private class CairoPathMeasure : PathMeasure {
    private data class Sample(val position: Offset, val distance: Float)

    private var samples: List<Sample> = emptyList()

    override val length: Float
        get() = samples.lastOrNull()?.distance ?: 0f

    override fun getSegment(
        startDistance: Float,
        stopDistance: Float,
        destination: Path,
        startWithMoveTo: Boolean,
    ): Boolean {
        if (!startDistance.isFinite() || !stopDistance.isFinite() || samples.size < 2) return false
        val start = startDistance.coerceIn(0f, length)
        val stop = stopDistance.coerceIn(0f, length)
        if (start >= stop) return false

        var wroteSegment = false
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val segmentStart = max(start, previous.distance)
            val segmentEnd = min(stop, current.distance)
            if (segmentStart >= segmentEnd) continue

            val startPoint = interpolate(previous, current, segmentStart)
            if (!wroteSegment) {
                if (startWithMoveTo) {
                    destination.moveTo(startPoint.x, startPoint.y)
                } else {
                    destination.lineTo(startPoint.x, startPoint.y)
                }
            }
            val endPoint = interpolate(previous, current, segmentEnd)
            destination.lineTo(endPoint.x, endPoint.y)
            wroteSegment = true
        }
        return wroteSegment
    }

    override fun setPath(path: Path?, forceClosed: Boolean) {
        val source = path as? CairoPath
        samples = if (source == null) emptyList() else flattenFirstContour(source, forceClosed)
    }

    override fun getPosition(distance: Float): Offset {
        if (samples.isEmpty()) return Offset.Unspecified
        if (samples.size == 1) return samples[0].position
        val pinned = distance.coerceIn(0f, length)
        for (index in 1 until samples.size) {
            val current = samples[index]
            if (pinned <= current.distance) {
                return interpolate(samples[index - 1], current, pinned)
            }
        }
        return samples.last().position
    }

    override fun getTangent(distance: Float): Offset {
        if (samples.size < 2) return Offset.Unspecified
        val pinned = distance.coerceIn(0f, length)
        val index =
            (1 until samples.size).firstOrNull { pinned <= samples[it].distance }
                ?: samples.lastIndex
        val delta = samples[index].position - samples[index - 1].position
        val magnitude = delta.getDistance()
        return if (magnitude > 0f) Offset(delta.x / magnitude, delta.y / magnitude)
        else Offset.Unspecified
    }

    private fun flattenFirstContour(path: CairoPath, forceClosed: Boolean): List<Sample> {
        val result = mutableListOf<Sample>()
        var current = Offset.Zero
        var contourStart = Offset.Zero
        var hasContour = false
        var hasSegment = false
        var closed = false

        fun begin(position: Offset) {
            current = position
            contourStart = position
            hasContour = true
            result.clear()
            result += Sample(position, 0f)
        }

        fun append(position: Offset) {
            if (!hasContour) begin(current)
            val segmentLength = (position - current).getDistance()
            current = position
            if (segmentLength <= 0f) return
            result += Sample(position, result.last().distance + segmentLength)
            hasSegment = true
        }

        commandLoop@ for (command in path.commands) {
            when (command) {
                is CairoPathCommand.Move -> {
                    if (hasSegment) break@commandLoop
                    begin(Offset(command.x, command.y))
                }
                is CairoPathCommand.Line -> append(Offset(command.x, command.y))
                is CairoPathCommand.Cubic -> {
                    if (!hasContour) begin(current)
                    val start = current
                    val control1 = Offset(command.x1, command.y1)
                    val control2 = Offset(command.x2, command.y2)
                    val end = Offset(command.x3, command.y3)
                    val estimatedLength =
                        (control1 - start).getDistance() +
                            (control2 - control1).getDistance() +
                            (end - control2).getDistance()
                    val steps = (estimatedLength / 2f).toInt().coerceIn(4, 96)
                    for (step in 1..steps) {
                        append(cubicPoint(start, control1, control2, end, step.toFloat() / steps))
                    }
                }
                CairoPathCommand.Close -> {
                    if (hasContour) append(contourStart)
                    closed = true
                    break@commandLoop
                }
            }
        }
        if (forceClosed && hasContour && !closed) append(contourStart)
        return result
    }

    private fun interpolate(start: Sample, end: Sample, distance: Float): Offset {
        val span = end.distance - start.distance
        if (span <= 0f) return end.position
        val fraction = ((distance - start.distance) / span).coerceIn(0f, 1f)
        return start.position + (end.position - start.position) * fraction
    }

    private fun cubicPoint(
        start: Offset,
        control1: Offset,
        control2: Offset,
        end: Offset,
        fraction: Float,
    ): Offset {
        val inverse = 1f - fraction
        val inverseSquared = inverse * inverse
        val fractionSquared = fraction * fraction
        return start * (inverseSquared * inverse) +
            control1 * (3f * inverseSquared * fraction) +
            control2 * (3f * inverse * fractionSquared) +
            end * (fractionSquared * fraction)
    }
}

private class CairoGraphicsLayer : PlatformGraphicsLayer {
    override var compositingStrategy = CompositingStrategy.Auto
    override var pivotOffset = Offset.Unspecified
    override var alpha = 1f
    override var scaleX = 1f
    override var scaleY = 1f
    override var translationX = 0f
    override var translationY = 0f
    override var shadowElevation = 0f
    override var ambientShadowColor = Color.Black
    override var spotShadowColor = Color.Black
    override var blendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var rotationX = 0f
    override var rotationY = 0f
    override var rotationZ = 0f
    override var cameraDistance = 8f
    override var renderEffect: RenderEffect? = null
    private var topLeft = IntOffset.Zero
    private var size = IntSize.Zero
    private var outline: Outline? = null
    private var clip = false
    private val scope = CanvasDrawScope()
    private var ol = 0
    private var ot = 0
    private var or = 0
    private var ob = 0
    private var recordedDensity: Density? = null
    private var recordedLayoutDirection: LayoutDirection? = null
    private var recordedLayer: GraphicsLayer? = null
    private var recordedBlock: (DrawScope.() -> Unit)? = null

    override fun setBounds(topLeft: IntOffset, size: IntSize) {
        this.topLeft = topLeft
        this.size = size
    }

    override fun setOutline(outline: Outline?, clip: Boolean) {
        this.outline = outline
        this.clip = clip
    }

    override fun record(
        density: Density,
        layoutDirection: LayoutDirection,
        layer: GraphicsLayer,
        block: DrawScope.() -> Unit,
    ) {
        recordedDensity = density
        recordedLayoutDirection = layoutDirection
        recordedLayer = layer
        recordedBlock = block
    }

    private fun applyEffect(surface: CairoSurface, effect: CairoEffect?) {
        if (effect == null) return
        applyEffect(surface, effect.input)
        when (effect) {
            is CairoBlurEffect ->
                kc_surface_blur(
                    surface.handle,
                    effect.radiusX.coerceAtLeast(0f).toDouble(),
                    effect.radiusY.coerceAtLeast(0f).toDouble(),
                    when (effect.edgeTreatment) {
                        TileMode.Repeated -> 1
                        TileMode.Mirror -> 2
                        TileMode.Decal -> 3
                        else -> 0
                    },
                )
            is CairoOffsetEffect ->
                kc_surface_offset(surface.handle, effect.offset.x.toInt(), effect.offset.y.toInt())
        }
    }

    private fun project(point: Offset, pivot: Offset): Offset {
        var x = (point.x - pivot.x) * scaleX
        var y = (point.y - pivot.y) * scaleY
        var z = 0f
        val rx = rotationX * PI.toFloat() / 180f
        val ry = rotationY * PI.toFloat() / 180f
        val rz = rotationZ * PI.toFloat() / 180f
        val yx = y * cos(rx) - z * sin(rx)
        val zx = y * sin(rx) + z * cos(rx)
        y = yx
        z = zx
        val xy = x * cos(ry) + z * sin(ry)
        val zy = -x * sin(ry) + z * cos(ry)
        x = xy
        z = zy
        val xz = x * cos(rz) - y * sin(rz)
        val yz = x * sin(rz) + y * cos(rz)
        val depth = max(1f, cameraDistance * 72f)
        val perspective = depth / max(0.01f, depth - z)
        return Offset(pivot.x + xz * perspective, pivot.y + yz * perspective)
    }

    private fun drawProjected(target: CairoCanvas, surface: CairoSurface, pivot: Offset) {
        val columns = 12
        val rows = 12
        fun source(column: Int, row: Int) =
            Offset(
                surface.width * column.toFloat() / columns,
                surface.height * row.toFloat() / rows,
            )
        fun destination(source: Offset) = project(Offset(source.x - ol, source.y - ot), pivot)
        val topLeft = destination(source(0, 0))
        val topRight = destination(source(columns, 0))
        val bottomRight = destination(source(columns, rows))
        val bottomLeft = destination(source(0, rows))
        target.clipPath(
            CairoPath().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }
        )
        for (row in 0 until rows) for (column in 0 until columns) {
            val s00 = source(column, row)
            val s10 = source(column + 1, row)
            val s01 = source(column, row + 1)
            val s11 = source(column + 1, row + 1)
            val d00 = destination(s00)
            val d10 = destination(s10)
            val d01 = destination(s01)
            val d11 = destination(s11)
            target.drawSurfaceTriangle(surface, s00, s10, s11, d00, d10, d11, alpha, blendMode)
            target.drawSurfaceTriangle(surface, s00, s11, s01, d00, d11, d01, alpha, blendMode)
        }
    }

    override fun draw(canvas: Canvas) {
        val target = canvas as? CairoCanvas ?: return
        val density = recordedDensity ?: return
        val layoutDirection = recordedLayoutDirection ?: return
        val layer = recordedLayer ?: return
        val block = recordedBlock ?: return
        val px = if (pivotOffset != Offset.Unspecified) pivotOffset.x else size.width / 2f
        val py = if (pivotOffset != Offset.Unspecified) pivotOffset.y else size.height / 2f
        val effect = renderEffect?.platformRenderEffect as? CairoEffect
        val requiresRaster = effect != null || abs(rotationX) > 0.001f || abs(rotationY) > 0.001f
        if (requiresRaster) {
            val surface = CairoSurface(max(1, size.width + ol + or), max(1, size.height + ot + ob))
            surface.clear()
            val context = checkNotNull(kc_create(surface.handle))
            val offscreen = CairoCanvas(context)
            offscreen.translate(ol.toFloat(), ot.toFloat())
            if (clip) offscreen.clipOutline(outline)
            scope.draw(density, layoutDirection, offscreen, layer.size.toSize(), layer, block)
            kc_destroy(context)
            applyEffect(surface, effect)
            val renderSurface =
                if (colorFilter == null) {
                    surface
                } else {
                    CairoSurface(surface.width, surface.height).also { filtered ->
                        filtered.clear()
                        val filteredContext = checkNotNull(kc_create(filtered.handle))
                        CairoCanvas(filteredContext)
                            .drawSurface(surface, 0f, 0f, 1f, BlendMode.Src, colorFilter)
                        kc_destroy(filteredContext)
                    }
                }
            target.save()
            target.translate(topLeft.x + translationX, topLeft.y + translationY)
            if (abs(rotationX) > 0.001f || abs(rotationY) > 0.001f) {
                drawProjected(target, renderSurface, Offset(px, py))
            } else {
                target.translate(px, py)
                target.rotate(rotationZ)
                target.scale(scaleX, scaleY)
                target.translate(-px, -py)
                target.drawSurface(renderSurface, -ol.toFloat(), -ot.toFloat(), alpha, blendMode)
            }
            target.restore()
            if (renderSurface !== surface) renderSurface.close()
            surface.close()
            return
        }
        target.save()
        target.translate(topLeft.x + translationX, topLeft.y + translationY)
        target.translate(px, py)
        target.rotate(rotationZ)
        target.scale(scaleX, scaleY)
        target.translate(-px, -py)
        if (clip) target.clipOutline(outline)
        val needsGroup =
            alpha < 1f ||
                colorFilter != null ||
                compositingStrategy == CompositingStrategy.Offscreen ||
                blendMode != BlendMode.SrcOver
        if (needsGroup) {
            val paint =
                CairoPaint().also {
                    it.alpha = alpha
                    it.blendMode = blendMode
                    it.colorFilter = colorFilter
                }
            target.saveLayer(
                Rect(
                    -ol.toFloat(),
                    -ot.toFloat(),
                    (size.width + or).toFloat(),
                    (size.height + ob).toFloat(),
                ),
                paint,
            )
        }
        scope.draw(density, layoutDirection, target, layer.size.toSize(), layer, block)
        if (needsGroup) target.restore()
        target.restore()
    }

    override fun discardDisplayList() {
        recordedDensity = null
        recordedLayoutDirection = null
        recordedLayer = null
        recordedBlock = null
    }

    override fun setOutsets(left: Int, top: Int, right: Int, bottom: Int) {
        ol = left
        ot = top
        or = right
        ob = bottom
    }
}

private class CairoGraphicsContext : PlatformGraphicsContext() {
    override fun createPlatformGraphicsLayer(): PlatformGraphicsLayer = CairoGraphicsLayer()
}

internal object CairoGraphics : PlatformGraphics {
    override fun createGraphicsContext(): PlatformGraphicsContext = CairoGraphicsContext()

    override fun createCanvas(image: ImageBitmap): Canvas {
        image as CairoImage
        return CairoCanvas(checkNotNull(kc_create(image.surface.handle)))
    }

    override fun createPaint(): Paint = CairoPaint()

    override fun isBlendModeSupported(blendMode: BlendMode) = true

    override fun isTileModeSupported(tileMode: TileMode) = true

    override fun createPath(): Path = CairoPath()

    override fun createPathIterator(
        path: Path,
        conicEvaluation: PathIterator.ConicEvaluation,
        tolerance: Float,
    ): PathIterator = CairoPathIterator(path, conicEvaluation, tolerance)

    override fun createPathMeasure(): PathMeasure = CairoPathMeasure()

    override fun createImageBitmap(
        width: Int,
        height: Int,
        config: ImageBitmapConfig,
        hasAlpha: Boolean,
        colorSpace: ColorSpace,
    ): ImageBitmap = CairoImage(width, height, config, hasAlpha, colorSpace)

    override fun decodeImageBitmap(bytes: ByteArray): ImageBitmap {
        require(bytes.isNotEmpty()) { "Encoded image is empty" }
        val handle =
            bytes.usePinned { kc_surface_decode(it.addressOf(0).reinterpret(), bytes.size) }
                ?: error("Unsupported or corrupt encoded image")
        val width = kc_surface_width(handle)
        val height = kc_surface_height(handle)
        if (width <= 0 || height <= 0 || kc_surface_status(handle) != 0) {
            kc_surface_destroy(handle)
            error("Could not decode encoded image")
        }
        return CairoImage(width, height, surface = CairoSurface(width, height, handle = handle))
    }

    private fun shader(
        pattern: COpaquePointer,
        colors: List<Color>,
        stops: List<Float>?,
        tile: TileMode,
    ): Shader {
        colors.forEachIndexed { i, c ->
            kc_pattern_color_stop(
                pattern,
                (stops?.getOrNull(i)
                        ?: if (colors.size == 1) 0f else i.toFloat() / (colors.size - 1))
                    .toDouble(),
                c.red.toDouble(),
                c.green.toDouble(),
                c.blue.toDouble(),
                c.alpha.toDouble(),
            )
        }
        kc_pattern_extend(
            pattern,
            when (tile) {
                TileMode.Repeated -> 1
                TileMode.Mirror -> 2
                TileMode.Decal -> 3
                else -> 3
            },
        )
        return Shader(CairoPatternShader(pattern))
    }

    override fun createLinearGradientShader(
        from: Offset,
        to: Offset,
        colors: List<Color>,
        colorStops: List<Float>?,
        tileMode: TileMode,
    ) =
        shader(
            checkNotNull(
                kc_pattern_linear(
                    from.x.toDouble(),
                    from.y.toDouble(),
                    to.x.toDouble(),
                    to.y.toDouble(),
                )
            ),
            colors,
            colorStops,
            tileMode,
        )

    override fun createRadialGradientShader(
        center: Offset,
        radius: Float,
        colors: List<Color>,
        colorStops: List<Float>?,
        tileMode: TileMode,
    ) =
        shader(
            checkNotNull(
                kc_pattern_radial(
                    center.x.toDouble(),
                    center.y.toDouble(),
                    0.0,
                    center.x.toDouble(),
                    center.y.toDouble(),
                    radius.toDouble(),
                )
            ),
            colors,
            colorStops,
            tileMode,
        )

    override fun createSweepGradientShader(
        center: Offset,
        colors: List<Color>,
        colorStops: List<Float>?,
    ): Shader {
        require(colors.size >= 2) { "A sweep gradient requires at least two colors" }
        val stops = colorStops ?: List(colors.size) { it.toFloat() / (colors.size - 1) }
        require(stops.size == colors.size) { "colorStops and colors must have equal sizes" }
        fun sample(value: Float): Color {
            val position = value.coerceIn(0f, 1f)
            val upper =
                stops.indexOfFirst { it >= position }.let { if (it < 0) stops.lastIndex else it }
            if (upper == 0) return colors.first()
            val lower = upper - 1
            val width = stops[upper] - stops[lower]
            val amount = if (width <= 0.00001f) 0f else (position - stops[lower]) / width
            val first = colors[lower]
            val second = colors[upper]
            return Color(
                first.red + (second.red - first.red) * amount,
                first.green + (second.green - first.green) * amount,
                first.blue + (second.blue - first.blue) * amount,
                first.alpha + (second.alpha - first.alpha) * amount,
            )
        }
        val mesh = checkNotNull(kc_pattern_mesh())
        val radius = 65536.0
        val segments = 256
        repeat(segments) { index ->
            val t0 = index.toFloat() / segments
            val t1 = (index + 1).toFloat() / segments
            val angle0 = t0 * PI * 2.0
            val angle1 = t1 * PI * 2.0
            val outer0 =
                Offset(
                    (center.x + cos(angle0) * radius).toFloat(),
                    (center.y + sin(angle0) * radius).toFloat(),
                )
            val outer1 =
                Offset(
                    (center.x + cos(angle1) * radius).toFloat(),
                    (center.y + sin(angle1) * radius).toFloat(),
                )
            val c0 = sample(t0)
            val c1 = sample(t1)
            kc_mesh_begin(mesh)
            kc_mesh_move_to(mesh, center.x.toDouble(), center.y.toDouble())
            kc_mesh_line_to(mesh, outer0.x.toDouble(), outer0.y.toDouble())
            kc_mesh_line_to(mesh, outer1.x.toDouble(), outer1.y.toDouble())
            kc_mesh_line_to(mesh, center.x.toDouble(), center.y.toDouble())
            listOf(c0, c0, c1, c1).forEachIndexed { corner, color ->
                kc_mesh_color(
                    mesh,
                    corner.toUInt(),
                    color.red.toDouble(),
                    color.green.toDouble(),
                    color.blue.toDouble(),
                    color.alpha.toDouble(),
                )
            }
            kc_mesh_end(mesh)
        }
        return Shader(CairoPatternShader(mesh))
    }

    override fun createImageShader(
        image: ImageBitmap,
        tileModeX: TileMode,
        tileModeY: TileMode,
    ): Shader {
        image as CairoImage
        val pattern = checkNotNull(kc_pattern_surface(image.surface.handle))
        kc_pattern_extend(
            pattern,
            if (tileModeX == TileMode.Repeated || tileModeY == TileMode.Repeated) 1
            else if (tileModeX == TileMode.Mirror || tileModeY == TileMode.Mirror) 2 else 3,
        )
        return Shader(CairoPatternShader(pattern))
    }

    override fun createCompositeShader(destination: Shader, source: Shader, blendMode: BlendMode) =
        Shader(
            CairoCompositeShader(
                destination.platformShader as CairoShader,
                source.platformShader as CairoShader,
                blendMode,
            )
        )

    override fun transformShader(shader: Shader, matrix: Matrix): Shader =
        Shader(transformCairoShader(shader.platformShader as CairoShader, matrix))

    override fun createBlurRenderEffect(
        renderEffect: RenderEffect?,
        radiusX: Float,
        radiusY: Float,
        edgeTreatment: TileMode,
    ): PlatformRenderEffect =
        CairoBlurEffect(
            renderEffect?.platformRenderEffect as? CairoEffect,
            radiusX,
            radiusY,
            edgeTreatment,
        )

    override fun createOffsetRenderEffect(
        renderEffect: RenderEffect?,
        offset: Offset,
    ): PlatformRenderEffect =
        CairoOffsetEffect(renderEffect?.platformRenderEffect as? CairoEffect, offset)

    override fun createBlurFilter(radius: Float): PlatformBlurFilter = CairoBlur(radius)

    override fun setBlurFilter(paint: Paint, blur: PlatformBlurFilter?) {
        (paint as CairoPaint).blur = blur as? CairoBlur
    }

    override fun createTintColorFilter(color: Color, blendMode: BlendMode): PlatformColorFilter =
        CairoTintFilter(color, blendMode)

    override fun createColorMatrixColorFilter(colorMatrix: ColorMatrix): PlatformColorFilter =
        CairoMatrixFilter(colorMatrix.values.copyOf())

    override fun createLightingColorFilter(multiply: Color, add: Color): PlatformColorFilter =
        CairoMatrixFilter(
            floatArrayOf(
                multiply.red,
                0f,
                0f,
                0f,
                add.red * 255f,
                0f,
                multiply.green,
                0f,
                0f,
                add.green * 255f,
                0f,
                0f,
                multiply.blue,
                0f,
                add.blue * 255f,
                0f,
                0f,
                0f,
                1f,
                0f,
            )
        )

    override fun colorMatrixFromFilter(filter: PlatformColorFilter) =
        ColorMatrix((filter as CairoMatrixFilter).matrix.copyOf())

    override fun createCornerPathEffect(radius: Float): PathEffect = CairoCorner(radius)

    override fun createDashPathEffect(intervals: FloatArray, phase: Float): PathEffect =
        CairoDash(intervals, phase)

    override fun createChainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect =
        CairoChain(outer, inner)

    override fun createStampedPathEffect(
        shape: Path,
        advance: Float,
        phase: Float,
        style: StampedPathEffectStyle,
    ): PathEffect = CairoStamp(shape, advance, phase)
}

private data class CairoTypeface(val family: String)

private object CairoLoadedFonts {
    private val families = mutableMapOf<String, CairoTypeface>()

    fun load(font: PlatformFont): CairoTypeface? =
        families[font.cacheKey]
            ?: when (font) {
                is LoadedFont -> {
                    val bytes = font.data
                    if (bytes.isEmpty()) return null
                    val family =
                        bytes.usePinned { pinned ->
                            val result =
                                kp_font_register(pinned.addressOf(0).reinterpret(), bytes.size)
                                    ?: return null
                            try {
                                result.toKString()
                            } finally {
                                kp_string_free(result)
                            }
                        }
                    CairoTypeface(family).also { families[font.cacheKey] = it }
                }
                is SystemFont -> CairoTypeface(font.identity).also { families[font.cacheKey] = it }
            }
}

private class CairoFontLoader : PlatformTypefacesLoader {
    override fun loadBlocking(font: Font): Any? =
        (font as? PlatformFont)?.let(CairoLoadedFonts::load)

    override suspend fun awaitLoad(font: Font): Any? = loadBlocking(font)

    override val cacheKey: Any = "cairo-pango"

    override fun loadPlatformTypes(
        fontFamily: FontFamily,
        fontWeight: FontWeight,
        fontStyle: FontStyle,
    ): Any = CairoTypeface(fontFamily.pangoFallbackFamily())

    override val fontCollection: Any = Unit
}

private data class CairoParagraphIntrinsics(
    val text: String,
    val style: TextStyle,
    val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
) : ParagraphIntrinsics {
    private val natural =
        createPangoLayout(
            text,
            style,
            annotations,
            placeholders,
            density,
            Constraints(),
            Int.MAX_VALUE,
            TextOverflow.Clip,
            fontFamilyResolver,
        )
    override val maxIntrinsicWidth: Float =
        kp_layout_width_exact(natural).toFloat() + maxLetterSpacing(style, annotations, density)
    override val minIntrinsicWidth: Float = run {
        kp_layout_wrap_words(natural)
        kp_layout_width(natural, 1)
        val value =
            (0 until kp_layout_line_count(natural)).maxOfOrNull {
                kp_layout_line_width(natural, it)
            } ?: 0
        kp_layout_width(natural, -1)
        kp_layout_wrap(natural)
        value.toFloat()
    }
}

private fun maxLetterSpacing(
    style: TextStyle,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    density: Density,
): Float {
    val baseSize = style.pixelFontSize(density)
    var spacing =
        if (style.letterSpacing.isUnspecified) 0f
        else style.letterSpacing.pixelSize(density, baseSize)
    annotations.forEach { range ->
        val span = range.item as? SpanStyle ?: return@forEach
        if (span.letterSpacing.isUnspecified) return@forEach
        val size = span.fontSize.pixelSize(density, baseSize)
        spacing = max(spacing, span.letterSpacing.pixelSize(density, size))
    }
    return spacing.coerceAtLeast(0f)
}

private fun FontFamily?.pangoFallbackFamily(): String =
    when (this) {
        FontFamily.Serif -> "serif"
        FontFamily.Monospace -> "monospace"
        FontFamily.Cursive -> "cursive"
        else -> "sans-serif"
    }

private fun FontFamily?.pangoFamily(
    resolver: FontFamily.Resolver,
    weight: FontWeight,
    style: FontStyle,
): String =
    (resolver.resolve(this, weight, style, FontSynthesis.All).value as? CairoTypeface)?.family
        ?: pangoFallbackFamily()

private fun TextStyle.pixelFontSize(density: Density): Float =
    with(density) { if (fontSize.isUnspecified) 14.sp.toPx() else fontSize.toPx() }

private fun TextUnit.pixelSize(density: Density, emSize: Float): Float =
    when {
        isEm -> value * emSize
        isUnspecified -> emSize
        else -> with(density) { toPx() }
    }

private fun applyPangoSpan(
    attrs: COpaquePointer,
    text: String,
    rangeStart: Int,
    rangeEnd: Int,
    span: SpanStyle,
    density: Density,
    inheritedSize: Float,
    fontFamilyResolver: FontFamily.Resolver,
    includeForeground: Boolean,
    includeBackground: Boolean,
) {
    val start = text.charToByte(rangeStart)
    val end = text.charToByte(rangeEnd)
    if (start >= end) return
    val size = span.fontSize.pixelSize(density, inheritedSize)
    if (!span.fontSize.isUnspecified) kp_attrs_size(attrs, start, end, size.toDouble())
    span.fontWeight?.let { kp_attrs_weight(attrs, start, end, it.weight) }
    span.fontStyle?.let { kp_attrs_style(attrs, start, end, if (it == FontStyle.Italic) 1 else 0) }
    span.fontFamily?.let {
        kp_attrs_family(
            attrs,
            start,
            end,
            it.pangoFamily(
                fontFamilyResolver,
                span.fontWeight ?: FontWeight.Normal,
                span.fontStyle ?: FontStyle.Normal,
            ),
        )
    }
    span.fontFeatureSettings?.let { kp_attrs_features(attrs, start, end, it) }
    span.localeList?.firstOrNull()?.let { kp_attrs_language(attrs, start, end, it.toLanguageTag()) }
    if (!span.letterSpacing.isUnspecified) {
        kp_attrs_letter_spacing(
            attrs,
            start,
            end,
            span.letterSpacing.pixelSize(density, size).toDouble(),
        )
    }
    span.baselineShift?.let { shift ->
        if (!shift.multiplier.isNaN())
            kp_attrs_rise(attrs, start, end, (shift.multiplier * size * 0.8f).toDouble())
    }
    span.textGeometricTransform?.let { transform ->
        if (transform.scaleX != 1f) kp_attrs_scale(attrs, start, end, transform.scaleX.toDouble())
    }
    val foreground = (span.brush as? SolidColor)?.value ?: span.color
    if (includeForeground && foreground != Color.Unspecified) {
        val extraAlpha = if (span.alpha.isNaN()) 1f else span.alpha
        kp_attrs_foreground(
            attrs,
            start,
            end,
            foreground.red.toDouble(),
            foreground.green.toDouble(),
            foreground.blue.toDouble(),
            (foreground.alpha * extraAlpha).toDouble(),
        )
    } else if (includeForeground && span.brush != null) {
        // Non-solid brushes are composited through a byte-range glyph mask during paint.
        kp_attrs_foreground_alpha(attrs, start, end, 0.0)
    }
    if (includeBackground && span.background != Color.Unspecified) {
        kp_attrs_background(
            attrs,
            start,
            end,
            span.background.red.toDouble(),
            span.background.green.toDouble(),
            span.background.blue.toDouble(),
            span.background.alpha.toDouble(),
        )
    }
    val decoration = span.textDecoration
    kp_attrs_decoration(
        attrs,
        start,
        end,
        if (decoration?.contains(TextDecoration.Underline) == true) 1 else 0,
        if (decoration?.contains(TextDecoration.LineThrough) == true) 1 else 0,
    )
}

private fun createPangoLayout(
    text: String,
    style: TextStyle,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    density: Density,
    constraints: Constraints,
    maxLines: Int,
    overflow: TextOverflow,
    fontFamilyResolver: FontFamily.Resolver,
): COpaquePointer {
    val layout = checkNotNull(kp_layout_create())
    kp_layout_text(layout, text)
    kp_layout_font(
        layout,
        style.fontFamily.pangoFamily(
            fontFamilyResolver,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
        ),
        style.pixelFontSize(density).toDouble(),
        style.fontWeight?.weight ?: 400,
        if (style.fontStyle == FontStyle.Italic) 1 else 0,
    )
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else -1
    kp_layout_width(layout, width)
    kp_layout_wrap(layout)
    kp_layout_lines(layout, if (maxLines == Int.MAX_VALUE) 0 else maxLines)
    kp_layout_ellipsize(layout, if (overflow == TextOverflow.Ellipsis) 1 else 0)
    kp_layout_alignment(
        layout,
        when (style.textAlign) {
            TextAlign.Center -> 1
            TextAlign.Right,
            TextAlign.End -> 2
            else -> 0
        },
    )
    val attrs = checkNotNull(kp_attrs_create())
    val baseSize = style.pixelFontSize(density)
    applyPangoSpan(
        attrs,
        text,
        0,
        text.length,
        style.toSpanStyle(),
        density,
        baseSize,
        fontFamilyResolver,
        includeForeground = false,
        includeBackground = false,
    )
    annotations.forEach { range ->
        val span = range.item as? SpanStyle ?: return@forEach
        applyPangoSpan(
            attrs,
            text,
            range.start.coerceIn(0, text.length),
            range.end.coerceIn(0, text.length),
            span,
            density,
            baseSize,
            fontFamilyResolver,
            includeForeground = true,
            includeBackground = true,
        )
    }
    placeholders.forEach { range ->
        val start = text.charToByte(range.start.coerceIn(0, text.length))
        val end = text.charToByte(range.end.coerceIn(0, text.length))
        val widthPx = range.item.width.pixelSize(density, baseSize)
        val heightPx = range.item.height.pixelSize(density, baseSize)
        val ascent = baseSize * 0.8f
        val descent = baseSize * 0.2f
        val y =
            when (range.item.placeholderVerticalAlign) {
                PlaceholderVerticalAlign.Top,
                PlaceholderVerticalAlign.TextTop -> -ascent
                PlaceholderVerticalAlign.Bottom,
                PlaceholderVerticalAlign.TextBottom -> descent - heightPx
                PlaceholderVerticalAlign.Center,
                PlaceholderVerticalAlign.TextCenter -> (-ascent + descent - heightPx) / 2f
                else -> -heightPx
            }
        if (start < end)
            kp_attrs_shape(attrs, start, end, widthPx.toDouble(), heightPx.toDouble(), y.toDouble())
    }
    kp_attrs_set(layout, attrs)
    kp_attrs_destroy(attrs)
    return layout
}

private fun String.charToByte(index: Int): Int =
    substring(0, index.coerceIn(0, length)).encodeToByteArray().size

private fun String.byteToChar(index: Int): Int {
    if (index <= 0) return 0
    val target = index.coerceAtMost(encodeToByteArray().size)
    var bytes = 0
    for (i in indices) {
        if (bytes >= target) return i
        bytes += this[i].toString().encodeToByteArray().size
    }
    return length
}

private class CairoParagraph(
    private val intrinsics: CairoParagraphIntrinsics,
    private val maxLines: Int,
    private val overflow: TextOverflow,
    private val constraints: Constraints,
) : PlatformParagraph {
    private val layout =
        createPangoLayout(
            intrinsics.text,
            intrinsics.style,
            intrinsics.annotations,
            intrinsics.placeholders,
            intrinsics.density,
            constraints,
            maxLines,
            overflow,
            intrinsics.fontFamilyResolver,
        )
    private val text
        get() = intrinsics.text

    private val naturalWidth = kp_layout_width_px(layout).toFloat()
    private val pangoLineCount = max(1, kp_layout_line_count(layout))
    private val visibleLineCount = min(pangoLineCount, maxLines.coerceAtLeast(1))
    private val clipsExtraLines = overflow == TextOverflow.Clip && pangoLineCount > visibleLineCount
    private val visibleHeight =
        if (clipsExtraLines) {
            val lastLine = visibleLineCount - 1
            kp_layout_line_y(layout, lastLine) + kp_layout_line_height(layout, lastLine)
        } else {
            kp_layout_height_px(layout)
        }
    override val width: Float =
        if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else naturalWidth
    override val height: Float = visibleHeight.toFloat()
    override val minIntrinsicWidth: Float
        get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float
        get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float
        get() = kp_layout_baseline_px(layout).toFloat()

    override val lastBaseline: Float
        get() = getLineBaseline(lineCount - 1)

    override val didExceedMaxLines: Boolean
        get() = pangoLineCount > visibleLineCount || kp_layout_is_ellipsized(layout) != 0

    override val lineCount: Int
        get() = visibleLineCount

    override val placeholderRects: List<Rect?> =
        intrinsics.placeholders.map { range ->
            val byte = text.charToByte(range.start.coerceIn(0, text.length))
            val x = kp_layout_index_x(layout, byte).toFloat()
            val y = kp_layout_index_y(layout, byte).toFloat()
            val width = abs(kp_layout_index_width(layout, byte)).toFloat()
            val height = max(1, kp_layout_index_height(layout, byte)).toFloat()
            Rect(x, y, x + width, y + height)
        }

    override fun getPathForRange(start: Int, end: Int): Path =
        Path().apply {
            for (offset in start.coerceAtLeast(0) until end.coerceAtMost(text.length)) addRect(
                getBoundingBox(offset)
            )
        }

    override fun getCursorRect(offset: Int): Rect {
        val byte = text.charToByte(offset)
        val x = kp_layout_index_x(layout, byte).toFloat()
        val y = kp_layout_index_y(layout, byte).toFloat()
        val h = max(1, kp_layout_index_height(layout, byte)).toFloat()
        return Rect(x, y, x, y + h)
    }

    override fun getLineLeft(lineIndex: Int) = kp_layout_line_x(layout, lineIndex).toFloat()

    override fun getLineRight(lineIndex: Int) = getLineLeft(lineIndex) + getLineWidth(lineIndex)

    override fun getLineTop(lineIndex: Int) = kp_layout_line_y(layout, lineIndex).toFloat()

    override fun getLineBaseline(lineIndex: Int) = getLineTop(lineIndex) + firstBaseline

    override fun getLineBottom(lineIndex: Int) = getLineTop(lineIndex) + getLineHeight(lineIndex)

    override fun getLineHeight(lineIndex: Int) = kp_layout_line_height(layout, lineIndex).toFloat()

    override fun getLineWidth(lineIndex: Int) = kp_layout_line_width(layout, lineIndex).toFloat()

    override fun getLineStart(lineIndex: Int) =
        text.byteToChar(kp_layout_line_start(layout, lineIndex))

    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean) =
        text.byteToChar(kp_layout_line_end(layout, lineIndex))

    override fun isLineEllipsized(lineIndex: Int) =
        kp_layout_is_ellipsized(layout) != 0 && lineIndex == lineCount - 1

    override fun getLineForOffset(offset: Int): Int {
        val byte = text.charToByte(offset)
        return (0 until lineCount).firstOrNull { byte <= kp_layout_line_end(layout, it) }
            ?: lineCount - 1
    }

    override fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean) =
        kp_layout_index_x(layout, text.charToByte(offset)).toFloat()

    override fun getParagraphDirection(offset: Int) =
        if (kp_layout_line_direction(layout, getLineForOffset(offset)) != 0)
            ResolvedTextDirection.Rtl
        else ResolvedTextDirection.Ltr

    override fun getBidiRunDirection(offset: Int) =
        if (kp_layout_direction(layout, text.charToByte(offset)) != 0) ResolvedTextDirection.Rtl
        else ResolvedTextDirection.Ltr

    override fun getLineForVerticalPosition(vertical: Float) =
        (0 until lineCount).firstOrNull { vertical < getLineBottom(it) } ?: lineCount - 1

    override fun getOffsetForPosition(position: Offset) =
        text.byteToChar(kp_layout_xy_index(layout, position.x.toInt(), position.y.toInt()))

    override fun getRangeForRect(
        rect: Rect,
        granularity: TextGranularity,
        inclusionStrategy: TextInclusionStrategy,
    ) = TextRange(getOffsetForPosition(rect.topLeft), getOffsetForPosition(rect.bottomRight))

    override fun getBoundingBox(offset: Int): Rect {
        val start = getCursorRect(offset)
        val end = getCursorRect((offset + 1).coerceAtMost(text.length))
        return Rect(start.left, start.top, max(start.left + 1f, end.left), start.bottom)
    }

    override fun fillBoundingBoxes(range: TextRange, array: FloatArray, arrayStart: Int) {
        var out = arrayStart
        for (i in range.min until range.max) {
            val r = getBoundingBox(i)
            array[out++] = r.left
            array[out++] = r.top
            array[out++] = r.right
            array[out++] = r.bottom
        }
    }

    override fun getWordBoundary(offset: Int): TextRange {
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.length && !text[end].isWhitespace()) end++
        return TextRange(start, end)
    }

    override fun getLineAscent(lineIndex: Int) = -firstBaseline

    override fun getLineDescent(lineIndex: Int) = getLineHeight(lineIndex) - firstBaseline

    override fun paint(
        canvas: Canvas,
        color: Color,
        shadow: androidx.compose.ui.graphics.Shadow?,
        textDecoration: TextDecoration?,
    ) = paint(canvas, color, shadow, textDecoration, null, BlendMode.SrcOver)

    override fun paint(
        canvas: Canvas,
        color: Color,
        shadow: androidx.compose.ui.graphics.Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) {
        val target = canvas as CairoCanvas
        paintWithLineClip(target) {
            val actual =
                if (color != Color.Unspecified) color
                else intrinsics.style.color.let { if (it != Color.Unspecified) it else Color.Black }
            if (shadow != null && shadow != androidx.compose.ui.graphics.Shadow.None) {
                target.save()
                target.translate(shadow.offset.x, shadow.offset.y)
                kc_set_source_rgba(
                    target.context,
                    shadow.color.red.toDouble(),
                    shadow.color.green.toDouble(),
                    shadow.color.blue.toDouble(),
                    shadow.color.alpha.toDouble(),
                )
                if (shadow.blurRadius > 0f) kc_push_group(target.context)
                kp_layout_mask_range(target.context, layout, 0, text.encodeToByteArray().size)
                if (shadow.blurRadius > 0f)
                    kc_pop_group_blur(
                        target.context,
                        shadow.blurRadius.toDouble(),
                        shadow.blurRadius.toDouble(),
                        3,
                    )
                target.restore()
            }
            intrinsics.annotations.forEach { range ->
                val span = range.item as? SpanStyle ?: return@forEach
                val spanShadow = span.shadow ?: return@forEach
                if (spanShadow == androidx.compose.ui.graphics.Shadow.None) return@forEach
                target.save()
                target.translate(spanShadow.offset.x, spanShadow.offset.y)
                kc_set_source_rgba(
                    target.context,
                    spanShadow.color.red.toDouble(),
                    spanShadow.color.green.toDouble(),
                    spanShadow.color.blue.toDouble(),
                    spanShadow.color.alpha.toDouble(),
                )
                if (spanShadow.blurRadius > 0f) kc_push_group(target.context)
                kp_layout_mask_range(
                    target.context,
                    layout,
                    text.charToByte(range.start),
                    text.charToByte(range.end),
                )
                if (spanShadow.blurRadius > 0f)
                    kc_pop_group_blur(
                        target.context,
                        spanShadow.blurRadius.toDouble(),
                        spanShadow.blurRadius.toDouble(),
                        3,
                    )
                target.restore()
            }
            kc_set_source_rgba(
                target.context,
                actual.red.toDouble(),
                actual.green.toDouble(),
                actual.blue.toDouble(),
                actual.alpha.toDouble(),
            )
            kp_layout_draw(target.context, layout)
            intrinsics.annotations.forEach { range ->
                val span = range.item as? SpanStyle ?: return@forEach
                val brush = span.brush?.takeUnless { it is SolidColor } ?: return@forEach
                val paint = CairoPaint()
                brush.applyTo(
                    androidx.compose.ui.geometry.Size(width, height),
                    paint,
                    if (span.alpha.isNaN()) 1f else span.alpha,
                )
                if (paint.alpha < 1f)
                    target.saveLayer(
                        Rect(0f, 0f, width, height),
                        CairoPaint().also { it.alpha = paint.alpha },
                    )
                target.applyPaint(paint.also { it.alpha = 1f })
                kp_layout_mask_range(
                    target.context,
                    layout,
                    text.charToByte(range.start),
                    text.charToByte(range.end),
                )
                if (paint.alpha < 1f) target.restore()
            }
            if (textDecoration?.contains(TextDecoration.Underline) == true) {
                target.drawLine(
                    Offset(0f, firstBaseline + 2f),
                    Offset(naturalWidth, firstBaseline + 2f),
                    CairoPaint().also {
                        it.color = actual
                        it.strokeWidth = 1f
                    },
                )
            }
        }
    }

    override fun paint(
        canvas: Canvas,
        brush: Brush,
        alpha: Float,
        shadow: androidx.compose.ui.graphics.Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) {
        if (brush is SolidColor) {
            val color =
                brush.value.let { if (alpha.isNaN()) it else it.copy(alpha = it.alpha * alpha) }
            paint(canvas, color, shadow, textDecoration, drawStyle, blendMode)
            return
        }
        val target = canvas as CairoCanvas
        paintWithLineClip(target) {
            val brushPaint = CairoPaint()
            brush.applyTo(
                androidx.compose.ui.geometry.Size(width, height),
                brushPaint,
                if (alpha.isNaN()) 1f else alpha,
            )
            if (brushPaint.alpha < 1f)
                target.saveLayer(
                    Rect(0f, 0f, width, height),
                    CairoPaint().also { it.alpha = brushPaint.alpha },
                )
            target.applyPaint(brushPaint.also { it.alpha = 1f })
            kp_layout_mask_range(target.context, layout, 0, text.encodeToByteArray().size)
            if (brushPaint.alpha < 1f) target.restore()
        }
    }

    private inline fun paintWithLineClip(canvas: CairoCanvas, block: () -> Unit) {
        if (!clipsExtraLines) {
            block()
            return
        }
        canvas.save()
        canvas.clipRect(0f, 0f, width, height, ClipOp.Intersect)
        try {
            block()
        } finally {
            canvas.restore()
        }
    }
}

internal object CairoText : PlatformText {
    override fun createParagraph(
        text: String,
        style: TextStyle,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        maxLines: Int,
        overflow: TextOverflow,
        constraints: Constraints,
        density: Density,
        fontFamilyResolver: FontFamily.Resolver,
    ): PlatformParagraph =
        CairoParagraph(
            CairoParagraphIntrinsics(
                text,
                style,
                annotations,
                placeholders,
                density,
                fontFamilyResolver,
            ),
            maxLines,
            overflow,
            constraints,
        )

    override fun createParagraph(
        paragraphIntrinsics: ParagraphIntrinsics,
        maxLines: Int,
        overflow: TextOverflow,
        constraints: Constraints,
    ): PlatformParagraph =
        CairoParagraph(
            paragraphIntrinsics as CairoParagraphIntrinsics,
            maxLines,
            overflow,
            constraints,
        )

    override fun createParagraphIntrinsics(
        text: String,
        style: TextStyle,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        density: Density,
        fontFamilyResolver: FontFamily.Resolver,
    ): ParagraphIntrinsics =
        CairoParagraphIntrinsics(
            text,
            style,
            annotations,
            placeholders,
            density,
            fontFamilyResolver,
        )

    override fun createFontFamilyResolver(): FontFamily.Resolver =
        createPlatformFontFamilyResolver(CairoFontLoader())

    override fun createFontFamilyResolver(coroutineContext: CoroutineContext): FontFamily.Resolver =
        createPlatformFontFamilyResolver(CairoFontLoader(), coroutineContext)

    override fun findPrecedingBreak(text: String, index: Int) = (index - 1).coerceAtLeast(0)

    override fun findFollowingBreak(text: String, index: Int) =
        (index + 1).coerceAtMost(text.length)

    override val defaultFontRasterizationSettings =
        FontRasterizationSettings(
            subpixelPositioning = true,
            smoothing = FontSmoothing.AntiAlias,
            hinting = FontHinting.Slight,
            autoHintingForced = false,
        )
}

internal fun runBackendSelfTests() {
    val png =
        byteArrayOf(
            -119,
            80,
            78,
            71,
            13,
            10,
            26,
            10,
            0,
            0,
            0,
            13,
            73,
            72,
            68,
            82,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            1,
            8,
            4,
            0,
            0,
            0,
            -75,
            28,
            12,
            2,
            0,
            0,
            0,
            11,
            73,
            68,
            65,
            84,
            120,
            -38,
            99,
            100,
            -8,
            15,
            0,
            1,
            5,
            1,
            1,
            39,
            24,
            -29,
            102,
            0,
            0,
            0,
            0,
            73,
            69,
            78,
            68,
            -82,
            66,
            96,
            -126,
        )
    val decoded = CairoGraphics.decodeImageBitmap(png)
    check(decoded.width == 1 && decoded.height == 1) { "Encoded-image decoding failed" }

    val first = CairoPath().apply { addOval(Rect(0f, 0f, 40f, 40f)) }
    val second = CairoPath().apply { addOval(Rect(20f, 0f, 60f, 40f)) }
    for (operation in
        listOf(
            PathOperation.Difference,
            PathOperation.Intersect,
            PathOperation.Union,
            PathOperation.Xor,
            PathOperation.ReverseDifference,
        )) {
        check(CairoPath().op(first, second, operation)) { "Path operation $operation failed" }
    }

    val blurSurface = CairoSurface(9, 9)
    blurSurface.clear()
    blurSurface.data.reinterpret<IntVar>()[4 * (blurSurface.stride / 4) + 4] = -1
    kc_surface_dirty(blurSurface.handle)
    kc_surface_blur(blurSurface.handle, 3.0, 3.0, 3)
    check(blurSurface.data.reinterpret<IntVar>()[4 * (blurSurface.stride / 4) + 3] != 0) {
        "Blur filter failed"
    }
    blurSurface.close()

    fun renderTest(block: (CairoCanvas) -> Unit): CairoSurface {
        val surface = CairoSurface(64, 64)
        surface.clear()
        val context = checkNotNull(kc_create(surface.handle))
        block(CairoCanvas(context))
        kc_destroy(context)
        surface.flush()
        return surface
    }
    fun CairoSurface.pixel(x: Int, y: Int): Color =
        Color(data.reinterpret<IntVar>()[y * (stride / 4) + x])

    val sweepSurface = renderTest { canvas ->
        canvas.drawRect(
            0f,
            0f,
            64f,
            64f,
            CairoPaint().also {
                it.shader =
                    CairoGraphics.createSweepGradientShader(
                        Offset(32f, 32f),
                        listOf(Color.Red, Color.Green, Color.Blue, Color.Red),
                        listOf(0f, 0.33f, 0.66f, 1f),
                    )
            },
        )
    }
    val sweepRight = sweepSurface.pixel(56, 32)
    val sweepBottom = sweepSurface.pixel(32, 56)
    val sweepTop = sweepSurface.pixel(32, 8)
    check(sweepRight.red > sweepRight.green && sweepRight.red > sweepRight.blue) {
        "Sweep-gradient origin is incorrect"
    }
    check(sweepBottom != sweepTop && sweepBottom != sweepRight) {
        "Sweep gradient did not vary around its center"
    }
    sweepSurface.close()

    val compositeSurface = renderTest { canvas ->
        val destination =
            CairoGraphics.createLinearGradientShader(
                Offset.Zero,
                Offset(64f, 0f),
                listOf(Color.Blue, Color.Blue),
                null,
                TileMode.Clamp,
            )
        val source =
            CairoGraphics.createLinearGradientShader(
                Offset.Zero,
                Offset(64f, 0f),
                listOf(Color.Red.copy(alpha = 0.5f), Color.Red.copy(alpha = 0.5f)),
                null,
                TileMode.Clamp,
            )
        canvas.drawRect(
            0f,
            0f,
            64f,
            64f,
            CairoPaint().also {
                it.shader =
                    CairoGraphics.createCompositeShader(destination, source, BlendMode.SrcOver)
            },
        )
    }
    val composite = compositeSurface.pixel(32, 32)
    check(composite.red > 0.35f && composite.blue > 0.35f) {
        "Composite shader ignored a source or destination"
    }
    compositeSurface.close()

    val filteredSurface = renderTest { canvas ->
        val invert =
            ColorMatrix(
                floatArrayOf(
                    -1f,
                    0f,
                    0f,
                    0f,
                    255f,
                    0f,
                    -1f,
                    0f,
                    0f,
                    255f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    255f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        canvas.drawRect(
            0f,
            0f,
            32f,
            64f,
            CairoPaint().also {
                it.color = Color(0xff204060)
                it.colorFilter = ColorFilter.colorMatrix(invert)
            },
        )
        canvas.drawRect(
            32f,
            0f,
            64f,
            64f,
            CairoPaint().also {
                it.color = Color.Green
                it.colorFilter = ColorFilter.tint(Color.Red, BlendMode.SrcIn)
            },
        )
    }
    val inverted = filteredSurface.pixel(16, 32)
    val tinted = filteredSurface.pixel(48, 32)
    check(inverted.red > 0.75f && inverted.green > 0.6f && inverted.blue > 0.5f) {
        "Color-matrix filter produced incorrect channels"
    }
    check(tinted.red > 0.9f && tinted.green < 0.1f && tinted.blue < 0.1f) {
        "Tint filter did not apply its blend mode"
    }
    filteredSurface.close()

    val verticesSurface = renderTest { canvas ->
        canvas.drawVertices(
            Vertices(
                VertexMode.TriangleFan,
                listOf(
                    Offset(32f, 32f),
                    Offset(4f, 4f),
                    Offset(60f, 4f),
                    Offset(60f, 60f),
                    Offset(4f, 60f),
                    Offset(4f, 4f),
                ),
                listOf(
                    Offset(32f, 32f),
                    Offset(4f, 4f),
                    Offset(60f, 4f),
                    Offset(60f, 60f),
                    Offset(4f, 60f),
                    Offset(4f, 4f),
                ),
                listOf(Color.White, Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Red),
                emptyList(),
            ),
            BlendMode.SrcOver,
            CairoPaint(),
        )
    }
    check(
        verticesSurface.pixel(32, 12).alpha > 0.8f && verticesSurface.pixel(52, 32).alpha > 0.8f
    ) {
        "drawVertices failed to cover triangle-fan geometry"
    }
    check(verticesSurface.pixel(32, 12) != verticesSurface.pixel(52, 32)) {
        "drawVertices did not interpolate vertex colors"
    }
    verticesSurface.close()

    memScoped {
        val byteCount = alloc<IntVar>()
        val pointer = kp_test_font_data(byteCount.ptr)
        check(pointer != null && byteCount.value > 0) {
            "Could not obtain a system font for LoadedFont testing"
        }
        val bytes = pointer!!.readBytes(byteCount.value)
        kp_bytes_free(pointer)
        val loaded = LoadedFont("compose-self-test", { bytes }, FontWeight.Normal, FontStyle.Normal)
        val typeface = CairoFontLoader().loadBlocking(loaded) as? CairoTypeface
        check(!typeface?.family.isNullOrEmpty()) {
            "LoadedFont was not registered with Fontconfig/Pango"
        }
    }

    // Resolved Compose styles use a transparent default background. It must not
    // turn into a Pango background rectangle around otherwise ordinary text.
    val selfTestFontResolver = CairoText.createFontFamilyResolver()
    val plainLayout =
        createPangoLayout(
            " Hi",
            TextStyle(fontSize = 20.sp, background = Color.Transparent),
            emptyList(),
            emptyList(),
            Density(1f),
            Constraints(maxWidth = 80),
            Int.MAX_VALUE,
            TextOverflow.Clip,
            selfTestFontResolver,
        )
    val plainSurface = CairoSurface(80, 40)
    plainSurface.clear()
    val plainContext = checkNotNull(kc_create(plainSurface.handle))
    kc_set_source_rgba(plainContext, 1.0, 1.0, 1.0, 1.0)
    kp_layout_draw(plainContext, plainLayout)
    kc_destroy(plainContext)
    kp_layout_destroy(plainLayout)
    plainSurface.flush()
    val plainPixels = plainSurface.data.reinterpret<IntVar>()
    check(plainPixels[1 * (plainSurface.stride / 4) + 1] == 0) {
        "Transparent text style painted a background rectangle"
    }
    check(
        (0 until plainSurface.height).any { y ->
            (0 until plainSurface.width).any { x ->
                plainPixels[y * (plainSurface.stride / 4) + x] != 0
            }
        }
    ) {
        "Plain text did not render"
    }
    plainSurface.close()

    val richText = "Rich \uFFFC text"
    val placeholder = Placeholder(12.sp, 16.sp, PlaceholderVerticalAlign.Center)
    val intrinsics =
        CairoParagraphIntrinsics(
            text = richText,
            style = TextStyle(fontSize = 18.sp),
            annotations =
                listOf(
                    AnnotatedString.Range(
                        SpanStyle(
                            color = Color(0xff66ccff),
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        ),
                        0,
                        4,
                    )
                ),
            placeholders = listOf(AnnotatedString.Range(placeholder, 5, 6)),
            density = Density(1f),
            fontFamilyResolver = selfTestFontResolver,
        )
    val paragraph =
        CairoParagraph(intrinsics, Int.MAX_VALUE, TextOverflow.Clip, Constraints(maxWidth = 300))
    check(checkNotNull(paragraph.placeholderRects.single()).width > 0f) {
        "Rich-text placeholder shaping failed"
    }
    check(paragraph.getBidiRunDirection(0) == ResolvedTextDirection.Ltr) {
        "Text direction query failed"
    }

    val layerSurface = CairoSurface(96, 96)
    layerSurface.clear()
    val layerContext = checkNotNull(kc_create(layerSurface.handle))
    val platformLayer = CairoGraphicsLayer()
    val graphicsLayer = GraphicsLayer(platformLayer)
    graphicsLayer.record(Density(1f), LayoutDirection.Ltr, IntSize(56, 56)) {
        drawRect(
            Color.White,
            topLeft = Offset(8f, 8f),
            size = androidx.compose.ui.geometry.Size(40f, 40f),
        )
    }
    graphicsLayer.topLeft = IntOffset(20, 20)
    graphicsLayer.rotationX = 24f
    graphicsLayer.rotationY = -18f
    graphicsLayer.cameraDistance = 12f
    graphicsLayer.renderEffect = BlurEffect(2f, 2f, TileMode.Decal)
    graphicsLayer.colorFilter = ColorFilter.tint(Color.Red, BlendMode.SrcIn)
    platformLayer.draw(CairoCanvas(layerContext))
    kc_destroy(layerContext)
    layerSurface.flush()
    val layerPixels = layerSurface.data.reinterpret<IntVar>()
    check(
        (0 until layerSurface.height).any { y ->
            (0 until layerSurface.width).any { x ->
                layerPixels[y * (layerSurface.stride / 4) + x] != 0
            }
        }
    ) {
        "Blurred 3D graphics layer failed"
    }
    check(
        (0 until layerSurface.height).any { y ->
            (0 until layerSurface.width).any { x ->
                val color = Color(layerPixels[y * (layerSurface.stride / 4) + x])
                color.alpha > 0.2f && color.red > color.green * 2f && color.red > color.blue * 2f
            }
        }
    ) {
        "GraphicsLayer color filter failed"
    }
    layerSurface.close()

    println(
        "Cairo/Pango backend self-test passed: image, blur/effect, 5 path ops, sweep/composite shaders, color filters, vertices, LoadedFont, 3D layer, and rich text"
    )
}
