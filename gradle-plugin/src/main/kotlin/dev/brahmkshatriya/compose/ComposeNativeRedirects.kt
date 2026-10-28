package dev.brahmkshatriya.compose

import org.gradle.api.artifacts.component.ModuleComponentSelector

internal fun ModuleComponentSelector.isNativeRedirectImplementation(): Boolean =
    isNativeRedirectImplementation(group, module)

internal fun ModuleComponentSelector.nativeRedirectImplementationCoordinateOrNull(): String? =
    nativeRedirectImplementationCoordinateOrNull(group, module)

internal fun ModuleComponentSelector.webRedirectImplementationCoordinateOrNull(): String? =
    webRedirectImplementationCoordinateOrNull(group, module)

internal fun nativeRedirectImplementationCoordinateOrNull(group: String, module: String): String? {
    val rootModule = module.withoutRedirectPlatformSuffix()
    val family = redirectImplementationFamilyOrNull(group) ?: return null
    if (!isNativeRedirectImplementation(group, rootModule)) return null
    return redirectImplementationCoordinate(family, module)
}

internal fun webRedirectImplementationCoordinateOrNull(group: String, module: String): String? {
    val rootModule = module.withoutRedirectPlatformSuffix()
    val family = redirectImplementationFamilyOrNull(group) ?: return null
    if (!isWebRedirectImplementation(group, rootModule)) return null
    return redirectImplementationCoordinate(family, module)
}

private fun redirectImplementationCoordinate(family: String, module: String): String? {
    val version = ComposeNativeUpstreamVersions.androidx(family) ?: return null
    val upstreamGroup =
        if (family == "compose") ANDROIDX_COMPOSE_RUNTIME_GROUP else "androidx.$family"
    return "$upstreamGroup:$module:$version"
}

private fun redirectImplementationFamilyOrNull(group: String): String? =
    when {
        group in
            setOf(
                ANDROIDX_COMPOSE_RUNTIME_GROUP,
                "${OFFICIAL_COMPOSE_GROUP_PREFIX}runtime",
                "${FORK_COMPOSE_GROUP_PREFIX}runtime",
            ) -> "compose"
        group in setOf("androidx.collection", "org.jetbrains.androidx.collection") -> "collection"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}collection" -> "collection"
        group in setOf("androidx.lifecycle", "org.jetbrains.androidx.lifecycle") -> "lifecycle"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}lifecycle" -> "lifecycle"
        group in setOf("androidx.navigation", "org.jetbrains.androidx.navigation") -> "navigation"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}navigation" -> "navigation"
        group in setOf("androidx.navigation3", "org.jetbrains.androidx.navigation3") ->
            "navigation3"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}navigation3" -> "navigation3"
        group in setOf("androidx.navigationevent", "org.jetbrains.androidx.navigationevent") ->
            "navigationevent"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}navigationevent" -> "navigationevent"
        group in setOf("androidx.savedstate", "org.jetbrains.androidx.savedstate") -> "savedstate"
        group == "${FORK_ANDROIDX_GROUP_PREFIX}savedstate" -> "savedstate"
        else -> null
    }

internal fun isNativeRedirectImplementation(group: String, module: String): Boolean {
    val rootModule = module.withoutRedirectPlatformSuffix()
    return when (group) {
        ANDROIDX_COMPOSE_RUNTIME_GROUP,
        "${OFFICIAL_COMPOSE_GROUP_PREFIX}runtime",
        "${FORK_COMPOSE_GROUP_PREFIX}runtime" -> rootModule in REDIRECTED_NATIVE_RUNTIME_MODULES
        "androidx.collection",
        "org.jetbrains.androidx.collection",
        "${FORK_ANDROIDX_GROUP_PREFIX}collection" -> rootModule == "collection"
        "androidx.lifecycle",
        "org.jetbrains.androidx.lifecycle",
        "${FORK_ANDROIDX_GROUP_PREFIX}lifecycle" -> true
        "androidx.navigation",
        "org.jetbrains.androidx.navigation",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigation" ->
            rootModule in REDIRECTED_NATIVE_NAVIGATION_MODULES
        "androidx.navigation3",
        "org.jetbrains.androidx.navigation3",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigation3" -> rootModule == "navigation3-runtime"
        "androidx.navigationevent",
        "org.jetbrains.androidx.navigationevent",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigationevent" ->
            rootModule in REDIRECTED_NATIVE_NAVIGATION_EVENT_MODULES
        "androidx.savedstate",
        "org.jetbrains.androidx.savedstate",
        "${FORK_ANDROIDX_GROUP_PREFIX}savedstate" ->
            rootModule in REDIRECTED_NATIVE_SAVEDSTATE_MODULES
        else -> false
    }
}

internal fun isWebRedirectImplementation(group: String, module: String): Boolean {
    val rootModule = module.withoutRedirectPlatformSuffix()
    return when (group) {
        ANDROIDX_COMPOSE_RUNTIME_GROUP,
        "${OFFICIAL_COMPOSE_GROUP_PREFIX}runtime",
        "${FORK_COMPOSE_GROUP_PREFIX}runtime" -> rootModule in REDIRECTED_WEB_RUNTIME_MODULES
        "androidx.lifecycle",
        "org.jetbrains.androidx.lifecycle",
        "${FORK_ANDROIDX_GROUP_PREFIX}lifecycle" -> true
        "androidx.navigation",
        "org.jetbrains.androidx.navigation",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigation" ->
            rootModule in REDIRECTED_NATIVE_NAVIGATION_MODULES
        "androidx.navigation3",
        "org.jetbrains.androidx.navigation3",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigation3" -> rootModule == "navigation3-runtime"
        "androidx.navigationevent",
        "org.jetbrains.androidx.navigationevent",
        "${FORK_ANDROIDX_GROUP_PREFIX}navigationevent" ->
            rootModule in REDIRECTED_NATIVE_NAVIGATION_EVENT_MODULES
        "androidx.savedstate",
        "org.jetbrains.androidx.savedstate",
        "${FORK_ANDROIDX_GROUP_PREFIX}savedstate" ->
            rootModule in REDIRECTED_NATIVE_SAVEDSTATE_MODULES
        else -> false
    }
}

internal fun String.withoutRedirectPlatformSuffix(): String {
    val suffix = REDIRECT_PLATFORM_MODULE_SUFFIXES.firstOrNull { endsWith(it) } ?: return this
    return removeSuffix(suffix)
}

internal fun nativeMetadataSubstitutionsFor(version: String): Map<String, ModuleSubstitution> =
    NATIVE_INTERNAL_COMPOSE_MODULES.associate { (family, module) ->
        val officialCoordinate = "$OFFICIAL_COMPOSE_GROUP_PREFIX$family:$module"
        officialCoordinate to
            ModuleSubstitution(
                officialCoordinate = officialCoordinate,
                forkCoordinate = "$FORK_COMPOSE_GROUP_PREFIX$family:$module:$version",
            )
    }

private val REDIRECTED_NATIVE_NAVIGATION_MODULES =
    setOf("navigation-common", "navigation-runtime", "navigation-testing")

private val REDIRECTED_NATIVE_NAVIGATION_EVENT_MODULES =
    setOf("navigationevent", "navigationevent-compose")

private val REDIRECTED_NATIVE_SAVEDSTATE_MODULES = setOf("savedstate", "savedstate-compose")

private val REDIRECT_PLATFORM_MODULE_SUFFIXES =
    listOf(
        "-wasm-js",
        "-js",
        "-linuxx64",
        "-linuxarm64",
        "-mingwx64",
        "-macosarm64",
        "-iosarm64",
        "-iossimulatorarm64",
        "-iosx64",
        "-watchosarm64",
        "-watchosdevicearm64",
        "-watchossimulatorarm64",
        "-watchosx64",
        "-tvosarm64",
        "-tvossimulatorarm64",
        "-tvosx64",
    )

private val NATIVE_INTERNAL_COMPOSE_MODULES = setOf("ui" to "ui-skiko")

internal val REDIRECTED_COMMON_METADATA_RUNTIME_MODULES =
    setOf("runtime", "runtime-annotation", "runtime-saveable")

private val REDIRECTED_NATIVE_RUNTIME_MODULES =
    REDIRECTED_COMMON_METADATA_RUNTIME_MODULES + "runtime-retain"

private val REDIRECTED_WEB_RUNTIME_MODULES = REDIRECTED_NATIVE_RUNTIME_MODULES

internal val REDIRECTED_COMMON_METADATA_ANDROIDX_FAMILIES =
    setOf("lifecycle", "navigation", "navigation3", "navigationevent", "savedstate")
