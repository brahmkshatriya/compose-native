package dev.brahmkshatriya.compose

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.plugins.ExtensionAware

class ComposeNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.addDesktopNativeSourceSets()
        project.configureSkikoCapabilityResolution()
        project.configureDependencySubstitutions()
    }
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
        if (!configuration.name.isDesktopNativeConfiguration()) return@configureEach
        configuration.resolutionStrategy.capabilitiesResolution.all { details ->
            if (
                details.capability.group != OFFICIAL_SKIKO_GROUP ||
                    !details.capability.name.startsWith("skiko")
            ) {
                return@all
            }
            val forkCandidate =
                details.candidates.firstOrNull { candidate ->
                    (candidate.id as? ModuleComponentIdentifier)?.group == FORK_SKIKO_GROUP
                }
            if (forkCandidate != null) details.select(forkCandidate)
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

        if (
            fullForkSubstitutions.isEmpty() &&
                nativeOverlaySubstitutions.isEmpty() &&
                fullForkComposeVersion == null &&
                nativeOverlayComposeVersion == null
        ) {
            return@afterEvaluate
        }

        configurations
            .configureEach { configuration ->
                val isAndroidConfiguration = configuration.name.isAndroidConfiguration()
                val substitutions =
                    if (configuration.name.isDesktopNativeConfiguration()) {
                        fullForkSubstitutions + nativeOverlaySubstitutions
                    } else {
                        fullForkSubstitutions
                    }
                val composeForkVersion =
                    if (configuration.name.isDesktopNativeConfiguration()) {
                        nativeOverlayComposeVersion ?: fullForkComposeVersion
                    } else {
                        fullForkComposeVersion
                    }
                if (substitutions.isEmpty() && composeForkVersion == null) return@configureEach

                configuration.resolutionStrategy.dependencySubstitution { rules ->
                    rules.all { details ->
                        val selector =
                            details.requested as? ModuleComponentSelector ?: return@all
                        val substitution =
                            substitutions["${selector.group}:${selector.module}"]
                        if (
                            substitution != null &&
                                (
                                    !selector.group.startsWith(ANDROIDX_COMPOSE_GROUP_PREFIX) ||
                                        isAndroidConfiguration
                                )
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
                                    includeJetBrainsAndroidx =
                                        configuration.name.isDesktopNativeConfiguration(),
                                    includeAndroidx =
                                        fullForkComposeVersion != null && isAndroidConfiguration,
                                )
                            } ?: return@all
                        details.useTarget(composeTarget)
                    }
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
        }.distinct()
    return versions.singleOrNull()
}

internal fun composeForkCoordinateFor(
    group: String,
    module: String,
    version: String,
    includeJetBrainsAndroidx: Boolean = true,
    includeAndroidx: Boolean,
): String? {
    if (group.startsWith(OFFICIAL_COMPOSE_GROUP_PREFIX)) {
        val family = group.removePrefix(OFFICIAL_COMPOSE_GROUP_PREFIX)
        if (family in COMPOSE_FAMILIES) {
            return "$FORK_COMPOSE_GROUP_PREFIX$family:$module:$version"
        }
    }
    if (includeJetBrainsAndroidx && group.startsWith(OFFICIAL_ANDROIDX_GROUP_PREFIX)) {
        val family = group.removePrefix(OFFICIAL_ANDROIDX_GROUP_PREFIX)
        if (family in FORK_ANDROIDX_FAMILIES) {
            return "$FORK_ANDROIDX_GROUP_PREFIX$family:$module:$version"
        }
    }
    if (includeAndroidx && group.startsWith(ANDROIDX_COMPOSE_GROUP_PREFIX)) {
        val family = group.removePrefix(ANDROIDX_COMPOSE_GROUP_PREFIX)
        if (
            family in ANDROIDX_COMPOSE_FAMILIES &&
                module.removeSuffix("-android") in ANDROIDX_COMPOSE_FORK_MODULES
        ) {
            return "$FORK_COMPOSE_GROUP_PREFIX$family:$module:$version"
        }
    }
    return null
}

internal data class ModuleSubstitution(
    val officialCoordinate: String,
    val forkCoordinate: String,
)

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

private fun String.isDesktopNativeConfiguration(): Boolean {
    val normalized = lowercase()
    return DESKTOP_NATIVE_CONFIGURATION_MARKERS.any(normalized::contains)
}

private fun String.isAndroidConfiguration(): Boolean = contains("android", ignoreCase = true)

internal fun Project.configureDesktopNativeExecutable(executableSpec: DesktopNativeExecutable) {
    val entryPoint = executableSpec.requiredEntryPoint()
    val kotlin = extensions.getByName("kotlin")
    @Suppress("UNCHECKED_CAST")
    val targets =
        kotlin.javaClass.methods
            .single { it.name == "getTargets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    DESKTOP_NATIVE_TARGET_SOURCE_SETS.forEach { (nativeTarget, targetName) ->
        val target = targets.getByName(targetName)
        val binaries =
            target.javaClass.methods
                .single {
                    it.name == "getBinaries" &&
                        it.parameterCount == 0 &&
                        it.returnType.name == KOTLIN_NATIVE_BINARY_CONTAINER_CLASS
                }
                .invoke(target)
        val existingExecutables =
            (binaries as Iterable<*>).filterNotNull().filter { binary ->
                binary.javaClass.methods.any {
                    it.name == "setEntryPoint" && it.parameterCount == 1
                }
            }
        if (existingExecutables.isNotEmpty()) {
            existingExecutables.forEach { binary ->
                binary.configureNativeExecutable(executableSpec, nativeTarget)
            }
            return@forEach
        }
        val executable =
            binaries.javaClass.methods.single {
                it.name == "executable" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single() == Action::class.java
            }
        executable.invoke(
            binaries,
            Action<Any> { binary -> binary.configureNativeExecutable(executableSpec, nativeTarget) },
        )
    }
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

        desktopNativeMain.dependsOnSourceSet(sourceSets.getByName("commonMain"))
        desktopNativeTest.dependsOnSourceSet(sourceSets.getByName("commonTest"))

        afterEvaluate {
            DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.forEach { sourceSetPrefix ->
                sourceSets
                    .findByName("${sourceSetPrefix}Main")
                    ?.dependsOnSourceSet(desktopNativeMain)
                sourceSets
                    .findByName("${sourceSetPrefix}Test")
                    ?.dependsOnSourceSet(desktopNativeTest)
            }
        }
    }
}

private fun Any.dependsOnSourceSet(sourceSet: Any) {
    javaClass.methods
        .single { it.name == "dependsOn" && it.parameterCount == 1 }
        .invoke(this, sourceSet)
}

private const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val KOTLIN_NATIVE_BINARY_CONTAINER_CLASS =
    "org.jetbrains.kotlin.gradle.dsl.KotlinNativeBinaryContainer"
private const val COMMON_MAIN_CONFIGURATION_PREFIX = "commonMain"
private const val DESKTOP_NATIVE_MAIN_CONFIGURATION_PREFIX = "desktopNativeMain"
private const val OFFICIAL_COMPOSE_GROUP_PREFIX = "org.jetbrains.compose."
private const val OFFICIAL_ANDROIDX_GROUP_PREFIX = "org.jetbrains.androidx."
private const val ANDROIDX_COMPOSE_GROUP_PREFIX = "androidx.compose."
private const val FORK_COMPOSE_GROUP_PREFIX = "dev.brahmkshatriya.compose."
private const val FORK_ANDROIDX_GROUP_PREFIX = "dev.brahmkshatriya.androidx."
private const val OFFICIAL_SKIKO_GROUP = "org.jetbrains.skiko"
private const val FORK_SKIKO_GROUP = "dev.brahmkshatriya.skiko"
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
        "runtime",
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
private val COMPOSE_FAMILIES =
    ANDROIDX_COMPOSE_FAMILIES + setOf("components", "desktop")
private val FORK_ANDROIDX_FAMILIES =
    setOf("lifecycle", "navigation", "navigation3", "navigationevent", "savedstate")
private val DEFAULT_LINUX_LINKER_OPTIONS = listOf("-L/usr/lib")
private val DESKTOP_NATIVE_CONFIGURATION_MARKERS =
    listOf("desktopnative", "linuxx64", "linuxarm64", "mingwx64")

private val DESKTOP_NATIVE_TARGET_SOURCE_SETS =
    mapOf("linux_x64" to "linuxX64", "linux_arm64" to "linuxArm64", "mingw_x64" to "mingwX64")
