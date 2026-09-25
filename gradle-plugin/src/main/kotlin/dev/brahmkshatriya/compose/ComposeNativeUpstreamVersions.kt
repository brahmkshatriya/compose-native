package dev.brahmkshatriya.compose

import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentSelector

internal object ComposeNativeUpstreamVersions {
    private val values: Map<String, String> by lazy {
        val properties = Properties()
        checkNotNull(
                ComposeNativeUpstreamVersions::class.java.classLoader.getResourceAsStream(
                    "compose-native-upstream.properties"
                )
            ) {
                "Compose Native upstream version metadata is missing from the Gradle plugin"
            }
            .use(properties::load)
        properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    fun compose(family: String): String? =
        when {
            family == "material3" -> values["compose.material3"]
            family == "material3.adaptive" -> values["compose.material3.adaptive"]
            family in COMMON_COMPOSE_FAMILIES -> values["compose"]
            else -> null
        }

    fun androidx(family: String): String? = values["androidx.$family"]
}

internal fun ModuleComponentSelector.officialMetadataCoordinateOrNull(): String? {
    if (group.startsWith(FORK_COMPOSE_GROUP_PREFIX)) {
        val family = group.removePrefix(FORK_COMPOSE_GROUP_PREFIX)
        val version = ComposeNativeUpstreamVersions.compose(family) ?: return null
        return "$OFFICIAL_COMPOSE_GROUP_PREFIX$family:$module:$version"
    }
    if (group.startsWith(FORK_ANDROIDX_GROUP_PREFIX)) {
        val family = group.removePrefix(FORK_ANDROIDX_GROUP_PREFIX)
        val version = ComposeNativeUpstreamVersions.androidx(family) ?: return null
        return "androidx.$family:$module:$version"
    }
    return null
}

private val COMMON_COMPOSE_FAMILIES =
    setOf("animation", "foundation", "material", "runtime", "ui", "components")
