package dev.brahmkshatriya.compose

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentSelector

internal fun Project.configureDependencySubstitutions() {
    afterEvaluate {
        val fullForkSubstitutions =
            configurations
                .matching { it.name.startsWith(COMMON_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .flatMap(::fullForkSubstitutionsFor)
                .associateBy(ModuleSubstitution::officialCoordinate)
        val androidApplicationConsumerSubstitutions =
            configurations
                .matching { it.name.startsWith(COMMON_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .flatMap(::androidApplicationConsumerSubstitutionsFor)
                .associateBy(ModuleSubstitution::officialCoordinate)
        val nativeOverlaySubstitutions =
            configurations
                .matching { it.name.startsWith(DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .mapNotNull(::overlaySubstitutionFor)
                .associateBy(ModuleSubstitution::officialCoordinate)
        val fullForkComposeVersion =
            configurations
                .matching { it.name.startsWith(COMMON_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .singleComposeForkVersionOrNull()
        val nativeOverlayComposeVersion =
            configurations
                .matching { it.name.startsWith(DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .singleComposeForkVersionOrNull()
        val nativeMetadataSubstitutions =
            nativeOverlayComposeVersion?.let(::nativeMetadataSubstitutionsFor).orEmpty()
        if (
            fullForkSubstitutions.isEmpty() &&
                nativeOverlaySubstitutions.isEmpty() &&
                fullForkComposeVersion == null &&
                nativeOverlayComposeVersion == null
        ) {
            return@afterEvaluate
        }

        configureLocalDependencySubstitutions(
            fullForkSubstitutions = fullForkSubstitutions,
            androidTargetSubstitutions = androidApplicationConsumerSubstitutions,
            nativeOverlaySubstitutions = nativeOverlaySubstitutions,
            fullForkComposeVersion = fullForkComposeVersion,
            nativeOverlayComposeVersion = nativeOverlayComposeVersion,
            nativeMetadataSubstitutions = nativeMetadataSubstitutions,
        )
        if (fullForkSubstitutions.isNotEmpty()) {
            configureProjectConsumers(
                fullForkSubstitutions,
                androidApplicationConsumerSubstitutions,
            )
        }
    }
}

private fun Project.configureLocalDependencySubstitutions(
    fullForkSubstitutions: Map<String, ModuleSubstitution>,
    androidTargetSubstitutions: Map<String, ModuleSubstitution>,
    nativeOverlaySubstitutions: Map<String, ModuleSubstitution>,
    fullForkComposeVersion: String?,
    nativeOverlayComposeVersion: String?,
    nativeMetadataSubstitutions: Map<String, ModuleSubstitution>,
) {
    configurations.configureEach { configuration ->
        val isAndroidConfiguration = configuration.name.isAndroidConfiguration()
        val usesNativeOverlay = configuration.name.usesNativeOverlay()
        val isMetadataTransformation = configuration.name.isMetadataTransformationConfiguration()
        val substitutions =
            when {
                usesNativeOverlay ->
                    fullForkSubstitutions + nativeOverlaySubstitutions + nativeMetadataSubstitutions
                isMetadataTransformation ->
                    fullForkSubstitutions.filterValues {
                        !it.targetsRedirectedCommonMetadataModule()
                    }
                isAndroidConfiguration -> fullForkSubstitutions + androidTargetSubstitutions
                else -> fullForkSubstitutions
            }
        val composeForkVersion =
            if (usesNativeOverlay) {
                nativeOverlayComposeVersion ?: fullForkComposeVersion
            } else {
                fullForkComposeVersion
            }
        if (substitutions.isEmpty() && composeForkVersion == null) {
            return@configureEach
        }

        val selectedForkVersion =
            when {
                usesNativeOverlay -> nativeOverlayComposeVersion ?: fullForkComposeVersion
                else -> fullForkComposeVersion ?: nativeOverlayComposeVersion
            }
        if (selectedForkVersion != null) {
            configuration.resolutionStrategy.eachDependency { details ->
                if (
                    details.requested.group.startsWith(FORK_COMPOSE_GROUP_PREFIX) &&
                        (usesNativeOverlay ||
                            isCrossPlatformForkModule(
                                details.requested.group,
                                details.requested.name,
                            ))
                ) {
                    details.useVersion(selectedForkVersion)
                    details.because("Keep Compose Native fork modules on one release")
                }
            }
        }

        configuration.resolutionStrategy.dependencySubstitution { rules ->
            rules.all { details ->
                val selector = details.requested as? ModuleComponentSelector ?: return@all
                if (
                    isMetadataTransformation &&
                        !usesNativeOverlay &&
                        selector.isRedirectedCommonMetadataModule()
                ) {
                    selector.officialMetadataCoordinateOrNull()?.let { officialCoordinate ->
                        details.useTarget(officialCoordinate)
                        return@all
                    }
                }
                val redirectImplementation =
                    when {
                        configuration.name.usesRedirectedNativeTarget() ->
                            selector.nativeRedirectImplementationCoordinateOrNull()
                        configuration.name.usesRedirectedWebTarget() ->
                            selector.webRedirectImplementationCoordinateOrNull()
                        else -> null
                    }
                if (redirectImplementation != null) {
                    // Some fork Native publications are intentionally empty redirect shims whose
                    // real implementation lives in the upstream AndroidX/JetBrains artifact. The
                    // same redirect model is used by JS/Wasm for Runtime and AndroidX families.
                    // Normalize stale JetBrains/AndroidX coordinates to the redirect version, and
                    // do not substitute that dependency back to the fork or the graph loops on the
                    // empty shim and the platform compiler never sees the real target KLIB.
                    details.useTarget(redirectImplementation)
                    return@all
                }
                val substitution = substitutions["${selector.group}:${selector.module}"]
                if (
                    substitution != null &&
                        (!selector.group.startsWith(ANDROIDX_COMPOSE_GROUP_PREFIX) ||
                            isAndroidConfiguration)
                ) {
                    details.useTarget(substitution.forkCoordinate)
                    return@all
                }
                val composeTarget =
                    composeForkVersion?.let {
                        composeForkCoordinateFor(
                            selector.group,
                            selector.module,
                            it,
                            useDesktopNativeFork = usesNativeOverlay,
                            includeJetBrainsAndroidx = usesNativeOverlay,
                            includeAndroidx = usesNativeOverlay,
                        )
                    } ?: return@all
                details.useTarget(composeTarget)
            }
        }
    }
}

private fun Project.configureProjectConsumers(
    fullForkSubstitutions: Map<String, ModuleSubstitution>,
    androidApplicationConsumerSubstitutions: Map<String, ModuleSubstitution>,
) {
    rootProject.allprojects { consumer ->
        if (consumer == this) return@allprojects
        val configureConsumer = {
            if (consumer.directlyDependsOn(this)) {
                consumer.configureNonDesktopForkPlatformMetadata()

                val isAndroidApplication =
                    consumer.pluginManager.hasPlugin(ANDROID_APPLICATION_PLUGIN_ID)
                val isNonDesktopNativeConsumer = consumer.isNonDesktopNativeConsumer()
                val substitutions =
                    when {
                        isAndroidApplication -> androidApplicationConsumerSubstitutions
                        else -> fullForkSubstitutions
                    }
                if (isNonDesktopNativeConsumer) {
                    consumer.configurations.configureEach { configuration ->
                        configuration.configureOfficialForkSupportRouting(useNativeRedirects = true)
                    }
                }
                consumer.configurations.configureEach { configuration ->
                    when {
                        configuration.name.usesRedirectedWebTarget() ->
                            configuration.configureOfficialForkSupportRouting(
                                useNativeRedirects = false,
                                useWebRedirects = true,
                            )
                        configuration.name.usesRedirectedJvmTarget() ->
                            configuration.configureOfficialForkSupportRouting(
                                useNativeRedirects = false,
                                useJvmRedirects = true,
                            )
                    }
                }
                consumer.configureConsumerSubstitutions(substitutions)
            }
        }
        if (consumer.state.executed) configureConsumer()
        else consumer.afterEvaluate { configureConsumer() }
    }
}

internal fun Project.isNonDesktopNativeConsumer(): Boolean =
    configurations.any { configuration -> configuration.name.usesRedirectedNativeTarget() } &&
        configurations.none { configuration -> configuration.name.usesNativeOverlay() }

internal fun Project.directlyDependsOn(producer: Project): Boolean =
    configurations.any { configuration ->
        configuration.dependencies.withType(ProjectDependency::class.java).any { dependency ->
            dependency.path == producer.path
        }
    }

private fun Project.configureConsumerSubstitutions(substitutions: Map<String, ModuleSubstitution>) {
    configurations.configureEach { configuration ->
        configuration.resolutionStrategy.dependencySubstitution { rules ->
            rules.all { details ->
                val selector = details.requested as? ModuleComponentSelector ?: return@all
                val redirectImplementation =
                    when {
                        configuration.name.usesRedirectedNativeTarget() ->
                            selector.nativeRedirectImplementationCoordinateOrNull()
                        configuration.name.usesRedirectedWebTarget() ->
                            selector.webRedirectImplementationCoordinateOrNull()
                        else -> null
                    }
                if (redirectImplementation != null) {
                    details.useTarget(redirectImplementation)
                    return@all
                }
                val substitution =
                    substitutions["${selector.group}:${selector.module}"] ?: return@all
                details.useTarget(substitution.forkCoordinate)
            }
        }
    }
}

private fun Iterable<Dependency>.singleComposeForkVersionOrNull(): String? {
    val versions =
        mapNotNull { dependency ->
                dependency.version?.takeIf(String::isNotBlank)?.takeIf {
                    dependency.group?.startsWith(FORK_COMPOSE_GROUP_PREFIX) == true
                }
            }
            .distinct()
    return versions.singleOrNull()
}
