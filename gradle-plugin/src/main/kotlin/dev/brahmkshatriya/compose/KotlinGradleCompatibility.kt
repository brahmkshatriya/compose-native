package dev.brahmkshatriya.compose

import org.gradle.api.Project

internal fun Project.enableRequestedCoordinateMatching() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlinPluginVersion =
            plugins
                .getPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID)
                .javaClass
                .`package`
                .implementationVersion
        if (kotlinPluginVersion.requiresRequestedCoordinateMatchingFlag()) {
            extensions.extraProperties.set(KMP_MATCH_REQUESTED_COORDINATES_PROPERTY, true)
        }
    }
}

internal fun String?.requiresRequestedCoordinateMatchingFlag(): Boolean {
    if (this == null) return true
    val components = substringBefore('-').split('.')
    val major = components.getOrNull(0)?.toIntOrNull() ?: return true
    val minor = components.getOrNull(1)?.toIntOrNull() ?: return true
    return major < 2 || major == 2 && minor < 4
}

internal const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"

private const val KMP_MATCH_REQUESTED_COORDINATES_PROPERTY =
    "kotlin.internal.kmp.allowMatchingByRequestedCoordinatesInMetadataTransformations"
