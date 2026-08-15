@file:OptIn(ExternalKotlinTargetApi::class)

package dev.brahmkshatriya.compose

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.ide.IdeDependencyResolver
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport
import org.jetbrains.kotlin.gradle.plugin.ide.dependencyResolvers.IdeBinaryDependencyResolver

/**
 * Adds the dependencies from KGP's resolvable metadata configurations to the IDE model.
 *
 * Compose Native deliberately mixes forked and official Compose modules. KGP's transformed
 * metadata resolver can lose transitive official modules when another module in the graph is
 * substituted to the fork. Compilation still works because it uses the complete Gradle
 * configuration, while Android Studio receives the incomplete transformed-metadata model.
 * Resolving the same configuration as ordinary Kotlin compile binaries preserves those modules.
 * The delegate intentionally contributes only official Compose UI metadata and the forked
 * common families used by Foundation and Material. KGP's normal resolvers remain responsible
 * for every other dependency and for platform KLIBs.
 */
internal fun Project.configureIdeDependencyResolution() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val officialStrategy =
            IdeBinaryDependencyResolver.ArtifactResolutionStrategy.ResolvableConfiguration(
                configurationSelector = { sourceSet ->
                    configurations.getByName("${sourceSet.name}ResolvableDependenciesMetadata")
                }
            )
        val forkStrategy =
            IdeBinaryDependencyResolver.ArtifactResolutionStrategy.ResolvableConfiguration(
                configurationSelector = { sourceSet ->
                    composeNativeIdeMetadataConfiguration(sourceSet.name)
                }
            )
        IdeMultiplatformImport.instance(this).registerDependencyResolver(
            ComposeNativeIdeDependencyResolver(
                project = this,
                officialDelegate =
                    IdeBinaryDependencyResolver(KOTLIN_COMPILE_BINARY_TYPE, officialStrategy),
                forkDelegate = IdeBinaryDependencyResolver(KOTLIN_COMPILE_BINARY_TYPE, forkStrategy),
            ),
            IdeMultiplatformImport.SourceSetConstraint { sourceSet ->
                sourceSet.name == COMMON_MAIN_SOURCE_SET ||
                    sourceSet.name == DESKTOP_NATIVE_MAIN_SOURCE_SET
            },
            IdeMultiplatformImport.DependencyResolutionPhase.PostDependencyResolution,
            IdeMultiplatformImport.Priority.high,
        )
    }
}

private class ComposeNativeIdeDependencyResolver(
    private val project: Project,
    private val officialDelegate: IdeDependencyResolver,
    private val forkDelegate: IdeDependencyResolver,
) : IdeDependencyResolver {
    override fun resolve(sourceSet: KotlinSourceSet): Set<IdeaKotlinDependency> {
        val result = linkedSetOf<IdeaKotlinDependency>()
        if (sourceSet.name == COMMON_MAIN_SOURCE_SET && project.hasCommonForkDependency()) {
            officialDelegate.resolve(sourceSet).filterTo(result, ::isOfficialCommonIdeDependency)
            forkDelegate.resolve(sourceSet).filterTo(result, ::isForkCommonComposeDependency)
        }
        if (sourceSet.name == DESKTOP_NATIVE_MAIN_SOURCE_SET && project.composeForkVersion() != null) {
            forkDelegate.resolve(sourceSet).filterTo(result, ::isDesktopComposeDependency)
        }
        return result
    }
}

private fun Project.hasCommonForkDependency(): Boolean =
    configurations
        .matching { it.name.startsWith(COMMON_MAIN_DEPENDENCY_CONFIGURATION_PREFIX) }
        .flatMap { it.dependencies }
        .any { it.group?.startsWith(COMPOSE_FORK_GROUP_PREFIX) == true }

private fun isOfficialCommonIdeDependency(dependency: IdeaKotlinDependency): Boolean {
    if (dependency !is IdeaKotlinResolvedBinaryDependency) return false
    val coordinates: IdeaKotlinBinaryCoordinates = dependency.coordinates ?: return false
    return isOfficialCommonIdeDependency(coordinates.group, coordinates.module)
}

internal fun isOfficialCommonIdeDependency(group: String, module: String): Boolean =
    when (group) {
        OFFICIAL_COMPOSE_UI_GROUP -> module !in PLATFORM_ONLY_COMPOSE_UI_MODULES
        OFFICIAL_NAVIGATION_EVENT_GROUP -> module == NAVIGATION_EVENT_COMPOSE_MODULE
        else -> false
    }

private fun isForkCommonComposeDependency(dependency: IdeaKotlinDependency): Boolean =
    dependency.binaryCoordinates()?.group in FORK_COMMON_COMPOSE_GROUPS

private fun isDesktopComposeDependency(dependency: IdeaKotlinDependency): Boolean {
    val coordinates = dependency.binaryCoordinates() ?: return false
    return coordinates.group.startsWith(COMPOSE_FORK_GROUP_PREFIX) ||
        (coordinates.group == OFFICIAL_COMPOSE_COMPONENTS_GROUP &&
            coordinates.module == COMPONENTS_RESOURCES_MODULE)
}

private fun IdeaKotlinDependency.binaryCoordinates(): IdeaKotlinBinaryCoordinates? =
    (this as? IdeaKotlinResolvedBinaryDependency)?.coordinates

private fun Project.composeNativeIdeMetadataConfiguration(sourceSetName: String): Configuration {
    val version = composeForkVersion() ?: return configurations.detachedConfiguration()
    val modules =
        if (sourceSetName == COMMON_MAIN_SOURCE_SET) FORK_COMMON_IDE_MODULES
        else FORK_DESKTOP_IDE_MODULES
    val dependencyNotations =
        modules.map { (family, module) ->
            "$COMPOSE_FORK_GROUP_PREFIX$family:$module:$version"
        } +
            if (sourceSetName == DESKTOP_NATIVE_MAIN_SOURCE_SET) {
                officialComposeVersion()?.let { officialVersion ->
                    listOf(
                        "$OFFICIAL_COMPOSE_COMPONENTS_GROUP:$COMPONENTS_RESOURCES_MODULE:$officialVersion"
                    )
                }.orEmpty()
            } else {
                emptyList()
            }
    val configuration =
        configurations.detachedConfiguration(
            *dependencyNotations.map(dependencies::create).toTypedArray()
        )
    val commonMetadata =
        configurations.getByName("${COMMON_MAIN_SOURCE_SET}ResolvableDependenciesMetadata")
    copyAttributes(from = commonMetadata, to = configuration)
    return configuration
}

private fun Project.officialComposeVersion(): String? =
    rootProject.allprojects
        .flatMap { project -> project.configurations.flatMap { it.dependencies } }
        .mapNotNull { dependency ->
            dependency.version?.takeIf(String::isNotBlank)?.takeIf {
                dependency.group?.startsWith(OFFICIAL_COMPOSE_GROUP_PREFIX) == true
            }
        }
        .distinct()
        .singleOrNull()

private fun Project.composeForkVersion(): String? {
    val sourceSetPrefixes =
        listOf(COMMON_MAIN_DEPENDENCY_CONFIGURATION_PREFIX, DESKTOP_NATIVE_MAIN_SOURCE_SET)
    return configurations
        .matching { configuration ->
            sourceSetPrefixes.any(configuration.name::startsWith)
        }
        .flatMap { it.dependencies }
        .mapNotNull { dependency ->
            dependency.version?.takeIf(String::isNotBlank)?.takeIf {
                dependency.group?.startsWith(COMPOSE_FORK_GROUP_PREFIX) == true
            }
        }
        .distinct()
        .singleOrNull()
}

private fun copyAttributes(from: Configuration, to: Configuration) {
    from.attributes.keySet().forEach { attribute -> copyAttribute(attribute, from, to) }
}

@Suppress("UNCHECKED_CAST")
private fun copyAttribute(
    attribute: Attribute<*>,
    from: Configuration,
    to: Configuration,
) {
    val typedAttribute = attribute as Attribute<Any>
    from.attributes.getAttribute(typedAttribute)?.let { value ->
        to.attributes.attribute(typedAttribute, value)
    }
}

private const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val KOTLIN_COMPILE_BINARY_TYPE = "KOTLIN_COMPILE"
private const val COMMON_MAIN_SOURCE_SET = "commonMain"
private const val DESKTOP_NATIVE_MAIN_SOURCE_SET = "desktopNativeMain"
private const val COMMON_MAIN_DEPENDENCY_CONFIGURATION_PREFIX = "commonMain"
private const val COMPOSE_FORK_GROUP_PREFIX = "dev.brahmkshatriya.compose."
private const val OFFICIAL_COMPOSE_GROUP_PREFIX = "org.jetbrains.compose."
private const val OFFICIAL_COMPOSE_UI_GROUP = "org.jetbrains.compose.ui"
private const val OFFICIAL_COMPOSE_COMPONENTS_GROUP = "org.jetbrains.compose.components"
private const val OFFICIAL_NAVIGATION_EVENT_GROUP = "androidx.navigationevent"
private const val COMPONENTS_RESOURCES_MODULE = "components-resources"
private const val NAVIGATION_EVENT_COMPOSE_MODULE = "navigationevent-compose"
private val PLATFORM_ONLY_COMPOSE_UI_MODULES = setOf("ui-uikit", "ui-skiko")
private val FORK_COMMON_COMPOSE_GROUPS =
    setOf(
        "dev.brahmkshatriya.compose.animation",
        "dev.brahmkshatriya.compose.foundation",
        "dev.brahmkshatriya.compose.material",
        "dev.brahmkshatriya.compose.material3",
    )
private val FORK_COMMON_IDE_MODULES =
    setOf(
        "animation" to "animation",
        "animation" to "animation-core",
        "animation" to "animation-graphics",
        "foundation" to "foundation",
        "foundation" to "foundation-layout",
        "material" to "material",
        "material" to "material-ripple",
        "material3" to "material3",
    )
private val FORK_DESKTOP_IDE_MODULES =
    FORK_COMMON_IDE_MODULES +
        setOf(
            "components" to "components-resources",
            "runtime" to "runtime",
            "runtime" to "runtime-saveable",
            "ui" to "ui",
            "ui" to "ui-backhandler",
            "ui" to "ui-geometry",
            "ui" to "ui-graphics",
            "ui" to "ui-skiko",
            "ui" to "ui-text",
            "ui" to "ui-unit",
            "ui" to "ui-util",
        )
