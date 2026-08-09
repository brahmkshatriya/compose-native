@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
    org.jetbrains.compose.resources.InternalResourceApi::class,
)

package org.jetbrains.compose.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density

private const val defaultFontIdentity = "org.jetbrains.compose.resources.defaultFont"
private val defaultFont: Font = SystemFont(defaultFontIdentity)
private val fontCache = AsyncCache<String, Font>()

private fun ByteArray.footprint() = "[$size:${lastOrNull()?.toInt()}]"

@Deprecated(
    message = "Use the new Font function with variationSettings instead.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
actual fun Font(resource: FontResource, weight: FontWeight, style: FontStyle): Font =
    Font(resource, weight, style, FontVariation.Settings(weight, style))

@Composable
actual fun Font(
    resource: FontResource,
    weight: FontWeight,
    style: FontStyle,
    variationSettings: FontVariation.Settings,
): Font {
    val resourceReader = LocalResourceReader.currentOrPreview
    val fontFile by
        rememberResourceState(resource, weight, style, variationSettings, { defaultFont }) {
            environment ->
            val path = resource.getResourceItemByEnvironment(environment).path
            val key = "$path:$weight:$style:${variationSettings.getCacheKey()}"
            fontCache.getOrLoad(key) {
                val fontBytes = resourceReader.read(path)
                LoadedFont(
                    identity = "$key${fontBytes.footprint()}",
                    getData = { fontBytes },
                    weight = weight,
                    style = style,
                    variationSettings = variationSettings,
                )
            }
        }
    return fontFile
}

private fun FontVariation.Settings.getCacheKey(): String {
    val density = Density(1f)
    return settings
        .map { "${it::class.simpleName}(${it.axisName},${it.toVariationValue(density)})" }
        .sorted()
        .joinToString(",")
}
