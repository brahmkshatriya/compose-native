package dev.textmorph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val SourceText = "Hi"
private const val TargetText = "Hello Compose"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(920.dp, 560.dp)),
        title = "Compose · Text Morph",
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) { TextMorphDemoPage() }
        }
    }
}

@Composable
internal fun TextMorphDemoPage() {
    val progress = remember { Animatable(0f) }
    var autoPlay by remember { mutableStateOf(true) }
    var replayRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(autoPlay, replayRequest) {
        progress.snapTo(0f)
        if (autoPlay) {
            while (isActive) {
                delay(650)
                progress.animateTo(1f, tween(1_450, easing = FastOutSlowInEasing))
                delay(1_100)
                progress.animateTo(0f, tween(1_050, easing = FastOutSlowInEasing))
            }
        } else {
            progress.animateTo(1f, tween(1_450, easing = FastOutSlowInEasing))
        }
    }

    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Hi → Hello Compose", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Each glyph leaves the compact greeting and settles into the full phrase.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MorphingTextStage(progress = progress.value)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    autoPlay = false
                    replayRequest += 1
                }
            ) {
                Text("Replay morph")
            }
            Text("Auto play")
            Switch(checked = autoPlay, onCheckedChange = { autoPlay = it })
            Text("${(progress.value * 100).toInt()}%")
        }
    }
}

@Composable
private fun MorphingTextStage(progress: Float) {
    val textMeasurer = rememberTextMeasurer(cacheSize = TargetText.length + SourceText.length)
    val textStyle = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black)
    val sourceGlyphs =
        remember(textMeasurer, textStyle) {
            SourceText.map { textMeasurer.measure(it.toString(), style = textStyle) }
        }
    val targetGlyphs =
        remember(textMeasurer, textStyle) {
            TargetText.map { textMeasurer.measure(it.toString(), style = textStyle) }
        }

    Surface(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color(0xff100f1c),
        tonalElevation = 6.dp,
    ) {
        Canvas(Modifier.fillMaxWidth()) {
            val sourceWidth = sourceGlyphs.sumOf { it.size.width }.toFloat()
            val targetWidth = targetGlyphs.sumOf { it.size.width }.toFloat()
            val sourceStart = (size.width - sourceWidth) / 2f
            val targetStart = (size.width - targetWidth) / 2f
            val sourceIStart = sourceStart + sourceGlyphs.first().size.width
            val centerY = size.height / 2f
            val gradient =
                Brush.linearGradient(
                    colors = listOf(Color(0xffd0bcff), Color(0xff80deea), Color(0xffffb4ab)),
                    start = Offset(targetStart, centerY),
                    end = Offset(targetStart + targetWidth, centerY),
                )

            repeat(9) { index ->
                val angle = index * 0.9f + progress * 3f
                val radius = 72f + index * 9f
                drawCircle(
                    color = Color.White.copy(alpha = 0.04f + progress * 0.03f),
                    radius = 2f + index % 3,
                    center =
                        Offset(
                            x = size.width / 2f + sin(angle) * radius,
                            y = centerY + sin(angle * 1.7f) * 86f,
                        ),
                )
            }

            var targetAdvance = 0f
            targetGlyphs.forEachIndexed { index, glyph ->
                val finalX = targetStart + targetAdvance
                val finalY = centerY - glyph.size.height / 2f
                when (index) {
                    0 -> {
                        drawMorphGlyph(
                            glyph = glyph,
                            brush = gradient,
                            x = lerp(sourceStart, finalX, progress),
                            y = finalY - sin(progress * PI).toFloat() * 8f,
                            alpha = 1f,
                            scale = 1f + sin(progress * PI).toFloat() * 0.08f,
                            rotation = 0f,
                        )
                    }
                    1 -> {
                        val glyphProgress = easedProgress(progress, 0.04f, 0.5f)
                        val currentX = lerp(sourceIStart, finalX, glyphProgress)
                        drawMorphGlyph(
                            glyph = sourceGlyphs[1],
                            brush = gradient,
                            x = currentX,
                            y = centerY - sourceGlyphs[1].size.height / 2f,
                            alpha = 1f - glyphProgress,
                            scale = 1f - glyphProgress * 0.18f,
                            rotation = -22f * glyphProgress,
                        )
                        drawMorphGlyph(
                            glyph = glyph,
                            brush = gradient,
                            x = currentX,
                            y = finalY,
                            alpha = glyphProgress,
                            scale = 0.72f + glyphProgress * 0.28f,
                            rotation = 22f * (1f - glyphProgress),
                        )
                    }
                    else -> {
                        val glyphProgress =
                            easedProgress(
                                value = progress,
                                start = 0.08f + index * 0.035f,
                                end = 0.72f + index * 0.018f,
                            )
                        val launchX = sourceIStart + sourceGlyphs[1].size.width / 2f
                        val arc = sin(glyphProgress * PI).toFloat()
                        drawMorphGlyph(
                            glyph = glyph,
                            brush = gradient,
                            x = lerp(launchX, finalX, glyphProgress),
                            y = finalY - arc * (38f + index * 2.5f),
                            alpha = glyphProgress,
                            scale = 0.18f + glyphProgress * 0.82f,
                            rotation = (1f - glyphProgress) * if (index % 2 == 0) 26f else -26f,
                        )
                    }
                }
                targetAdvance += glyph.size.width
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMorphGlyph(
    glyph: TextLayoutResult,
    brush: Brush,
    x: Float,
    y: Float,
    alpha: Float,
    scale: Float,
    rotation: Float,
) {
    if (alpha <= 0f) return
    val pivot = Offset(glyph.size.width / 2f, glyph.size.height / 2f)
    withTransform({
        translate(x, y)
        rotate(rotation, pivot)
        scale(scale, scale, pivot)
    }) {
        drawText(glyph, brush = brush, alpha = alpha.coerceIn(0f, 1f))
    }
}

private fun easedProgress(value: Float, start: Float, end: Float): Float {
    val normalized = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(normalized)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction
