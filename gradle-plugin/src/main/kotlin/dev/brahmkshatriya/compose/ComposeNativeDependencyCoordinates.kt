package dev.brahmkshatriya.compose

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentSelector

internal fun composeForkCoordinateFor(
    group: String,
    module: String,
    version: String,
    useDesktopNativeFork: Boolean = false,
    includeJetBrainsAndroidx: Boolean = true,
    includeAndroidx: Boolean,
): String? {
    if (group.startsWith(OFFICIAL_COMPOSE_GROUP_PREFIX)) {
        val family = group.removePrefix(OFFICIAL_COMPOSE_GROUP_PREFIX)
        if (
            (useDesktopNativeFork &&
                family in COMPOSE_FAMILIES &&
                !(family == "ui" && module == UI_UKIT_MODULE)) ||
                "$family:$module" in CROSS_PLATFORM_COMPOSE_MODULES
        ) {
            return "$FORK_COMPOSE_GROUP_PREFIX$family:$module:$version"
        }
    }
    if (includeJetBrainsAndroidx && group.startsWith(OFFICIAL_ANDROIDX_GROUP_PREFIX)) {
        val family = group.removePrefix(OFFICIAL_ANDROIDX_GROUP_PREFIX)
        if (family in FORK_ANDROIDX_FAMILIES) {
            return "$FORK_ANDROIDX_GROUP_PREFIX$family:$module:$version"
        }
    }
    if (includeAndroidx && group.startsWith(ANDROIDX_GROUP_PREFIX)) {
        val family = group.removePrefix(ANDROIDX_GROUP_PREFIX)
        if (family in FORK_ANDROIDX_FAMILIES) {
            return "$FORK_ANDROIDX_GROUP_PREFIX$family:$module:$version"
        }
    }
    if (includeAndroidx && group.startsWith(ANDROIDX_COMPOSE_GROUP_PREFIX)) {
        val family = group.removePrefix(ANDROIDX_COMPOSE_GROUP_PREFIX)
        if (
            family in ANDROIDX_COMPOSE_FAMILIES &&
                (family != "ui" || useDesktopNativeFork) &&
                module.removeSuffix("-android") in ANDROIDX_COMPOSE_FORK_MODULES
        ) {
            return "$FORK_COMPOSE_GROUP_PREFIX$family:$module:$version"
        }
    }
    return null
}

internal data class ModuleSubstitution(val officialCoordinate: String, val forkCoordinate: String)

internal fun ModuleSubstitution.targetsRedirectedCommonMetadataModule(): Boolean {
    val group = forkCoordinate.substringBefore(':')
    val module = forkCoordinate.substringAfter(':').substringBefore(':')
    return isRedirectedCommonMetadataModule(group, module)
}

internal fun ModuleComponentSelector.isRedirectedCommonMetadataModule(): Boolean =
    isRedirectedCommonMetadataModule(group, module)

internal fun isRedirectedCommonMetadataModule(group: String, module: String): Boolean =
    when {
        group == "${FORK_COMPOSE_GROUP_PREFIX}runtime" ->
            module in REDIRECTED_COMMON_METADATA_RUNTIME_MODULES
        group == "${FORK_COMPOSE_GROUP_PREFIX}ui" -> module == "ui-skiko"
        group.startsWith(FORK_ANDROIDX_GROUP_PREFIX) ->
            group.removePrefix(FORK_ANDROIDX_GROUP_PREFIX) in
                REDIRECTED_COMMON_METADATA_ANDROIDX_FAMILIES
        else -> false
    }

internal fun overlaySubstitutionFor(dependency: Dependency): ModuleSubstitution? {
    val group = dependency.group ?: return null
    val version = dependency.version?.takeIf(String::isNotBlank) ?: return null
    val officialGroup =
        when {
            group.startsWith(FORK_COMPOSE_GROUP_PREFIX) ->
                OFFICIAL_COMPOSE_GROUP_PREFIX + group.removePrefix(FORK_COMPOSE_GROUP_PREFIX)
            group == FORK_SKIKO_GROUP -> OFFICIAL_SKIKO_GROUP
            else -> return null
        }
    return ModuleSubstitution(
        officialCoordinate = "$officialGroup:${dependency.name}",
        forkCoordinate = "$group:${dependency.name}:$version",
    )
}

internal fun fullForkSubstitutionsFor(dependency: Dependency): List<ModuleSubstitution> {
    val group = dependency.group ?: return emptyList()
    if (!group.startsWith(FORK_COMPOSE_GROUP_PREFIX)) return emptyList()
    val version = dependency.version?.takeIf(String::isNotBlank) ?: return emptyList()
    val composeFamily = group.removePrefix(FORK_COMPOSE_GROUP_PREFIX)
    val module = dependency.name
    val forkCoordinate = "$group:$module:$version"
    return buildList {
        add(
            ModuleSubstitution(
                officialCoordinate = "$OFFICIAL_COMPOSE_GROUP_PREFIX$composeFamily:$module",
                forkCoordinate = forkCoordinate,
            )
        )
        if (composeFamily in ANDROIDX_COMPOSE_FAMILIES) {
            add(
                ModuleSubstitution(
                    officialCoordinate =
                        "$ANDROIDX_COMPOSE_GROUP_PREFIX$composeFamily:$module-android",
                    forkCoordinate = "$group:$module-android:$version",
                )
            )
        }
    }
}

internal fun androidApplicationConsumerSubstitutionsFor(
    dependency: Dependency
): List<ModuleSubstitution> {
    val group = dependency.group ?: return emptyList()
    if (!group.startsWith(FORK_COMPOSE_GROUP_PREFIX)) return emptyList()
    val version = dependency.version?.takeIf(String::isNotBlank) ?: return emptyList()
    val composeFamily = group.removePrefix(FORK_COMPOSE_GROUP_PREFIX)
    if (composeFamily !in ANDROIDX_COMPOSE_FAMILIES) return emptyList()
    val module = dependency.name
    val forkAndroidCoordinate = "$group:$module-android:$version"
    return listOf(
        ModuleSubstitution(
            officialCoordinate = "$OFFICIAL_COMPOSE_GROUP_PREFIX$composeFamily:$module",
            forkCoordinate = forkAndroidCoordinate,
        ),
        ModuleSubstitution(
            officialCoordinate = "$ANDROIDX_COMPOSE_GROUP_PREFIX$composeFamily:$module-android",
            forkCoordinate = forkAndroidCoordinate,
        ),
        ModuleSubstitution(
            officialCoordinate = "$group:$module",
            forkCoordinate = forkAndroidCoordinate,
        ),
    )
}

internal const val COMMON_MAIN_CONFIGURATION_PREFIX = "commonMain"

internal const val DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX = "desktopNativeMain"

internal const val OFFICIAL_COMPOSE_GROUP_PREFIX = "org.jetbrains.compose."

internal const val OFFICIAL_ANDROIDX_GROUP_PREFIX = "org.jetbrains.androidx."

internal const val ANDROIDX_GROUP_PREFIX = "androidx."

internal const val ANDROIDX_COMPOSE_GROUP_PREFIX = "androidx.compose."

internal const val ANDROIDX_COMPOSE_RUNTIME_GROUP = "androidx.compose.runtime"

internal const val FORK_COMPOSE_GROUP_PREFIX = "dev.brahmkshatriya.compose."

internal const val FORK_ANDROIDX_GROUP_PREFIX = "dev.brahmkshatriya.androidx."

internal const val OFFICIAL_SKIKO_GROUP = "org.jetbrains.skiko"

internal const val FORK_SKIKO_GROUP = "dev.brahmkshatriya.skiko"

private const val UI_UKIT_MODULE = "ui-uikit"

private val ANDROIDX_COMPOSE_FAMILIES =
    setOf("animation", "foundation", "material", "material3", "runtime", "ui")

private val ANDROIDX_COMPOSE_FORK_MODULES =
    setOf(
        "animation",
        "animation-core",
        "animation-graphics",
        "foundation",
        "foundation-layout",
        "material",
        "material-ripple",
        "material3",
        "material3-ripple",
        "runtime",
        "runtime-annotation",
        "runtime-retain",
        "runtime-saveable",
        "ui",
        "ui-backhandler",
        "ui-geometry",
        "ui-graphics",
        "ui-skiko",
        "ui-text",
        "ui-unit",
        "ui-util",
    )

private val COMPOSE_FAMILIES = ANDROIDX_COMPOSE_FAMILIES + setOf("components", "desktop")

internal val CROSS_PLATFORM_COMPOSE_MODULES = setOf("foundation:foundation", "material3:material3")

private val FORK_ANDROIDX_FAMILIES =
    setOf("collection", "lifecycle", "navigation", "navigation3", "navigationevent", "savedstate")
