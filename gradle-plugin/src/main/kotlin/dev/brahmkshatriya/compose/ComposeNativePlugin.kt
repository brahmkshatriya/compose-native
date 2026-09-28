package dev.brahmkshatriya.compose

import java.io.Serializable
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

class ComposeNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.enableRequestedCoordinateMatching()
        project.addDesktopNativeSourceSets()
        project.createComposeNativeApplicationExtension()
        project.configureDesktopNativeApplicationConventions()
        project.configureMetadataCompilation()
        project.configureIdeDependencyResolution()
        project.configureSkikoCapabilityResolution()
        project.configureNonDesktopForkPlatformMetadata()
        project.configureNonDesktopForkSupportRouting()
        project.configureAndroidApplicationForkSupportRouting()
        project.configureDependencySubstitutions()
        project.configureNativeSkikoPublicationMetadata()
    }
}

private fun Project.configureNonDesktopForkPlatformMetadata() {
    val extraProperties = extensions.extraProperties
    if (extraProperties.has(NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER)) return
    extraProperties.set(NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER, true)

    dependencies.components.all { details ->
        val routing =
            nonDesktopForkPlatformRoutingOrNull(
                group = details.id.group,
                module = details.id.name,
            ) ?: return@all

        details.allVariants { variant ->
            variant.withDependencies { dependencies ->
                val replacements =
                    dependencies.mapNotNull { dependency ->
                        val coordinate =
                            nonDesktopForkSupportCoordinateOrNull(
                                group = dependency.group,
                                module = dependency.name,
                                useNativeRedirects = routing == NonDesktopForkPlatformRouting.Native,
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
        suffix.startsWith("ios") ||
            suffix.startsWith("watchos") ||
            suffix.startsWith("tvos") -> NonDesktopForkPlatformRouting.Native
        else -> null
    }
}

private fun Project.configureNonDesktopForkSupportRouting() {
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

private fun Project.configureAndroidApplicationForkSupportRouting() {
    rootProject.allprojects { consumer ->
        consumer.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN_ID) {
            val extraProperties = consumer.extensions.extraProperties
            if (!extraProperties.has(ANDROID_SUPPORT_ROUTING_MARKER)) {
                extraProperties.set(ANDROID_SUPPORT_ROUTING_MARKER, true)
                consumer.configurations.configureEach { configuration ->
                    configuration.configureOfficialForkSupportRouting(
                        useNativeRedirects = false,
                    )
                }
            }
        }
    }
}

private fun org.gradle.api.artifacts.Configuration.configureOfficialForkSupportRouting(
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
        nativeRedirectImplementationCoordinateOrNull(group, module)?.let { return it }
        return officialMetadataCoordinateOrNull(group, module)
    }
    if (useWebRedirects) {
        webRedirectImplementationCoordinateOrNull(group, module)?.let { return it }
        return officialMetadataCoordinateOrNull(group, module)
    }
    if (useJvmRedirects || useMetadataRedirects) {
        nativeRedirectImplementationCoordinateOrNull(group, module)?.let { return it }
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

private fun Project.enableRequestedCoordinateMatching() {
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

private fun Project.configureSkikoCapabilityResolution() {
    dependencies.components.all { details ->
        if (details.id.group != FORK_SKIKO_GROUP) return@all
        details.allVariants { variant ->
            variant.withCapabilities { capabilities ->
                capabilities.addCapability(
                    OFFICIAL_SKIKO_GROUP,
                    details.id.name,
                    details.id.version,
                )
            }
        }
    }
    configurations.configureEach { configuration ->
        configuration.resolutionStrategy.capabilitiesResolution.all { details ->
            if (
                details.capability.group != OFFICIAL_SKIKO_GROUP ||
                    !details.capability.name.startsWith("skiko")
            ) {
                return@all
            }
            val selectedGroup =
                if (configuration.name.usesNativeOverlay()) FORK_SKIKO_GROUP
                else OFFICIAL_SKIKO_GROUP
            val selectedCandidate =
                details.candidates.firstOrNull { candidate ->
                    (candidate.id as? ModuleComponentIdentifier)?.group == selectedGroup
                }
            if (selectedCandidate != null) details.select(selectedCandidate)
        }
    }
}

private fun Project.configureDependencySubstitutions() {
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
        if (
            substitutions.isEmpty() &&
                composeForkVersion == null
        ) {
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

private fun Project.configureNativeSkikoPublicationMetadata() {
    afterEvaluate {
        val nativeSkikoVersion = nativeSkikoPublicationVersion()
        tasks.withType(GenerateModuleMetadata::class.java).configureEach { task ->
            if (!task.name.isDesktopNativePublicationMetadataTask()) return@configureEach
            task.doLast(RewriteNativeSkikoPublicationMetadataAction(nativeSkikoVersion))
        }
    }
}

internal fun Project.nativeSkikoPublicationVersion(): String {
    val declaredVersions =
        configurations
            .matching { configuration ->
                configuration.name.startsWith(
                    DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX,
                    ignoreCase = true,
                )
            }
            .flatMap { it.dependencies }
            .filter { dependency ->
                dependency.group == FORK_SKIKO_GROUP && dependency.name == SKIKO_MODULE
            }
            .mapNotNull { it.version?.takeIf(String::isNotBlank) }
            .distinct()
    if (declaredVersions.size > 1) {
        throw GradleException(
            "Compose Native found multiple desktop Native Skiko fork versions: " +
                declaredVersions.joinToString()
        )
    }
    return declaredVersions.singleOrNull() ?: DEFAULT_NATIVE_SKIKO_VERSION
}

private class RewriteNativeSkikoPublicationMetadataAction(private val nativeSkikoVersion: String) :
    Action<Task>, Serializable {
    override fun execute(task: Task) {
        val metadataTask = task as GenerateModuleMetadata
        val metadataFile = metadataTask.outputFile.get().asFile
        val original = metadataFile.readText()
        val rewritten = rewriteNativeSkikoPublicationMetadata(original, nativeSkikoVersion)
        if (rewritten != original) metadataFile.writeText(rewritten)
    }
}

internal fun rewriteNativeSkikoPublicationMetadata(
    metadata: String,
    nativeSkikoVersion: String,
): String =
    OFFICIAL_SKIKO_DEPENDENCY_REGEX.replace(metadata) { match ->
        buildString {
            append(match.groupValues[1])
            append(FORK_SKIKO_GROUP)
            append(match.groupValues[2])
            append(nativeSkikoVersion)
            append(match.groupValues[3])
        }
    }

internal fun String.isDesktopNativePublicationMetadataTask(): Boolean =
    DESKTOP_NATIVE_PUBLICATION_TARGET_NAMES.any { targetName ->
        contains(targetName, ignoreCase = true)
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
                        configuration.configureOfficialForkSupportRouting(
                            useNativeRedirects = true,
                        )
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

private fun ModuleSubstitution.targetsRedirectedCommonMetadataModule(): Boolean {
    val group = forkCoordinate.substringBefore(':')
    val module = forkCoordinate.substringAfter(':').substringBefore(':')
    return isRedirectedCommonMetadataModule(group, module)
}

private fun ModuleComponentSelector.isRedirectedCommonMetadataModule(): Boolean =
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

internal fun String.isDesktopNativeConfiguration(): Boolean {
    val normalized = lowercase()
    return DESKTOP_NATIVE_CONFIGURATION_MARKERS.any(normalized::contains)
}

internal fun String.isSharedNativeMetadataConfiguration(): Boolean =
    SHARED_NATIVE_MAIN_CONFIGURATION_PREFIXES.any { startsWith(it, ignoreCase = true) } &&
        contains("metadata", ignoreCase = true)

internal fun String.isMetadataTransformationConfiguration(): Boolean =
    endsWith(RESOLVABLE_METADATA_CONFIGURATION_SUFFIX, ignoreCase = true)

internal fun String.usesNativeOverlay(): Boolean =
    isDesktopNativeConfiguration() || isSharedNativeMetadataConfiguration()

internal fun String.usesRedirectedNativeTarget(): Boolean {
    val normalized = lowercase()
    return REDIRECTED_NATIVE_CONFIGURATION_MARKERS.any(normalized::contains)
}

internal fun String.usesRedirectedWebTarget(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("wasmjs") || normalized.startsWith("js")
}

internal fun String.usesRedirectedJvmTarget(): Boolean =
    startsWith("jvm", ignoreCase = true)

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
    val upstreamGroup = if (family == "compose") ANDROIDX_COMPOSE_RUNTIME_GROUP else "androidx.$family"
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
        group in setOf("androidx.navigation3", "org.jetbrains.androidx.navigation3") -> "navigation3"
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
        "${FORK_COMPOSE_GROUP_PREFIX}runtime" ->
            rootModule in REDIRECTED_NATIVE_RUNTIME_MODULES
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
        "${FORK_ANDROIDX_GROUP_PREFIX}navigation3" ->
            rootModule == "navigation3-runtime"
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
        "${FORK_ANDROIDX_GROUP_PREFIX}savedstate" -> rootModule in REDIRECTED_NATIVE_SAVEDSTATE_MODULES
        else -> false
    }
}

private fun String.withoutRedirectPlatformSuffix(): String {
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

private fun String.isAndroidConfiguration(): Boolean = contains("android", ignoreCase = true)

internal fun Project.configureDesktopNativeExecutable(executableSpec: DesktopNativeExecutable) {
    executableSpec.requiredEntryPoint()
    val kotlin = extensions.getByName("kotlin")
    @Suppress("UNCHECKED_CAST")
    val targets =
        kotlin.javaClass.methods
            .single { it.name == "getTargets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    val hostTarget =
        hostDesktopNativeTarget(System.getProperty("os.name"), System.getProperty("os.arch"))
    DESKTOP_NATIVE_TARGET_SOURCE_SETS.forEach { (nativeTarget, conventionalTargetName) ->
        val isHostTarget = nativeTarget == hostTarget?.nativeTarget
        val target = targets.getByName(conventionalTargetName)
        target.configureNativeExecutable(
            executableSpec = executableSpec,
            nativeTarget = nativeTarget,
            buildTypes =
                if (isHostTarget) {
                    setOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE)
                } else {
                    setOf(NativeBuildType.RELEASE)
                },
        )
    }
    if (hostTarget != null) configureHostDesktopRunAliases(hostTarget)
}

internal fun Project.createDesktopNativeTargets() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.forEach { targetName ->
            kotlin.javaClass.methods
                .single { it.name == targetName && it.parameterCount == 0 }
                .invoke(kotlin)
        }
    }
}

private fun Project.configureHostDesktopRunAliases(hostTarget: HostDesktopNativeTarget) {
    afterEvaluate {
        listOf("Debug", "Release").forEach { buildType ->
            val sourceTaskName = "run${buildType}Executable${hostTarget.concreteTaskSuffix}"
            val sourceTask = tasks.findByName(sourceTaskName) as? Exec ?: return@forEach
            val aliasTaskName = "run${buildType}Executable$HOST_DESKTOP_TARGET_NAME_CAPITALIZED"
            if (tasks.findByName(aliasTaskName) == null) {
                tasks.register(aliasTaskName, Exec::class.java) { alias ->
                    alias.group = sourceTask.group
                    alias.description =
                        "Executes Kotlin/Native ${buildType.lowercase()} executable for the current desktop host."
                    alias.enabled = sourceTask.enabled
                    alias.commandLine(sourceTask.commandLine)
                    alias.workingDir = sourceTask.workingDir
                    alias.environment(sourceTask.environment)
                    alias.dependsOn(sourceTask.taskDependencies.getDependencies(sourceTask))
                }
            }
        }
    }
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    nativeTarget: String,
    buildTypes: Set<NativeBuildType>,
) {
    val binaries =
        javaClass.methods
            .single {
                it.name == "getBinaries" &&
                    it.parameterCount == 0 &&
                    it.returnType.name == KOTLIN_NATIVE_BINARY_CONTAINER_CLASS
            }
            .invoke(this)
    val existingExecutables =
        (binaries as Iterable<*>).filterNotNull().filter { binary ->
            binary.javaClass.methods.any { it.name == "setEntryPoint" && it.parameterCount == 1 }
        }
    val configuredBuildTypes =
        existingExecutables.mapNotNull { binary ->
            val buildType =
                binary.javaClass.methods
                    .firstOrNull { it.name == "getBuildType" && it.parameterCount == 0 }
                    ?.invoke(binary) as? NativeBuildType
            if (buildType in buildTypes) {
                binary.configureNativeExecutable(executableSpec, nativeTarget)
                buildType
            } else {
                null
            }
        }
    val missingBuildTypes = buildTypes - configuredBuildTypes.toSet()
    if (missingBuildTypes.isEmpty()) return

    val executable =
        binaries.javaClass.methods.single {
            it.name == "executable" &&
                it.parameterCount == 2 &&
                Collection::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Action::class.java
        }
    executable.invoke(
        binaries,
        missingBuildTypes,
        Action<Any> { binary -> binary.configureNativeExecutable(executableSpec, nativeTarget) },
    )
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    nativeTarget: String,
) {
    javaClass.methods
        .single {
            it.name == "setEntryPoint" &&
                it.parameterCount == 1 &&
                it.parameterTypes.single() == String::class.java
        }
        .invoke(this, executableSpec.requiredEntryPoint())
    val linkerOptions =
        executableSpec.linkerOptions +
            if (nativeTarget.startsWith("linux_")) DEFAULT_LINUX_LINKER_OPTIONS else emptyList()
    if (linkerOptions.isNotEmpty()) {
        javaClass.methods
            .single {
                it.name == "linkerOpts" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single() == Iterable::class.java
            }
            .invoke(this, linkerOptions)
    }
}

private fun Project.addDesktopNativeSourceSets() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        (kotlin as ExtensionAware).extensions.add("desktopNative", DesktopNativeTargets(this))
        @Suppress("UNCHECKED_CAST")
        val sourceSets =
            kotlin.javaClass.methods
                .single { it.name == "getSourceSets" && it.parameterCount == 0 }
                .invoke(kotlin) as NamedDomainObjectContainer<Any>
        val desktopNativeMain = sourceSets.maybeCreate("desktopNativeMain")
        val desktopNativeTest = sourceSets.maybeCreate("desktopNativeTest")
        val linuxWindowsMain = sourceSets.maybeCreate("linuxWindowsMain")
        val linuxWindowsTest = sourceSets.maybeCreate("linuxWindowsTest")

        desktopNativeMain.dependsOnSourceSet(sourceSets.getByName("commonMain"))
        desktopNativeTest.dependsOnSourceSet(sourceSets.getByName("commonTest"))
        linuxWindowsMain.dependsOnSourceSet(desktopNativeMain)
        linuxWindowsTest.dependsOnSourceSet(desktopNativeTest)

        afterEvaluate {
            LINUX_WINDOWS_SOURCE_SET_NAMES.forEach { sourceSetPrefix ->
                sourceSets
                    .findByName("${sourceSetPrefix}Main")
                    ?.dependsOnSourceSet(linuxWindowsMain)
                sourceSets
                    .findByName("${sourceSetPrefix}Test")
                    ?.dependsOnSourceSet(linuxWindowsTest)
            }
            MACOS_SOURCE_SET_NAMES.forEach { sourceSetPrefix ->
                sourceSets
                    .findByName("${sourceSetPrefix}Main")
                    ?.dependsOnSourceSet(desktopNativeMain)
                sourceSets
                    .findByName("${sourceSetPrefix}Test")
                    ?.dependsOnSourceSet(desktopNativeTest)
            }
            // Compile shared Skia implementations in each concrete target, as with explicit srcDir calls.
            val skiaSources = layout.projectDirectory.dir("src/skiaTargetMain/kotlin").asFile
            SKIA_TARGET_MAIN_SOURCE_SET_NAMES.forEach { sourceSetName ->
                sourceSets.findByName(sourceSetName)?.addKotlinSourceDirectory(skiaSources)
            }
        }
    }
}

private fun Any.addKotlinSourceDirectory(directory: java.io.File) {
    val kotlinSources =
        javaClass.methods
            .single { it.name == "getKotlin" && it.parameterCount == 0 }
            .invoke(this)
    kotlinSources.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
        .invoke(kotlinSources, directory)
}

private fun Any.dependsOnSourceSet(sourceSet: Any) {
    javaClass.methods
        .single { it.name == "dependsOn" && it.parameterCount == 1 }
        .invoke(this, sourceSet)
}

private const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val ANDROID_APPLICATION_PLUGIN_ID = "com.android.application"
private const val ANDROID_SUPPORT_ROUTING_MARKER =
    "dev.brahmkshatriya.compose.androidSupportRouting"
private const val NON_DESKTOP_FORK_PLATFORM_METADATA_ROUTING_MARKER =
    "dev.brahmkshatriya.compose.nonDesktopForkPlatformMetadataRouting"
private const val KMP_MATCH_REQUESTED_COORDINATES_PROPERTY =
    "kotlin.internal.kmp.allowMatchingByRequestedCoordinatesInMetadataTransformations"
private const val KOTLIN_NATIVE_BINARY_CONTAINER_CLASS =
    "org.jetbrains.kotlin.gradle.dsl.KotlinNativeBinaryContainer"
private const val HOST_DESKTOP_TARGET_NAME = "desktop"
private const val HOST_DESKTOP_TARGET_NAME_CAPITALIZED = "Desktop"
private const val COMMON_MAIN_CONFIGURATION_PREFIX = "commonMain"
private const val DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX = "desktopNativeMain"
private const val RESOLVABLE_METADATA_CONFIGURATION_SUFFIX = "ResolvableDependenciesMetadata"
internal const val OFFICIAL_COMPOSE_GROUP_PREFIX = "org.jetbrains.compose."
internal const val OFFICIAL_ANDROIDX_GROUP_PREFIX = "org.jetbrains.androidx."
private const val ANDROIDX_GROUP_PREFIX = "androidx."
private const val ANDROIDX_COMPOSE_GROUP_PREFIX = "androidx.compose."
private const val ANDROIDX_COMPOSE_RUNTIME_GROUP = "androidx.compose.runtime"
internal const val FORK_COMPOSE_GROUP_PREFIX = "dev.brahmkshatriya.compose."
internal const val FORK_ANDROIDX_GROUP_PREFIX = "dev.brahmkshatriya.androidx."
private const val OFFICIAL_SKIKO_GROUP = "org.jetbrains.skiko"
private const val FORK_SKIKO_GROUP = "dev.brahmkshatriya.skiko"
private const val SKIKO_MODULE = "skiko"
private const val DEFAULT_NATIVE_SKIKO_VERSION = "0.153.1"
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
private val CROSS_PLATFORM_COMPOSE_MODULES = setOf("foundation:foundation", "material3:material3")
private val FORK_ANDROIDX_FAMILIES =
    setOf("collection", "lifecycle", "navigation", "navigation3", "navigationevent", "savedstate")
private val OFFICIAL_SKIKO_DEPENDENCY_REGEX =
    Regex(
        """(?s)("group"\s*:\s*")org\.jetbrains\.skiko("\s*,\s*"module"\s*:\s*"skiko"\s*,\s*"version"\s*:\s*\{\s*"(?:requires|strictly)"\s*:\s*")[^"]+(")"""
    )
private val DEFAULT_LINUX_LINKER_OPTIONS = listOf("-L/usr/lib")
private val DESKTOP_NATIVE_CONFIGURATION_MARKERS =
    listOf(
        "desktopnative",
        "linuxx64",
        "linuxarm64",
        "mingwx64",
        "macosx64",
        "macosarm64",
    )
private val REDIRECTED_NATIVE_CONFIGURATION_MARKERS =
    listOf(
        "linuxx64",
        "linuxarm64",
        "mingwx64",
        "macosarm64",
        "iosarm64",
        "iossimulatorarm64",
        "iosx64",
        "watchos",
        "tvos",
    )
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
private val SHARED_NATIVE_MAIN_CONFIGURATION_PREFIXES =
    listOf("desktopNativeMain", "linuxWindowsMain")
private val NATIVE_INTERNAL_COMPOSE_MODULES = setOf("ui" to "ui-skiko")
private val REDIRECTED_COMMON_METADATA_RUNTIME_MODULES =
    setOf("runtime", "runtime-annotation", "runtime-saveable")
private val REDIRECTED_NATIVE_RUNTIME_MODULES =
    REDIRECTED_COMMON_METADATA_RUNTIME_MODULES + "runtime-retain"
private val REDIRECTED_WEB_RUNTIME_MODULES = REDIRECTED_NATIVE_RUNTIME_MODULES
private val REDIRECTED_COMMON_METADATA_ANDROIDX_FAMILIES =
    setOf("lifecycle", "navigation", "navigation3", "navigationevent", "savedstate")

private val DESKTOP_NATIVE_TARGET_SOURCE_SETS =
    mapOf(
        "linux_x64" to "linuxX64",
        "linux_arm64" to "linuxArm64",
        "mingw_x64" to "mingwX64",
        "macos_x64" to "macosX64",
        "macos_arm64" to "macosArm64",
    )
private val DESKTOP_NATIVE_SOURCE_SET_NAMES = DESKTOP_NATIVE_TARGET_SOURCE_SETS.values
private val LINUX_WINDOWS_SOURCE_SET_NAMES = listOf("linuxX64", "linuxArm64", "mingwX64")
private val MACOS_SOURCE_SET_NAMES = listOf("macosX64", "macosArm64")
private val SKIA_TARGET_MAIN_SOURCE_SET_NAMES =
    listOf(
        "desktopNativeMain",
        "iosArm64Main",
        "iosSimulatorArm64Main",
        "iosX64Main",
        "wasmJsMain",
    )
private val DESKTOP_NATIVE_PUBLICATION_TARGET_NAMES = DESKTOP_NATIVE_SOURCE_SET_NAMES
