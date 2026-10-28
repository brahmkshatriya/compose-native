package dev.brahmkshatriya.compose

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentSelector

internal fun Project.configureNonDesktopForkPlatformMetadata() {
    val extraProperties = extensions.extraProperties
    if (extraProperties.has(NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER)) return
    extraProperties.set(NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER, true)

    dependencies.components.all { details ->
        val routing =
            nonDesktopForkPlatformRoutingOrNull(group = details.id.group, module = details.id.name)
                ?: return@all

        details.allVariants { variant ->
            variant.withDependencies { dependencies ->
                val replacements =
                    dependencies.mapNotNull { dependency ->
                        val coordinate =
                            nonDesktopForkSupportCoordinateOrNull(
                                group = dependency.group,
                                module = dependency.name,
                                useNativeRedirects =
                                    routing == NonDesktopForkPlatformRouting.Native,
                                useWebRedirects = routing == NonDesktopForkPlatformRouting.Web,
                                useJvmRedirects = routing == NonDesktopForkPlatformRouting.Jvm,
                            )
                        coordinate?.let { dependency to it }
                    }
                replacements.forEach { (dependency, coordinate) ->
                    dependencies.remove(dependency)
                    dependencies.add(coordinate)
                }
            }
        }
    }
}

internal enum class NonDesktopForkPlatformRouting {
    Android,
    Native,
    Web,
    Jvm,
}

internal fun nonDesktopForkPlatformRoutingOrNull(
    group: String,
    module: String,
): NonDesktopForkPlatformRouting? {
    val family =
        when (group) {
            "${FORK_COMPOSE_GROUP_PREFIX}foundation" -> "foundation"
            "${FORK_COMPOSE_GROUP_PREFIX}material3" -> "material3"
            else -> return null
        }
    if (!module.startsWith("$family-")) return null
    val suffix = module.removePrefix("$family-").lowercase()
    return when {
        suffix == "android" -> NonDesktopForkPlatformRouting.Android
        suffix == "desktop" || suffix == "jvm" -> NonDesktopForkPlatformRouting.Jvm
        suffix == "js" || suffix == "wasm-js" -> NonDesktopForkPlatformRouting.Web
        suffix.startsWith("ios") || suffix.startsWith("watchos") || suffix.startsWith("tvos") ->
            NonDesktopForkPlatformRouting.Native
        else -> null
    }
}

internal fun Project.configureNonDesktopForkSupportRouting() {
    configurations.configureEach { configuration ->
        if (configuration.name.usesNativeOverlay()) return@configureEach

        val isNonDesktopNative = configuration.name.usesRedirectedNativeTarget()
        val isWeb = configuration.name.usesRedirectedWebTarget()
        val isAndroid = configuration.name.isAndroidConfiguration()
        val isJvm = configuration.name.usesRedirectedJvmTarget()

        configuration.configureOfficialForkSupportRouting(
            useNativeRedirects = isNonDesktopNative,
            useWebRedirects = isWeb,
            useJvmRedirects = isJvm,
            useMetadataRedirects = !isNonDesktopNative && !isWeb && !isAndroid && !isJvm,
        )
    }
}

internal fun Project.configureAndroidApplicationForkSupportRouting() {
    rootProject.allprojects { consumer ->
        consumer.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN_ID) {
            val extraProperties = consumer.extensions.extraProperties
            if (!extraProperties.has(ANDROID_SUPPORT_ROUTING_MARKER)) {
                extraProperties.set(ANDROID_SUPPORT_ROUTING_MARKER, true)
                consumer.configurations.configureEach { configuration ->
                    configuration.configureOfficialForkSupportRouting(useNativeRedirects = false)
                }
            }
        }
    }
}

internal fun org.gradle.api.artifacts.Configuration.configureOfficialForkSupportRouting(
    useNativeRedirects: Boolean,
    useWebRedirects: Boolean = false,
    useJvmRedirects: Boolean = false,
    useMetadataRedirects: Boolean = false,
) {
    resolutionStrategy.eachDependency { details ->
        val coordinate =
            nonDesktopForkSupportCoordinateOrNull(
                group = details.requested.group,
                module = details.requested.name,
                useNativeRedirects = useNativeRedirects,
                useWebRedirects = useWebRedirects,
                useJvmRedirects = useJvmRedirects,
                useMetadataRedirects = useMetadataRedirects,
            )
        if (coordinate != null) {
            details.useTarget(coordinate)
            details.because("Keep non-desktop Compose support dependencies on official coordinates")
        }
    }
    resolutionStrategy.dependencySubstitution { rules ->
        rules.all { details ->
            val selector = details.requested as? ModuleComponentSelector ?: return@all
            val coordinate =
                nonDesktopForkSupportCoordinateOrNull(
                    group = selector.group,
                    module = selector.module,
                    useNativeRedirects = useNativeRedirects,
                    useWebRedirects = useWebRedirects,
                    useJvmRedirects = useJvmRedirects,
                    useMetadataRedirects = useMetadataRedirects,
                )
            if (coordinate != null) details.useTarget(coordinate)
        }
    }
}

internal fun nonDesktopForkSupportCoordinateOrNull(
    group: String,
    module: String,
    useNativeRedirects: Boolean,
    useWebRedirects: Boolean = false,
    useJvmRedirects: Boolean = false,
    useMetadataRedirects: Boolean = false,
): String? {
    if (isCrossPlatformForkModule(group, module)) return null
    if (useNativeRedirects) {
        nativeRedirectImplementationCoordinateOrNull(group, module)?.let {
            return it
        }
        return officialMetadataCoordinateOrNull(group, module)
    }
    if (useWebRedirects) {
        webRedirectImplementationCoordinateOrNull(group, module)?.let {
            return it
        }
        return officialMetadataCoordinateOrNull(group, module)
    }
    if (useJvmRedirects || useMetadataRedirects) {
        nativeRedirectImplementationCoordinateOrNull(group, module)?.let {
            return it
        }
        return officialMetadataCoordinateOrNull(group, module)
    }
    return androidOfficialCoordinateOrNull(group, module)
}

internal fun isCrossPlatformForkModule(group: String, module: String): Boolean {
    if (!group.startsWith(FORK_COMPOSE_GROUP_PREFIX)) return false
    val family = group.removePrefix(FORK_COMPOSE_GROUP_PREFIX)
    val rootModule = module.removeSuffix("-android").withoutRedirectPlatformSuffix()
    return "$family:$rootModule" in CROSS_PLATFORM_COMPOSE_MODULES
}

internal const val ANDROID_APPLICATION_PLUGIN_ID = "com.android.application"

private const val ANDROID_SUPPORT_ROUTING_MARKER =
    "dev.brahmkshatriya.compose.androidSupportRouting"

private const val NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER =
    "dev.brahmkshatriya.compose.nonDesktopForkPlatformMetadataRouting"
