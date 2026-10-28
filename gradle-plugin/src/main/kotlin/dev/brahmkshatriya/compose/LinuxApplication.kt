package dev.brahmkshatriya.compose

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(
    because = "Runs host tools and assembles a platform-specific application directory"
)
abstract class PrepareLinuxAppDirTask : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val executableName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val applicationDescription: Property<String>
    @get:Input abstract val vendor: Property<String>
    @get:Input abstract val categories: ListProperty<String>
    @get:Input abstract val startupWmClass: Property<String>
    @get:Input abstract val stripExecutable: Property<Boolean>
    @get:Input abstract val bundleSdl: Property<Boolean>
    @get:Input abstract val targetArchitecture: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseExecutable: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirectory: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sdlRuntimeFiles: ConfigurableFileCollection

    @get:OutputDirectory abstract val appDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = appDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()

        val packageId = packageName.get()
        val executable = root.resolve("usr/bin/${executableName.get()}")
        executable.parentFile.mkdirs()
        releaseExecutable.get().asFile.copyTo(executable, overwrite = true)
        executable.setExecutable(true, false)

        if (stripExecutable.get()) {
            runCommand(
                linuxStripToolNames(targetArchitecture.get(), hostMatches(targetArchitecture.get()))
                    .firstNotNullOfOrNull(::findOnPath)
                    ?: throw GradleException(
                        "Install a strip tool for Linux ${targetArchitecture.get()} or llvm-strip"
                    ),
                "--strip-unneeded",
                executable.absolutePath,
            )
        }

        val libraryDirectory = root.resolve("usr/lib").apply(File::mkdirs)
        copyRuntimeFiles(runtimeFiles.files, libraryDirectory)
        if (bundleSdl.get() && !libraryDirectory.containsSdl3Runtime()) {
            val sdl = sdlRuntimeFiles.files.firstOrNull()
            if (sdl != null) {
                // Keep SDL's SONAME even when only a versioned library file was installed.
                sdl.copyTo(libraryDirectory.resolve("libSDL3.so.0"), overwrite = true)
            } else {
                if (!hostMatches(targetArchitecture.get())) {
                    throw GradleException(
                        "Cannot discover the SDL 3 runtime for Linux ${targetArchitecture.get()} on " +
                            "this host. Configure composeNativeApplication.${runtimePropertyName().replace("RuntimeFiles", "LibraryDirectories")} " +
                            "or composeNativeApplication.${runtimePropertyName()}."
                    )
                }
                bundleHostSdlRuntime(releaseExecutable.get().asFile, libraryDirectory)
            }
        }

        if (resourceDirectory.isPresent) {
            val resources = resourceDirectory.get().asFile
            if (resources.isDirectory) {
                resources.copyRecursively(
                    target = root.resolve("usr/share/$packageId/resources"),
                    overwrite = true,
                )
            }
        }

        val desktopText = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=${desktopEscape(applicationName.get())}")
            appendLine("Comment=${desktopEscape(applicationDescription.get())}")
            appendLine("Exec=${executableName.get()}")
            appendLine("Icon=$packageId")
            appendLine(
                "Categories=${categories.get().joinToString(separator = ";", postfix = ";")}"
            )
            appendLine("Terminal=false")
            startupWmClass.orNull?.takeIf(String::isNotBlank)?.let {
                appendLine("StartupWMClass=${desktopEscape(it)}")
            }
            appendLine("X-AppImage-Version=${desktopEscape(packageVersion.get())}")
            vendor.orNull?.takeIf(String::isNotBlank)?.let {
                appendLine("X-AppImage-Vendor=${desktopEscape(it)}")
            }
        }
        val desktopAtRoot = root.resolve("$packageId.desktop")
        desktopAtRoot.writeText(desktopText)
        val desktopInstalled = root.resolve("usr/share/applications/$packageId.desktop")
        desktopInstalled.parentFile.mkdirs()
        desktopInstalled.writeText(desktopText)

        val iconExtension: String
        val iconSource: File
        if (iconFile.isPresent) {
            iconSource = iconFile.get().asFile
            iconExtension = iconSource.extension.lowercase().ifBlank { "png" }
        } else {
            iconExtension = "svg"
            iconSource = root.resolve(".generated-$packageId.svg")
            iconSource.writeText(defaultIconSvg(applicationName.get()))
        }
        iconSource.copyTo(root.resolve("$packageId.$iconExtension"), overwrite = true)
        val installedIcon =
            if (iconExtension == "svg") {
                root.resolve("usr/share/icons/hicolor/scalable/apps/$packageId.svg")
            } else {
                root.resolve("usr/share/icons/hicolor/512x512/apps/$packageId.$iconExtension")
            }
        installedIcon.parentFile.mkdirs()
        iconSource.copyTo(installedIcon, overwrite = true)
        if (iconSource.name.startsWith(".generated-")) iconSource.delete()

        root.resolve("AppRun").apply {
            writeText(
                """#!/bin/sh
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export LD_LIBRARY_PATH="${'$'}APPDIR/usr/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
export COMPOSE_RESOURCE_ROOT="${'$'}APPDIR/usr/share/$packageId/resources"
exec "${'$'}APPDIR/usr/bin/${executableName.get()}" "${'$'}@"
"""
            )
            setExecutable(true, false)
        }
    }

    private fun bundleHostSdlRuntime(sourceExecutable: File, destination: File) {
        val ldd = findOnPath("ldd") ?: throw GradleException("ldd was not found on PATH")
        val process =
            ProcessBuilder(ldd, sourceExecutable.absolutePath).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (process.waitFor() != 0) {
            throw GradleException("ldd failed for ${sourceExecutable.absolutePath}")
        }
        val sdlLine =
            lines.firstOrNull { it.trimStart().startsWith("libSDL3.so") }
                ?: throw GradleException(
                    "Could not locate the SDL 3 runtime required by $sourceExecutable"
                )
        val path =
            sdlLine.substringAfter("=>", "").trim().substringBefore(' ').takeIf(String::isNotBlank)
                ?: throw GradleException("Could not locate SDL 3 runtime: $sdlLine")
        val library = File(path)
        if (!library.isFile) throw GradleException("Could not locate SDL 3 runtime: $sdlLine")
        library.copyTo(destination.resolve(library.name), overwrite = true)
    }

    private fun hostMatches(target: String): Boolean {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return false
        val host = System.getProperty("os.arch").lowercase()
        return when (target) {
            "x86_64" -> host == "x86_64" || host == "amd64"
            "aarch64" -> host == "aarch64" || host == "arm64"
            else -> false
        }
    }

    private fun runtimePropertyName(): String =
        if (targetArchitecture.get() == "aarch64") "linuxArm64RuntimeFiles"
        else "linuxX64RuntimeFiles"
}

@DisableCachingByDefault(because = "Invokes appimagetool, which embeds a platform runtime")
abstract class PackageLinuxAppImageTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDir: DirectoryProperty

    @get:Input abstract val architecture: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageAppImage() {
        val tool =
            System.getenv("APPIMAGETOOL")?.takeIf(String::isNotBlank)
                ?: findOnPath("appimagetool")
                ?: throw GradleException(
                    "appimagetool was not found. Install it or set APPIMAGETOOL."
                )
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.delete()
        execOperations.exec { spec ->
            spec.executable(tool)
            spec.args("--no-appstream", appDir.get().asFile.absolutePath, output.absolutePath)
            spec.environment("ARCH", architecture.get())
        }
        output.setExecutable(true, false)
    }
}

internal fun Project.configureLinuxPackaging(target: DesktopNativeTarget) {
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val binarySourceSetPrefix = target.sourceSetPrefix
    val binaryTaskSuffix = target.taskSuffix
    val releaseExecutable =
        layout.buildDirectory.file(
            extension.binaryName.map { binary ->
                "bin/$binarySourceSetPrefix/releaseExecutable/$binary.kexe"
            }
        )
    val resourceDirectory =
        layout.buildDirectory.dir("bin/$binarySourceSetPrefix/releaseExecutable/resources")
    val appDir =
        extension.distributionDirectory.dir(
            extension.applicationName.zip(extension.packageVersion) { app, version ->
                "${app.fileSafe()}-$version-linux-${target.packageArchitecture}.AppDir"
            }
        )
    val runtimeFiles =
        if (target.sourceSetPrefix == "linuxArm64") {
            extension.linuxArm64RuntimeFiles
        } else {
            extension.linuxX64RuntimeFiles
        }
    val linkTaskName = "linkReleaseExecutable$binaryTaskSuffix"
    val copyTaskName = "copyRelease${binaryTaskSuffix}ExecutableResources"
    val prepareTaskName = "prepare${target.taskSuffix}ReleaseAppDir"
    val prepare =
        tasks.register(prepareTaskName, PrepareLinuxAppDirTask::class.java) { task ->
            task.group = "distribution"
            task.description = "Assembles the ${target.displayName} release AppDir."
            task.dependsOn(linkTaskName)
            if (tasks.findByName(copyTaskName) != null) task.dependsOn(copyTaskName)
            task.applicationName.set(extension.applicationName)
            task.packageName.set(extension.packageName)
            task.executableName.set(extension.executableName)
            task.packageVersion.set(extension.packageVersion)
            task.applicationDescription.set(extension.description)
            task.vendor.set(extension.vendor)
            task.categories.set(extension.categories)
            task.startupWmClass.set(extension.startupWmClass)
            task.stripExecutable.set(extension.stripLinuxExecutable)
            task.bundleSdl.set(extension.bundleSdl)
            task.targetArchitecture.set(target.packageArchitecture)
            task.releaseExecutable.set(releaseExecutable)
            if (tasks.findByName(copyTaskName) != null)
                task.resourceDirectory.set(resourceDirectory)
            task.iconFile.set(extension.iconFile)
            task.runtimeFiles.from(runtimeFiles)
            task.sdlRuntimeFiles.from(
                extension.bundleSdl.map { bundle ->
                    if (bundle)
                        listOfNotNull(
                            findLinuxSdlRuntime(
                                extension.libraryDirectories(target.sourceSetPrefix).files,
                                target.packageArchitecture,
                            )
                        )
                    else emptyList()
                }
            )
            task.appDir.set(appDir)
        }
    tasks.register(
        "package${target.taskSuffix}ReleaseAppImage",
        PackageLinuxAppImageTask::class.java,
    ) { task ->
        task.group = "distribution"
        task.description = "Builds the ${target.displayName} release AppImage."
        task.dependsOn(prepare)
        task.appDir.set(appDir)
        task.architecture.set(target.packageArchitecture)
        task.outputFile.set(
            extension.distributionDirectory.file(
                extension.applicationName.zip(extension.packageVersion) { app, version ->
                    "${app.fileSafe()}-$version-linux-${target.packageArchitecture}.AppImage"
                }
            )
        )
    }
}

internal fun Project.configureSingleLinuxPackagingAliases(targets: List<DesktopNativeTarget>) {
    val linuxTargets = targets.filter { it.platform == NativeApplicationPlatform.LINUX }
    if (linuxTargets.size != 1) return
    val target = linuxTargets.single()
    tasks.register("prepareLinuxReleaseAppDir") { task ->
        task.group = "distribution"
        task.dependsOn("prepare${target.taskSuffix}ReleaseAppDir")
    }
    tasks.register("packageReleaseAppImage") { task ->
        task.group = "distribution"
        task.dependsOn("package${target.taskSuffix}ReleaseAppImage")
    }
}

private fun File.containsSdl3Runtime(): Boolean =
    listFiles().orEmpty().any { it.isFile && it.name.startsWith("libSDL3.so") }

private fun desktopEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ")
