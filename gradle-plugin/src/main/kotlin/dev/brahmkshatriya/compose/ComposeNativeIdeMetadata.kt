@file:OptIn(ExternalKotlinTargetApi::class)

package dev.brahmkshatriya.compose

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.KlibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.klibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.sourcesClasspath
import org.jetbrains.kotlin.tooling.core.toMutableExtras

/**
 * Import root metadata archives as individual KLIBs with fragment identities, as KGP normally does.
 * An unresolved platform dependency must not replace a populated library with the same root name.
 * Desktop archives can also contain shared Kotlin fragments and expanded CInterop libraries; a JAR
 * classpath alone does not expose those to the IDE. Keep their published manifests unchanged and
 * leave missing platform dependencies visible.
 */
internal fun prepareForkIdeDependencies(
    dependencies: Set<IdeaKotlinDependency>,
    cacheDirectory: File,
    sourceSetName: String = COMMON_MAIN_SOURCE_SET,
): Set<IdeaKotlinDependency> {
    val existingMetadata =
        dependencies
            .filterIsInstance<IdeaKotlinResolvedBinaryDependency>()
            .filter { it.coordinates?.sourceSetName != null && it.classpath.isNotEmpty() }
            .mapNotNull { it.coordinates }
            .toSet()
    val desktop = sourceSetName == DESKTOP_NATIVE_MAIN_SOURCE_SET
    val fragments = if (desktop) DESKTOP_IDE_METADATA_FRAGMENTS else setOf(COMMON_MAIN_SOURCE_SET)
    return dependencies.flatMapTo(linkedSetOf()) { dependency ->
        if (dependency !is IdeaKotlinResolvedBinaryDependency) return@flatMapTo listOf(dependency)
        val coordinates = dependency.coordinates ?: return@flatMapTo listOf(dependency)
        if (
            coordinates.sourceSetName != null ||
                !(desktop && coordinates.group.startsWith(COMPOSE_FORK_GROUP_PREFIX) ||
                    isCrossPlatformForkModule(coordinates.group, coordinates.module))
        ) {
            return@flatMapTo listOf(dependency)
        }
        val artifact = dependency.classpath.singleOrNull()
        if (artifact == null || !artifact.isFile || artifact.extension != "jar") {
            return@flatMapTo if (
                coordinates.copy(sourceSetName = COMMON_MAIN_SOURCE_SET) in existingMetadata
            ) {
                emptyList()
            } else listOf(dependency)
        }
        val imported =
            ZipFile(artifact).use { archive ->
                val kotlinFragments =
                    fragments.filter { archive.getEntry("$it/default/manifest") != null }
                val interopPrefixes =
                    if (desktop) {
                        archive
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .filter { name ->
                                name.endsWith("/default/manifest") &&
                                    fragments.any { name.startsWith("$it-cinterop/") }
                            }
                            .map { it.removeSuffix("/default/manifest") }
                            .toList()
                    } else emptyList()
                if (kotlinFragments.isEmpty() && interopPrefixes.isEmpty()) return@use null
                val hash =
                    MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString(
                        ""
                    ) {
                        "%02x".format(it)
                    }
                (kotlinFragments + interopPrefixes).mapNotNull { prefix ->
                    val manifest =
                        Properties().apply {
                            archive
                                .getInputStream(archive.getEntry("$prefix/default/manifest"))
                                .use(::load)
                        }
                    val interop = manifest.getProperty("interop") == "true"
                    val extra = manifest.klibExtra()
                    val fragmentCoordinates =
                        if (interop) {
                            val uniqueName = requireNotNull(extra.uniqueName)
                            val targets =
                                extra.commonizerNativeTargets ?: extra.nativeTargets.orEmpty()
                            IdeaKotlinBinaryCoordinates(
                                group = uniqueName.substringBefore(":", "<unknown>"),
                                module = uniqueName.substringAfter(":"),
                                version = null,
                                sourceSetName =
                                    targets.singleOrNull()
                                        ?: targets.joinToString(prefix = "(", postfix = ")"),
                            )
                        } else coordinates.copy(sourceSetName = prefix)
                    if (fragmentCoordinates in existingMetadata) return@mapNotNull null
                    val output = cachedIdeMetadataKlib(artifact, cacheDirectory, hash, prefix)
                    dependency
                        .copy(
                            coordinates = fragmentCoordinates,
                            classpath = IdeaKotlinClasspath(output),
                            extras = dependency.extras.toMutableExtras(),
                        )
                        .apply { klibExtra = extra }
                }
            }
        imported ?: listOf(dependency)
    }
}

private fun cachedIdeMetadataKlib(
    artifact: File,
    cacheDirectory: File,
    hash: String,
    prefix: String,
): File {
    val name = prefix.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val output = cacheDirectory.resolve("${artifact.nameWithoutExtension}-$hash-$name.klib")
    if (!output.isFile) {
        cacheDirectory.mkdirs()
        val temporary = File.createTempFile("metadata", ".klib", cacheDirectory)
        try {
            writeMetadataKlib(artifact, temporary, prefix)
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
    return output
}

private fun Properties.klibExtra(): KlibExtra =
    KlibExtra(
        builtInsPlatform = getProperty("builtins_platform"),
        uniqueName = getProperty("unique_name"),
        shortName = getProperty("short_name"),
        packageFqName = getProperty("package"),
        nativeTargets = getProperty("native_targets")?.split(' ')?.filter(String::isNotBlank),
        commonizerNativeTargets =
            getProperty("commonizer_native_targets")?.split(' ')?.filter(String::isNotBlank),
        commonizerTarget = getProperty("commonizer_target"),
        isInterop = getProperty("interop")?.toBoolean(),
    )

// These fragments are shared by all native desktop targets. OS-specific fragments stay with KGP's
// target-aware resolvers; importing a Linux leaf library here would expose it on Windows and macOS.
private val DESKTOP_IDE_METADATA_FRAGMENTS =
    setOf(
        COMMON_MAIN_SOURCE_SET,
        "nonJvmMain",
        "nonAndroidMain",
        "nonAndroidExcludingWebMain",
        "nativeMain",
        "skikoMain",
        "skikoBackendMain",
        DESKTOP_NATIVE_MAIN_SOURCE_SET,
    )

internal fun propagateRootSourcesToTransformedDependencies(
    dependencies: Set<IdeaKotlinDependency>
) {
    val resolvedDependencies = dependencies.filterIsInstance<IdeaKotlinResolvedBinaryDependency>()
    val rootSourcesByModule =
        resolvedDependencies
            .filter { dependency ->
                val coordinates = dependency.coordinates ?: return@filter false
                coordinates.sourceSetName == null &&
                    coordinates.group.startsWith(COMPOSE_FORK_GROUP_PREFIX) &&
                    dependency.sourcesClasspath.isNotEmpty()
            }
            .groupBy(::moduleCoordinates)

    resolvedDependencies.forEach { dependency ->
        val coordinates = dependency.coordinates ?: return@forEach
        if (
            coordinates.sourceSetName == null ||
                !coordinates.group.startsWith(COMPOSE_FORK_GROUP_PREFIX)
        ) {
            return@forEach
        }
        rootSourcesByModule[moduleCoordinates(dependency)].orEmpty().forEach { rootDependency ->
            dependency.sourcesClasspath.addAll(rootDependency.sourcesClasspath)
        }
    }
}

private data class ModuleCoordinates(val group: String, val module: String, val version: String?)

private fun moduleCoordinates(dependency: IdeaKotlinResolvedBinaryDependency): ModuleCoordinates {
    val coordinates = requireNotNull(dependency.coordinates)
    return ModuleCoordinates(
        group = coordinates.group,
        module = coordinates.module,
        version = coordinates.version,
    )
}
