@file:OptIn(
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
    org.jetbrains.compose.resources.InternalResourceApi::class,
)

package org.jetbrains.compose.resources

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import org.jetbrains.compose.resources.vector.xmldom.Element
import org.jetbrains.compose.resources.vector.xmldom.parse

internal actual fun ByteArray.toImageBitmap(resourceDensity: Int, targetDensity: Int): ImageBitmap =
    decodeToImageBitmap()

internal actual fun ByteArray.toXmlElement(): Element = parse(decodeToString())

internal actual class SvgElement internal constructor(val bytes: ByteArray)

internal actual fun ByteArray.toSvgElement(): SvgElement = SvgElement(copyOf())

internal actual fun SvgElement.toSvgPainter(density: Density): Painter {
    throw UnsupportedOperationException(
        "SVG Compose resources are not supported by the desktop-native resource runtime yet. " +
            "Use an Android vector XML or an encoded bitmap resource."
    )
}
