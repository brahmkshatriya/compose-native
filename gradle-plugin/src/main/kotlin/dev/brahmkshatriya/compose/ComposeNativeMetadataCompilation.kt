package dev.brahmkshatriya.compose

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class RepairCommonMetadataLibraries : DefaultTask() {
    @get:Classpath
    abstract val metadataArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun repair() {
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val candidates =
            metadataArtifacts.files
                .asSequence()
                .mapNotNull(::commonMetadataCandidate)
                .groupBy(CommonMetadataCandidate::uniqueName)
                .mapValues { (_, candidates) ->
                    candidates.sortedWith(
                        compareByDescending<CommonMetadataCandidate> { it.payloadSize }
                            .thenBy { it.artifact.absolutePath }
                    ).first()
                }

        candidates.toSortedMap().forEach { (uniqueName, candidate) ->
            writeCommonMetadataKlib(
                sourceArtifact = candidate.artifact,
                output = outputRoot.resolve("${sanitizeFileName(uniqueName)}.klib"),
            )
        }
    }
}

private data class CommonMetadataCandidate(
    val artifact: File,
    val uniqueName: String,
    val payloadSize: Long,
)

private fun commonMetadataCandidate(artifact: File): CommonMetadataCandidate? {
    if (!artifact.isFile) return null
    return runCatching {
            ZipFile(artifact).use { source ->
                val prefix = "commonMain/"
                val manifestEntry = source.getEntry("${prefix}default/manifest") ?: return@use null
                val manifest = source.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                val uniqueName = manifestValue(manifest, "unique_name") ?: return@use null
                val payloadSize =
                    source.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory && entry.name.startsWith(prefix) && entry.name != prefix
                        }
                        .sumOf { entry -> entry.size.coerceAtLeast(0L) }
                CommonMetadataCandidate(artifact, uniqueName, payloadSize)
            }
        }
        .getOrNull()
}

private fun writeCommonMetadataKlib(sourceArtifact: File, output: File) {
    ZipFile(sourceArtifact).use { source ->
        val prefix = "commonMain/"
        val entries =
            source.entries().asSequence()
                .filter { entry -> entry.name.startsWith(prefix) && entry.name != prefix }
                .sortedBy { it.name }
                .toList()
        ZipOutputStream(BufferedOutputStream(output.outputStream())).use { target ->
            entries.forEach { entry ->
                val relativeName = entry.name.removePrefix(prefix)
                if (relativeName.isEmpty()) return@forEach
                target.putNextEntry(ZipEntry(relativeName).apply { time = 0L })
                if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(target) }
                target.closeEntry()
            }
        }
    }
}

internal fun Project.configureMetadataCompilation() {
    pluginManager.withPlugin(METADATA_KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val repairCommonMetadata =
            tasks.register("repairComposeNativeCommonMetadata", RepairCommonMetadataLibraries::class.java) {
                it.outputDirectory.set(
                    layout.buildDirectory.dir("kotlinComposeNativeMetadataLibraries/commonMain")
                )
            }
        repairCommonMetadata.configure { repair ->
            repair.dependsOn(TRANSFORM_COMMON_MAIN_METADATA_TASK)
        }
        val representativeNativeLibraries = objects.fileCollection()

        configurations.configureEach { configuration ->
            when (configuration.name) {
                COMMON_MAIN_RESOLVABLE_METADATA_CONFIGURATION -> {
                    val officialMetadata =
                        configuration.incoming.artifactView { view ->
                            view.componentFilter { component ->
                                component is ModuleComponentIdentifier &&
                                    isOfficialCommonIdeDependency(component.group, component.module)
                            }
                        }.files
                    repairCommonMetadata.configure { it.metadataArtifacts.from(officialMetadata) }
                }
                REPRESENTATIVE_NATIVE_COMPILE_LIBRARIES_CONFIGURATION ->
                    representativeNativeLibraries.from(configuration)
            }
        }

        tasks.configureEach { task ->
            if (task.name.isKotlinMetadataCompilationTask()) {
                task.dependsOn(repairCommonMetadata)
                task.replaceLibraries(
                    this,
                    repairCommonMetadata.map { repair ->
                        repair.outputDirectory.get().asFileTree.matching { it.include("*.klib") }
                    },
                )
            }
            if (task.name == COMPILE_DESKTOP_NATIVE_MAIN_METADATA_TASK) {
                val cinteropLibraries = representativeNativeLibraries.filter(::isCInteropKlib)
                task.addNativeLibraryCompilerArguments(this, cinteropLibraries)
            }
        }
    }
}

internal fun String.isKotlinMetadataCompilationTask(): Boolean =
    startsWith("compile") && endsWith("KotlinMetadata")

@Suppress("UNCHECKED_CAST")
private fun Task.replaceLibraries(project: Project, libraries: Any) {
    val target =
        javaClass.methods
            .singleOrNull { it.name == "getLibraries" && it.parameterCount == 0 }
            ?.invoke(this) as? ConfigurableFileCollection ?: return
    val originalSources = target.from.toList()
    val repaired = project.files(libraries)
    val repairedUniqueNames = repaired.files.mapNotNull(::klibUniqueName).toSet()
    val originals =
        project.files(originalSources).filter { file ->
            val uniqueName = klibUniqueName(file)
            uniqueName == null || uniqueName !in repairedUniqueNames
        }
    target.setFrom(originals, repaired)
}

@Suppress("UNCHECKED_CAST")
private fun Task.addNativeLibraryCompilerArguments(project: Project, libraries: FileCollection) {
    val compilerOptions =
        javaClass.methods
            .firstOrNull { method ->
                method.name == "getCompilerOptions" &&
                    method.parameterCount == 0 &&
                    method.returnType.name == "org.jetbrains.kotlin.gradle.dsl.KotlinNativeCompilerOptions"
            }
            ?.invoke(this) ?: return
    val freeCompilerArgs =
        compilerOptions.javaClass.methods
            .singleOrNull { it.name == "getFreeCompilerArgs" && it.parameterCount == 0 }
            ?.invoke(compilerOptions) as? ListProperty<String> ?: return
    inputs.files(libraries).withPropertyName("composeNativeSharedCInteropLibraries")
    freeCompilerArgs.addAll(
        project.providers.provider {
            libraries.files.sortedBy(File::getAbsolutePath).flatMap { file ->
                listOf("-library", file.absolutePath)
            }
        }
    )
}

private fun isCInteropKlib(file: File): Boolean {
    if (!file.isFile || file.extension != "klib") return false
    return runCatching {
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("default/manifest") ?: return@use false
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            manifestValue(manifest, "interop") == "true"
        }
    }.getOrDefault(false)
}

private fun klibUniqueName(file: File): String? =
    runCatching {
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("default/manifest") ?: return@use null
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            manifestValue(manifest, "unique_name")
        }
    }.getOrNull()

private fun manifestValue(manifest: String, key: String): String? =
    manifest.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun sanitizeFileName(value: String): String =
    value.map { char -> if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_' }
        .joinToString("")

private const val METADATA_KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val COMMON_MAIN_RESOLVABLE_METADATA_CONFIGURATION =
    "commonMainResolvableDependenciesMetadata"
private const val REPRESENTATIVE_NATIVE_COMPILE_LIBRARIES_CONFIGURATION = "linuxX64CompileKlibraries"
private const val TRANSFORM_COMMON_MAIN_METADATA_TASK = "transformCommonMainDependenciesMetadata"
private const val COMPILE_DESKTOP_NATIVE_MAIN_METADATA_TASK = "compileDesktopNativeMainKotlinMetadata"
