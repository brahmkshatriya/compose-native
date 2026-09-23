package org.jetbrains.compose.resources

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.compose.resources.vector.xmldom.Element
import org.jetbrains.compose.resources.vector.xmldom.ElementImpl
import org.xml.sax.InputSource

internal actual fun ByteArray.toImageBitmap(resourceDensity: Int, targetDensity: Int): ImageBitmap =
    decodeToImageBitmap()

internal actual fun ByteArray.toXmlElement(): Element =
    ElementImpl(
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(ByteArrayInputStream(this)))
            .documentElement
    )

internal actual class SvgElement internal constructor(val bytes: ByteArray)

internal actual fun ByteArray.toSvgElement(): SvgElement = SvgElement(copyOf())

internal actual fun SvgElement.toSvgPainter(density: Density): Painter {
    throw UnsupportedOperationException(
        "SVG Compose resources are not supported by this fork resource runtime yet. " +
            "Use an Android vector XML or an encoded bitmap resource."
    )
}
