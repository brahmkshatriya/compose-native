package dev.brahmkshatriya.compose

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.work.DisableCachingByDefault

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

internal fun Project.configureWindowsPackaging(target: DesktopNativeTarget) {
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val binarySourceSetPrefix = target.sourceSetPrefix
    val binaryTaskSuffix = target.taskSuffix
    val releaseExecutable =
        layout.buildDirectory.file(
            extension.binaryName.map { binary ->
                "bin/$binarySourceSetPrefix/releaseExecutable/$binary.exe"
            }
        )
    val resourceDirectory =
        layout.buildDirectory.dir("bin/$binarySourceSetPrefix/releaseExecutable/resources")
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
    val cxxRuntime =
        tasks.register("prepareWindowsX64CxxRuntime", PrepareWindowsCxxRuntimeTask::class.java) {
            task ->
            task.konanDataDirectory.set(
                providers
                    .environmentVariable("KONAN_DATA_DIR")
                    .orElse(providers.systemProperty("user.home").map { "$it/.konan" })
            )
            task.outputDirectory.set(
                layout.buildDirectory.dir("composeNativeApplication/windowsX64/cxxRuntime")
            )
        }
    configureWindowsSdlLinker(target, sdl)
    val icu = configureWindowsIcuData()
    configureWindowsExecutableRuntimeCopyTasks(target, extension, sdl, cxxRuntime, icu)
    val linkTaskName = "linkReleaseExecutable$binaryTaskSuffix"
    val copyTaskName = "copyRelease${binaryTaskSuffix}ExecutableResources"
    cxxRuntime.configure { task -> task.mustRunAfter(linkTaskName) }
    val prepare =
        tasks.register(
            "prepareWindowsX64ReleaseDistribution",
            PrepareWindowsDistributionTask::class.java,
        ) { task ->
            task.group = "distribution"
            task.description = "Assembles the Windows x64 release distribution."
            task.dependsOn(linkTaskName)
            task.dependsOn(cxxRuntime)
            if (extension.bundleSdl.get()) task.dependsOn(sdl)
            if (tasks.findByName(copyTaskName) != null) task.dependsOn(copyTaskName)
            task.executableName.set(extension.executableName)
            task.releaseExecutable.set(releaseExecutable)
            if (tasks.findByName(copyTaskName) != null)
                task.resourceDirectory.set(resourceDirectory)
            task.iconFile.set(extension.iconFile)
            task.runtimeFiles.from(extension.windowsX64RuntimeFiles)
            task.runtimeFiles.from(cxxRuntime.flatMap { it.outputDirectory })
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
    tasks.register("packageWindowsX64ReleaseMsi", PackageWindowsMsiTask::class.java) { task ->
        task.group = "distribution"
        task.description = "Builds the Windows x64 release MSI installer."
        task.dependsOn(prepare)
        task.applicationName.set(extension.applicationName)
        task.packageName.set(extension.packageName)
        task.packageVersion.set(extension.packageVersion)
        task.applicationDescription.set(extension.description)
        task.vendor.set(extension.vendor)
        task.wixExecutable.set(extension.windowsWixExecutable)
        task.distributionDirectory.set(distributionDirectory)
        task.outputFile.set(
            extension.distributionDirectory.file(
                extension.applicationName.zip(extension.packageVersion) { app, version ->
                    app.fileSafe() + "-" + version + "-windows-x86_64.msi"
                }
            )
        )
    }
    val installerExe =
        tasks.register(
            "packageWindowsX64ReleaseInstallerExe",
            PackageWindowsInstallerExeTask::class.java,
        ) { task ->
            task.group = "distribution"
            task.description = "Builds the Windows x64 release installer executable."
            task.dependsOn(prepare)
            task.applicationName.set(extension.applicationName)
            task.packageName.set(extension.packageName)
            task.executableName.set(extension.executableName)
            task.packageVersion.set(extension.packageVersion)
            task.applicationDescription.set(extension.description)
            task.vendor.set(extension.vendor)
            task.nsisExecutable.set(extension.windowsNsisExecutable)
            task.distributionDirectory.set(distributionDirectory)
            task.outputFile.set(
                extension.distributionDirectory.file(
                    extension.applicationName.zip(extension.packageVersion) { app, version ->
                        app.fileSafe() + "-" + version + "-windows-x86_64-installer.exe"
                    }
                )
            )
        }
    tasks.register("packageWindowsX64ReleaseInstaller") { task ->
        task.group = "distribution"
        task.description = "Builds the Windows x64 release installer executable."
        task.dependsOn(installerExe)
    }
}

private fun Project.configureWindowsExecutableRuntimeCopyTasks(
    target: DesktopNativeTarget,
    extension: ComposeNativeApplicationExtension,
    sdl: org.gradle.api.tasks.TaskProvider<PrepareWindowsSdlRuntimeTask>,
    cxxRuntime: org.gradle.api.tasks.TaskProvider<PrepareWindowsCxxRuntimeTask>,
    icu: FileCollection,
) {
    val binarySourceSetPrefix = target.sourceSetPrefix
    val binaryTaskSuffix = target.taskSuffix
    listOf("debug", "release").forEach { buildType ->
        val capitalizedBuildType = buildType.replaceFirstChar(Char::uppercaseChar)
        val linkTaskName = "link${capitalizedBuildType}Executable$binaryTaskSuffix"
        val linkTask = tasks.findByName(linkTaskName) ?: return@forEach
        cxxRuntime.configure { runtimeTask -> runtimeTask.mustRunAfter(linkTask) }
        val copyTaskName = "copy${capitalizedBuildType}${binaryTaskSuffix}ExecutableRuntime"
        val copyTask =
            tasks.register(copyTaskName, Copy::class.java) { task ->
                task.group = "build"
                task.description =
                    "Stages the ${target.displayName} $buildType executable runtime files."
                task.dependsOn(linkTask)
                task.dependsOn(cxxRuntime)
                task.from(extension.windowsX64RuntimeFiles)
                task.from(cxxRuntime.flatMap { it.outputDirectory })
                if (extension.bundleSdl.get()) {
                    task.from(sdl.map { it.outputDirectory.file("SDL3.dll") })
                }
                task.from(icu)
                task.into(
                    layout.buildDirectory.dir("bin/$binarySourceSetPrefix/${buildType}Executable")
                )
                task.rename { fileName ->
                    if (
                        fileName.endsWith(".dat", ignoreCase = true) &&
                            fileName.contains("icudtl", ignoreCase = true)
                    ) {
                        "icudtl.dat"
                    } else {
                        fileName
                    }
                }
            }
        tasks
            .findByName("run${capitalizedBuildType}Executable$binaryTaskSuffix")
            ?.dependsOn(copyTask)
    }
}

private fun Project.configureWindowsSdlLinker(
    target: DesktopNativeTarget,
    sdl: org.gradle.api.tasks.TaskProvider<PrepareWindowsSdlRuntimeTask>,
) {
    val binarySourceSetPrefix = target.sourceSetPrefix
    val binaryTaskSuffix = target.taskSuffix
    val libraryDirectory = sdl.get().outputDirectory.get().asFile
    val targets = kotlinNativeTargets()
    val nativeTarget = targets.getByName(binarySourceSetPrefix)
    (nativeTarget.nativeBinaries() as Iterable<*>).filterNotNull().forEach { binary ->
        binary.addNativeLinkerOptions(listOf("-L${libraryDirectory.absolutePath}"))
    }
    listOf("Debug", "Release").forEach { buildType ->
        tasks.findByName("link${buildType}Executable$binaryTaskSuffix")?.dependsOn(sdl)
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
