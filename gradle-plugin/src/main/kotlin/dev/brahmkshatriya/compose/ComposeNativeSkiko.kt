package dev.brahmkshatriya.compose

import java.io.Serializable
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.publish.tasks.GenerateModuleMetadata

internal fun Project.configureSkikoResolution() {
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
    afterEvaluate { configureNativeSkikoResolution(nativeSkikoPublicationVersion()) }
}

internal fun Project.configureNativeSkikoPublicationMetadata() {
    afterEvaluate {
        val nativeSkikoVersion = nativeSkikoPublicationVersion()
        tasks.withType(GenerateModuleMetadata::class.java).configureEach { task ->
            if (!task.name.isDesktopNativePublicationMetadataTask()) return@configureEach
            task.doLast(RewriteNativeSkikoPublicationMetadataAction(nativeSkikoVersion))
        }
    }
}

private fun Project.configureNativeSkikoResolution(nativeSkikoVersion: String) {
    configurations.configureEach { configuration ->
        if (!configuration.name.usesNativeOverlay()) return@configureEach
        configuration.resolutionStrategy.dependencySubstitution { rules ->
            rules.all { details ->
                val selector = details.requested as? ModuleComponentSelector ?: return@all
                val coordinate =
                    nativeSkikoCoordinateFor(selector.group, selector.module, nativeSkikoVersion)
                        ?: return@all
                details.useTarget(coordinate, "Use the compatible Compose Native Skiko release")
            }
        }
    }
}

internal fun nativeSkikoCoordinateFor(group: String, module: String, version: String): String? {
    if (group != OFFICIAL_SKIKO_GROUP && group != FORK_SKIKO_GROUP) return null
    if (module !in NATIVE_SKIKO_MODULES) return null
    return "$FORK_SKIKO_GROUP:$module:$version"
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
    NATIVE_SKIKO_DEPENDENCY_REGEX.replace(metadata) { match ->
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

private const val SKIKO_MODULE = "skiko"

private const val DEFAULT_NATIVE_SKIKO_VERSION = "0.156.1"

private val NATIVE_SKIKO_MODULES =
    DESKTOP_NATIVE_TARGETS.map { "skiko-${it.konanName.replace("_", "")}" }.toSet() + SKIKO_MODULE

private val NATIVE_SKIKO_DEPENDENCY_REGEX =
    Regex(
        """(?s)("group"\s*:\s*")(?:org\.jetbrains|dev\.brahmkshatriya)\.skiko("\s*,\s*"module"\s*:\s*"skiko"\s*,\s*"version"\s*:\s*\{\s*"(?:requires|strictly)"\s*:\s*")[^"]+(")"""
    )
