package dev.brahmkshatriya.compose

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
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
import org.gradle.api.tasks.bundling.Zip
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

abstract class ComposeNativeApplicationExtension @Inject constructor(objects: ObjectFactory) {
    val applicationName: Property<String> = objects.property(String::class.java)
    val packageName: Property<String> = objects.property(String::class.java)
    val binaryName: Property<String> = objects.property(String::class.java)
    val executableName: Property<String> = objects.property(String::class.java)
    val packageVersion: Property<String> = objects.property(String::class.java)
    val description: Property<String> = objects.property(String::class.java)
    val vendor: Property<String> = objects.property(String::class.java)
    val categories: ListProperty<String> = objects.listProperty(String::class.java)
    val startupWmClass: Property<String> = objects.property(String::class.java)
    val stripLinuxExecutable: Property<Boolean> = objects.property(Boolean::class.java)
    val bundleSdl: Property<Boolean> = objects.property(Boolean::class.java)
    val iconFile: RegularFileProperty = objects.fileProperty()
    val distributionDirectory: DirectoryProperty = objects.directoryProperty()

    val linuxX64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()
    val linuxArm64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()
    val windowsX64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()

    val windowsSdlVersion: Property<String> = objects.property(String::class.java)
    val windowsSdlSha256: Property<String> = objects.property(String::class.java)
}

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
                findOnPath("strip") ?: throw GradleException("strip was not found on PATH"),
                "--strip-unneeded",
                executable.absolutePath,
            )
        }

        val libraryDirectory = root.resolve("usr/lib").apply(File::mkdirs)
        copyRuntimeFiles(runtimeFiles.files, libraryDirectory)
        if (bundleSdl.get() && !libraryDirectory.containsSdl3Runtime()) {
            if (!hostMatches(targetArchitecture.get())) {
                throw GradleException(
                    "Cannot discover the SDL 3 runtime for Linux ${targetArchitecture.get()} on " +
                        "this host. Add it to composeNativeApplication.${runtimePropertyName()}."
                )
            }
            bundleHostSdlRuntime(releaseExecutable.get().asFile, libraryDirectory)
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

@DisableCachingByDefault(because = "Downloads and unpacks the SDL runtime for Windows")
abstract class PrepareWindowsSdlRuntimeTask : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val archive = temporaryDir.resolve("SDL3-devel-${version.get()}-mingw.tar.gz")
        val url =
            URI.create(
                "https://github.com/libsdl-org/SDL/releases/download/release-${version.get()}/" +
                    archive.name
            )
        downloadWithRetry(url, archive)
        val actual = sha256(archive)
        check(actual == sha256.get()) {
            "SDL ${version.get()} checksum mismatch: expected ${sha256.get()}, got $actual"
        }
        extractTarGzEntries(
            archive,
            output,
            mapOf(
                "/x86_64-w64-mingw32/bin/SDL3.dll" to "SDL3.dll",
                "/x86_64-w64-mingw32/lib/libSDL3.dll.a" to "libSDL3.dll.a",
                "/x86_64-w64-mingw32/share/licenses/SDL3/LICENSE.txt" to "SDL3-LICENSE.txt",
            ),
        )
        check(output.resolve("SDL3.dll").isFile) { "SDL3.dll was not found in ${archive.name}" }
        check(output.resolve("libSDL3.dll.a").isFile) {
            "libSDL3.dll.a was not found in ${archive.name}"
        }
    }
}

@DisableCachingByDefault(because = "Assembles a platform-specific Windows distribution")
abstract class PrepareWindowsDistributionTask : DefaultTask() {
    @get:Input abstract val executableName: Property<String>

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

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        releaseExecutable
            .get()
            .asFile
            .copyTo(output.resolve("${executableName.get()}.exe"), overwrite = true)
        copyRuntimeFiles(runtimeFiles.files, output)
        if (resourceDirectory.isPresent) {
            val resources = resourceDirectory.get().asFile
            if (resources.isDirectory) {
                resources.copyRecursively(output.resolve("resources"), overwrite = true)
            }
        }
        if (iconFile.isPresent) {
            val icon = iconFile.get().asFile
            icon.copyTo(
                output.resolve("icon.${icon.extension.ifBlank { "png" }}"),
                overwrite = true,
            )
        }
    }
}

internal fun Project.createComposeNativeApplicationExtension() {
    if (extensions.findByName(COMPOSE_NATIVE_APPLICATION_EXTENSION_NAME) != null) return
    extensions
        .create(
            COMPOSE_NATIVE_APPLICATION_EXTENSION_NAME,
            ComposeNativeApplicationExtension::class.java,
        )
        .apply {
            applicationName.convention(name)
            packageName.convention(
                providers.provider {
                    val groupPart =
                        group.toString().takeIf { it.isNotBlank() && it != "unspecified" }
                    listOfNotNull(groupPart, name)
                        .joinToString(".")
                        .lowercase()
                        .replace(Regex("[^a-z0-9._-]"), "-")
                }
            )
            binaryName.convention(name)
            executableName.convention(name)
            packageVersion.convention(
                providers.provider {
                    version.toString().takeIf { it.isNotBlank() && it != "unspecified" } ?: "1.0.0"
                }
            )
            description.convention(applicationName)
            vendor.convention("")
            categories.convention(listOf("Utility"))
            startupWmClass.convention(applicationName)
            stripLinuxExecutable.convention(true)
            bundleSdl.convention(true)
            distributionDirectory.convention(layout.buildDirectory.dir("distributions"))
            windowsSdlVersion.convention(DEFAULT_WINDOWS_SDL_VERSION)
            windowsSdlSha256.convention(DEFAULT_WINDOWS_SDL_SHA256)
            windowsX64RuntimeFiles.from(defaultWindowsCxxRuntimeFiles())
        }
}

internal fun Project.configureDesktopNativeApplicationConventions() {
    afterEvaluate {
        val executableTargets =
            DESKTOP_NATIVE_APPLICATION_TARGETS.filter { target ->
                tasks.findByName("linkDebugExecutable${target.taskSuffix}") != null ||
                    tasks.findByName("linkReleaseExecutable${target.taskSuffix}") != null
            }
        if (executableTargets.isEmpty()) return@afterEvaluate

        addConventionalMainKotlinSources()
        addConventionalMainComposeResources()
        executableTargets.forEach(::configureExecutableResourceCopyTasks)
        executableTargets
            .filter { it.platform == NativeApplicationPlatform.LINUX }
            .forEach(::configureLinuxPackaging)
        executableTargets
            .filter { it.platform == NativeApplicationPlatform.WINDOWS }
            .forEach(::configureWindowsPackaging)
        configureSingleLinuxPackagingAliases(executableTargets)
    }
}

private fun Project.addConventionalMainKotlinSources() {
    val kotlin = extensions.getByName("kotlin")
    @Suppress("UNCHECKED_CAST")
    val sourceSets =
        kotlin.javaClass.methods
            .single { it.name == "getSourceSets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    val desktopNativeMain = sourceSets.getByName("desktopNativeMain")
    val kotlinSources =
        desktopNativeMain.javaClass.methods
            .single { it.name == "getKotlin" && it.parameterCount == 0 }
            .invoke(desktopNativeMain)
    kotlinSources.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
        .invoke(kotlinSources, layout.projectDirectory.dir("src/main/kotlin").asFile)
}

private fun Project.addConventionalMainComposeResources() {
    pluginManager.withPlugin("org.jetbrains.compose") {
        val compose = extensions.getByName("compose") as ExtensionAware
        val resources = compose.extensions.getByName("resources")
        val customDirectory =
            resources.javaClass.methods.first {
                it.name == "customDirectory" && it.parameterCount == 2
            }
        customDirectory.invoke(
            resources,
            "desktopNativeMain",
            providers.provider { layout.projectDirectory.dir("src/main/composeResources") },
        )
    }
}

private fun Project.configureExecutableResourceCopyTasks(target: DesktopNativeApplicationTarget) {
    val aggregateTaskName = "${target.sourceSetPrefix}AggregateResources"
    if (tasks.findByName(aggregateTaskName) == null) return
    listOf("debug", "release").forEach { buildType ->
        val capitalizedBuildType = buildType.replaceFirstChar(Char::uppercaseChar)
        val linkTaskName = "link${capitalizedBuildType}Executable${target.taskSuffix}"
        val linkTask = tasks.findByName(linkTaskName) ?: return@forEach
        val copyTaskName = "copy${capitalizedBuildType}${target.taskSuffix}ExecutableResources"
        val copyTask =
            tasks.findByName(copyTaskName)
                ?: tasks
                    .register(copyTaskName, Copy::class.java) { task ->
                        task.dependsOn(aggregateTaskName)
                        task.from(
                            layout.buildDirectory.dir(
                                "kotlin-multiplatform-resources/aggregated-resources/${target.sourceSetPrefix}"
                            )
                        )
                        task.into(
                            layout.buildDirectory.dir(
                                "bin/${target.sourceSetPrefix}/${buildType}Executable/resources"
                            )
                        )
                    }
                    .get()
        linkTask.finalizedBy(copyTask)
        tasks
            .findByName("run${capitalizedBuildType}Executable${target.taskSuffix}")
            ?.dependsOn(copyTask)
    }
}

private fun Project.configureLinuxPackaging(target: DesktopNativeApplicationTarget) {
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val releaseExecutable =
        layout.buildDirectory.file(
            extension.binaryName.map { binary ->
                "bin/${target.sourceSetPrefix}/releaseExecutable/$binary.kexe"
            }
        )
    val resourceDirectory =
        layout.buildDirectory.dir("bin/${target.sourceSetPrefix}/releaseExecutable/resources")
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
    val linkTaskName = "linkReleaseExecutable${target.taskSuffix}"
    val copyTaskName = "copyRelease${target.taskSuffix}ExecutableResources"
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

private fun Project.configureWindowsPackaging(target: DesktopNativeApplicationTarget) {
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val releaseExecutable =
        layout.buildDirectory.file(
            extension.binaryName.map { binary ->
                "bin/${target.sourceSetPrefix}/releaseExecutable/$binary.exe"
            }
        )
    val resourceDirectory =
        layout.buildDirectory.dir("bin/${target.sourceSetPrefix}/releaseExecutable/resources")
    val distributionDirectory =
        extension.distributionDirectory.dir(
            extension.applicationName.zip(extension.packageVersion) { app, version ->
                "${app.fileSafe()}-$version-windows-${target.packageArchitecture}"
            }
        )
    val sdl =
        tasks.register("prepareWindowsX64SdlRuntime", PrepareWindowsSdlRuntimeTask::class.java) {
            task ->
            task.version.set(extension.windowsSdlVersion)
            task.sha256.set(extension.windowsSdlSha256)
            task.outputDirectory.set(
                layout.buildDirectory.dir("composeNativeApplication/windowsX64/sdl")
            )
        }
    configureWindowsSdlLinker(target, sdl)
    val icu = configureWindowsIcuData()
    val linkTaskName = "linkReleaseExecutable${target.taskSuffix}"
    val copyTaskName = "copyRelease${target.taskSuffix}ExecutableResources"
    val prepare =
        tasks.register(
            "prepareWindowsX64ReleaseDistribution",
            PrepareWindowsDistributionTask::class.java,
        ) { task ->
            task.group = "distribution"
            task.description = "Assembles the Windows x64 release distribution."
            task.dependsOn(linkTaskName)
            if (extension.bundleSdl.get()) task.dependsOn(sdl)
            if (tasks.findByName(copyTaskName) != null) task.dependsOn(copyTaskName)
            task.executableName.set(extension.executableName)
            task.releaseExecutable.set(releaseExecutable)
            if (tasks.findByName(copyTaskName) != null)
                task.resourceDirectory.set(resourceDirectory)
            task.iconFile.set(extension.iconFile)
            task.runtimeFiles.from(extension.windowsX64RuntimeFiles)
            if (extension.bundleSdl.get()) {
                task.runtimeFiles.from(sdl.map { it.outputDirectory.file("SDL3.dll") })
                task.runtimeFiles.from(sdl.map { it.outputDirectory.file("SDL3-LICENSE.txt") })
            }
            task.runtimeFiles.from(icu)
            task.outputDirectory.set(distributionDirectory)
        }
    tasks.register("packageWindowsX64ReleaseZip", Zip::class.java) { task ->
        task.group = "distribution"
        task.description = "Builds the Windows x64 release zip."
        task.dependsOn(prepare)
        task.from(distributionDirectory)
        task.archiveBaseName.set(extension.applicationName.map(String::fileSafe))
        task.archiveVersion.set(extension.packageVersion)
        task.archiveClassifier.set("windows-x86_64")
        task.destinationDirectory.set(extension.distributionDirectory)
    }
}

private fun Project.configureWindowsSdlLinker(
    target: DesktopNativeApplicationTarget,
    sdl: org.gradle.api.tasks.TaskProvider<PrepareWindowsSdlRuntimeTask>,
) {
    val libraryDirectory = sdl.get().outputDirectory.get().asFile
    val kotlin = extensions.getByName("kotlin")
    @Suppress("UNCHECKED_CAST")
    val targets =
        kotlin.javaClass.methods
            .first { it.name == "getTargets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    val nativeTarget = targets.getByName(target.sourceSetPrefix)
    val binaries =
        nativeTarget.javaClass.methods
            .first { it.name == "getBinaries" && it.parameterCount == 0 }
            .invoke(nativeTarget)
    (binaries as Iterable<*>).filterNotNull().forEach { binary ->
        val linkerOpts =
            binary.javaClass.methods.firstOrNull {
                it.name == "linkerOpts" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single() == Iterable::class.java
            } ?: return@forEach
        linkerOpts.invoke(binary, listOf("-L${libraryDirectory.absolutePath}"))
    }
    listOf("Debug", "Release").forEach { buildType ->
        tasks.findByName("link${buildType}Executable${target.taskSuffix}")?.dependsOn(sdl)
    }
}

private fun Project.configureWindowsIcuData() =
    dependencies.let { projectDependencies ->
        configurations.maybeCreate("composeNativeWindowsIcuData").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            defaultDependencies { dependencySet ->
                val compileConfiguration =
                    configurations.findByName("mingwX64CompileKlibraries")
                        ?: return@defaultDependencies
                val skiko =
                    compileConfiguration.incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? ModuleComponentIdentifier }
                        .firstOrNull {
                            (it.group == "dev.brahmkshatriya.skiko" ||
                                it.group == "org.jetbrains.skiko") && it.module.startsWith("skiko")
                        } ?: return@defaultDependencies
                dependencySet.add(
                    projectDependencies.create(
                        "${skiko.group}:skiko-mingwx64:${skiko.version}:icudtl@dat"
                    )
                )
            }
        }
    }

private fun Project.configureSingleLinuxPackagingAliases(
    targets: List<DesktopNativeApplicationTarget>
) {
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

private fun Project.defaultWindowsCxxRuntimeFiles() =
    providers.provider {
        val konanRoot =
            providers.environmentVariable("KONAN_DATA_DIR").orNull?.takeIf(String::isNotBlank)
                ?: "${System.getProperty("user.home")}/.konan"
        val bin = File(konanRoot, "dependencies/msys2-mingw-w64-x86_64-2/bin")
        listOf("libstdc++-6.dll", "libgcc_s_seh-1.dll", "libwinpthread-1.dll")
            .map(bin::resolve)
            .filter(File::isFile)
    }

private fun copyRuntimeFiles(files: Set<File>, destination: File) {
    files.forEach { source ->
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                if (child.isFile)
                    child.copyTo(destination.resolve(runtimeFileName(child)), overwrite = true)
            }
        } else if (source.isFile) {
            source.copyTo(destination.resolve(runtimeFileName(source)), overwrite = true)
        }
    }
}

private fun runtimeFileName(file: File): String =
    if (file.extension == "dat" && file.name.contains("icudtl", ignoreCase = true)) "icudtl.dat"
    else file.name

private fun File.containsSdl3Runtime(): Boolean =
    listFiles().orEmpty().any { it.isFile && it.name.startsWith("libSDL3.so") }

private fun findOnPath(name: String): String? =
    System.getenv("PATH")
        ?.split(File.pathSeparatorChar)
        ?.asSequence()
        ?.map { File(it, name) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

private fun runCommand(vararg command: String) {
    val result = ProcessBuilder(*command).inheritIO().start().waitFor()
    if (result != 0) throw GradleException("Command failed ($result): ${command.joinToString(" ")}")
}

private fun desktopEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ")

private fun defaultIconSvg(name: String): String {
    val label = name.trim().firstOrNull()?.uppercaseChar() ?: 'C'
    return """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
<rect width="256" height="256" rx="52" fill="#4051b5"/>
<text x="128" y="164" text-anchor="middle" font-family="sans-serif" font-size="132" font-weight="600" fill="white">$label</text>
</svg>
"""
}

private fun downloadWithRetry(uri: URI, destination: File) {
    var lastFailure: Exception? = null
    repeat(3) { attempt ->
        try {
            val connection =
                uri.toURL().openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
            connection.getInputStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        } catch (failure: Exception) {
            destination.delete()
            lastFailure = failure
            if (attempt < 2) Thread.sleep((attempt + 1) * 1_000L)
        }
    }
    throw GradleException("Could not download $uri", lastFailure)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun extractTarGzEntries(archive: File, output: File, entries: Map<String, String>) {
    GZIPInputStream(archive.inputStream().buffered()).use { input ->
        val header = ByteArray(512)
        while (true) {
            if (!input.readFully(header)) break
            if (header.all { it == 0.toByte() }) break
            val name = header.copyOfRange(0, 100).toTarString()
            val size = header.copyOfRange(124, 136).toTarString().trim().ifBlank { "0" }.toLong(8)
            val outputName = entries.entries.firstOrNull { name.endsWith(it.key) }?.value
            if (outputName != null) {
                output.resolve(outputName).outputStream().use { sink ->
                    input.copyExactlyTo(sink, size)
                }
            } else {
                input.skipExactly(size)
            }
            input.skipExactly((512 - (size % 512)) % 512)
        }
    }
}

private fun ByteArray.toTarString(): String =
    takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()

private fun java.io.InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) return offset != 0
        offset += count
    }
    return true
}

private fun java.io.InputStream.copyExactlyTo(output: java.io.OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) throw java.io.EOFException("Unexpected end of tar archive")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun java.io.InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() >= 0) {
            remaining--
        } else {
            throw java.io.EOFException("Unexpected end of tar archive")
        }
    }
}

private fun String.fileSafe(): String =
    trim().ifBlank { "application" }.replace(Regex("[^A-Za-z0-9._-]"), "-")

private enum class NativeApplicationPlatform {
    LINUX,
    WINDOWS,
}

private data class DesktopNativeApplicationTarget(
    val sourceSetPrefix: String,
    val taskSuffix: String,
    val platform: NativeApplicationPlatform,
    val packageArchitecture: String,
    val displayName: String,
)

private val DESKTOP_NATIVE_APPLICATION_TARGETS =
    listOf(
        DesktopNativeApplicationTarget(
            "linuxX64",
            "LinuxX64",
            NativeApplicationPlatform.LINUX,
            "x86_64",
            "Linux x64",
        ),
        DesktopNativeApplicationTarget(
            "linuxArm64",
            "LinuxArm64",
            NativeApplicationPlatform.LINUX,
            "aarch64",
            "Linux arm64",
        ),
        DesktopNativeApplicationTarget(
            "mingwX64",
            "MingwX64",
            NativeApplicationPlatform.WINDOWS,
            "x86_64",
            "Windows x64",
        ),
    )

private const val COMPOSE_NATIVE_APPLICATION_EXTENSION_NAME = "composeNativeApplication"
private const val DEFAULT_WINDOWS_SDL_VERSION = "3.4.10"
private const val DEFAULT_WINDOWS_SDL_SHA256 =
    "39dd2ac370bf33d6332a21ed768d8d49c37cc6f3211d788ead765102722639a8"
