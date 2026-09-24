package dev.brahmkshatriya.compose

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
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
import org.gradle.api.tasks.Sync
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
    val macosX64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()
    val macosArm64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()
    val windowsX64RuntimeFiles: ConfigurableFileCollection = objects.fileCollection()

    val windowsSdlVersion: Property<String> = objects.property(String::class.java)
    val windowsSdlSha256: Property<String> = objects.property(String::class.java)
    val windowsWixExecutable: Property<String> = objects.property(String::class.java)
    val windowsNsisExecutable: Property<String> = objects.property(String::class.java)
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

@DisableCachingByDefault(because = "Invokes WiX to build a Windows Installer package")
abstract class PackageWindowsMsiTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val applicationDescription: Property<String>
    @get:Input abstract val vendor: Property<String>

    @get:Input
    @get:Optional
    abstract val wixExecutable: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionDirectory: DirectoryProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageMsi() {
        val tool = resolveWindowsWixTool(wixExecutable.orNull)
        val source = temporaryDir.resolve("installer.wxs")
        source.writeText(
            windowsMsiSource(
                modernWix = tool.modern,
                applicationName = applicationName.get(),
                packageName = packageName.get(),
                packageVersion = packageVersion.get(),
                description = applicationDescription.get(),
                vendor = vendor.orNull.orEmpty(),
                distributionDirectory = distributionDirectory.get().asFile,
            )
        )

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.delete()

        if (tool.modern) {
            execOperations.exec { spec ->
                spec.executable(tool.wix!!)
                spec.args(
                    "build",
                    source.absolutePath,
                    "-arch",
                    "x64",
                    "-out",
                    output.absolutePath,
                )
            }
        } else {
            val objectFile = temporaryDir.resolve("installer.wixobj")
            objectFile.delete()
            execOperations.exec { spec ->
                spec.executable(tool.candle!!)
                spec.args(
                    "-nologo",
                    "-arch",
                    "x64",
                    "-out",
                    objectFile.absolutePath,
                    source.absolutePath,
                )
            }
            execOperations.exec { spec ->
                spec.executable(tool.light!!)
                spec.args(
                    "-nologo",
                    "-out",
                    output.absolutePath,
                    objectFile.absolutePath,
                )
            }
        }
    }
}

@DisableCachingByDefault(because = "Invokes NSIS to build a Windows installer executable")
abstract class PackageWindowsInstallerExeTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val executableName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val applicationDescription: Property<String>
    @get:Input abstract val vendor: Property<String>

    @get:Input
    @get:Optional
    abstract val nsisExecutable: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionDirectory: DirectoryProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageInstaller() {
        val makensis = resolveWindowsNsisTool(nsisExecutable.orNull)
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.delete()

        val source = temporaryDir.resolve("installer.nsi")
        source.writeText(
            windowsNsisSource(
                applicationName = applicationName.get(),
                packageName = packageName.get(),
                executableName = executableName.get(),
                packageVersion = packageVersion.get(),
                description = applicationDescription.get(),
                vendor = vendor.orNull.orEmpty(),
                distributionDirectory = distributionDirectory.get().asFile,
                outputFile = output,
            )
        )
        execOperations.exec { spec ->
            spec.executable(makensis)
            spec.args(source.absolutePath)
        }
    }
}

@DisableCachingByDefault(
    because = "Assembles and rewrites a platform-specific macOS application bundle"
)
abstract class PrepareMacosAppBundleTask : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val executableName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val applicationDescription: Property<String>
    @get:Input abstract val vendor: Property<String>
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

    @get:OutputDirectory abstract val appBundle: DirectoryProperty

    @TaskAction
    fun prepare() {
        checkMacosHost()
        val app = appBundle.get().asFile
        app.deleteRecursively()
        val contents = app.resolve("Contents")
        val macos = contents.resolve("MacOS").apply(File::mkdirs)
        val resources = contents.resolve("Resources").apply(File::mkdirs)
        val frameworks = contents.resolve("Frameworks").apply(File::mkdirs)

        val executable = macos.resolve(executableName.get())
        releaseExecutable.get().asFile.copyTo(executable, overwrite = true)
        executable.setExecutable(true, false)

        copyRuntimeFiles(runtimeFiles.files, frameworks)
        bundleMacosSdlIfNeeded(executable, frameworks)
        rewriteBundledMacosDylibs(executable, frameworks)

        if (resourceDirectory.isPresent) {
            val source = resourceDirectory.get().asFile
            if (source.isDirectory) {
                source.copyRecursively(resources.resolve("compose-resources"), overwrite = true)
            }
        }

        val iconName =
            if (iconFile.isPresent) {
                val source = iconFile.get().asFile
                val name = "AppIcon.${source.extension.ifBlank { "icns" }}"
                source.copyTo(resources.resolve(name), overwrite = true)
                name.takeIf { source.extension.equals("icns", ignoreCase = true) }
            } else null

        contents.resolve("Info.plist").writeText(
            macosInfoPlist(
                applicationName.get(), packageName.get(), executableName.get(), packageVersion.get(),
                applicationDescription.get(), vendor.orNull.orEmpty(), iconName,
            )
        )
        contents.resolve("PkgInfo").writeText("APPL????")
        adHocSignMacosBundle(app)
    }

    private fun bundleMacosSdlIfNeeded(executable: File, frameworks: File) {
        if (!bundleSdl.get() || frameworks.containsSdl3Dylib()) return
        val dependency = macosDylibDependencies(executable).firstOrNull {
            File(it).name.contains("SDL3", ignoreCase = true)
        } ?: throw GradleException(
            "Could not locate the SDL 3 dependency of ${releaseExecutable.get().asFile}. " +
                "Add it to composeNativeApplication.${runtimePropertyName()}."
        )
        val source = resolveMacosRuntimeDependency(dependency, runtimeFiles.files)
            ?: throw GradleException(
                "Could not locate $dependency. Add the SDL 3 dylib to " +
                    "composeNativeApplication.${runtimePropertyName()}."
            )
        source.copyTo(frameworks.resolve(source.name), overwrite = true)
    }

    private fun runtimePropertyName(): String =
        if (targetArchitecture.get() == "arm64") "macosArm64RuntimeFiles"
        else "macosX64RuntimeFiles"
}

@DisableCachingByDefault(because = "Invokes hdiutil to create a macOS disk image")
abstract class PackageMacosDmgTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appBundle: DirectoryProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageDmg() {
        checkMacosHost()
        val hdiutil =
            findOnPath("hdiutil") ?: throw GradleException("hdiutil was not found on PATH")
        val source = appBundle.get().asFile
        val staging = temporaryDir.resolve("dmg-root")
        staging.deleteRecursively()
        staging.mkdirs()
        source.copyRecursively(staging.resolve(source.name), overwrite = true)

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.delete()
        execOperations.exec { spec ->
            spec.executable(hdiutil)
            spec.args(
                "create",
                "-volname",
                applicationName.get(),
                "-srcfolder",
                staging.absolutePath,
                "-ov",
                "-format",
                "UDZO",
                output.absolutePath,
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
            windowsWixExecutable.convention(providers.environmentVariable("COMPOSE_WINDOWS_WIX"))
            windowsNsisExecutable.convention(providers.environmentVariable("COMPOSE_WINDOWS_NSIS"))
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
        executableTargets
            .filter { it.platform == NativeApplicationPlatform.MACOS }
            .forEach(::configureMacosPackaging)
        configureSingleLinuxPackagingAliases(executableTargets)
        configureSingleMacosPackagingAliases(executableTargets)
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
        val mergedDirectory =
            layout.buildDirectory.dir("generated/composeNative/desktopNativeMain/composeResources")
        val prepareResources =
            tasks.register("prepareDesktopNativeComposeResources", Sync::class.java) { task ->
                task.from(layout.projectDirectory.dir("src/main/composeResources"))
                task.from(layout.projectDirectory.dir("src/desktopNativeMain/composeResources"))
                task.into(mergedDirectory)
                task.includeEmptyDirs = false
                task.duplicatesStrategy = DuplicatesStrategy.FAIL
            }
        val compose = extensions.getByName("compose") as ExtensionAware
        val resources = compose.extensions.getByName("resources")
        val customDirectory =
            resources.javaClass.methods.first {
                it.name == "customDirectory" && it.parameterCount == 2
            }
        customDirectory.invoke(
            resources,
            "desktopNativeMain",
            prepareResources.map { mergedDirectory.get() },
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

private fun Project.configureMacosPackaging(target: DesktopNativeApplicationTarget) {
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val releaseExecutable =
        layout.buildDirectory.file(
            extension.binaryName.map { binary ->
                "bin/${target.sourceSetPrefix}/releaseExecutable/$binary.kexe"
            }
        )
    val resourceDirectory =
        layout.buildDirectory.dir("bin/${target.sourceSetPrefix}/releaseExecutable/resources")
    val appBundle =
        extension.distributionDirectory.dir(
            extension.applicationName.zip(extension.packageVersion) { app, version ->
                "${app.fileSafe()}-$version-macos-${target.packageArchitecture}.app"
            }
        )
    val runtimeFiles =
        if (target.sourceSetPrefix == "macosArm64") extension.macosArm64RuntimeFiles
        else extension.macosX64RuntimeFiles
    val linkTaskName = "linkReleaseExecutable${target.taskSuffix}"
    val copyTaskName = "copyRelease${target.taskSuffix}ExecutableResources"
    val prepare =
        tasks.register(
            "prepare${target.taskSuffix}ReleaseAppBundle",
            PrepareMacosAppBundleTask::class.java,
        ) { task ->
            task.group = "distribution"
            task.description = "Assembles the ${target.displayName} release .app bundle."
            task.dependsOn(linkTaskName)
            if (tasks.findByName(copyTaskName) != null) task.dependsOn(copyTaskName)
            task.applicationName.set(extension.applicationName)
            task.packageName.set(extension.packageName)
            task.executableName.set(extension.executableName)
            task.packageVersion.set(extension.packageVersion)
            task.applicationDescription.set(extension.description)
            task.vendor.set(extension.vendor)
            task.bundleSdl.set(extension.bundleSdl)
            task.targetArchitecture.set(target.packageArchitecture)
            task.releaseExecutable.set(releaseExecutable)
            if (tasks.findByName(copyTaskName) != null)
                task.resourceDirectory.set(resourceDirectory)
            task.iconFile.set(extension.iconFile)
            task.runtimeFiles.from(runtimeFiles)
            task.appBundle.set(appBundle)
        }
    tasks.register(
        "package${target.taskSuffix}ReleaseDmg",
        PackageMacosDmgTask::class.java,
    ) { task ->
        task.group = "distribution"
        task.description = "Builds the ${target.displayName} release DMG."
        task.dependsOn(prepare)
        task.applicationName.set(extension.applicationName)
        task.appBundle.set(appBundle)
        task.outputFile.set(
            extension.distributionDirectory.file(
                extension.applicationName.zip(extension.packageVersion) { app, version ->
                    "${app.fileSafe()}-$version-macos-${target.packageArchitecture}.dmg"
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
    configureWindowsExecutableRuntimeCopyTasks(target, extension, sdl, icu)
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
    target: DesktopNativeApplicationTarget,
    extension: ComposeNativeApplicationExtension,
    sdl: org.gradle.api.tasks.TaskProvider<PrepareWindowsSdlRuntimeTask>,
    icu: FileCollection,
) {
    listOf("debug", "release").forEach { buildType ->
        val capitalizedBuildType = buildType.replaceFirstChar(Char::uppercaseChar)
        val linkTaskName = "link${capitalizedBuildType}Executable${target.taskSuffix}"
        val linkTask = tasks.findByName(linkTaskName) ?: return@forEach
        val copyTaskName = "copy${capitalizedBuildType}${target.taskSuffix}ExecutableRuntime"
        val copyTask =
            tasks.register(copyTaskName, Copy::class.java) { task ->
                task.group = "build"
                task.description =
                    "Stages the ${target.displayName} $buildType executable runtime files."
                task.dependsOn(linkTask)
                task.from(extension.windowsX64RuntimeFiles)
                if (extension.bundleSdl.get()) {
                    task.from(sdl.map { it.outputDirectory.file("SDL3.dll") })
                }
                task.from(icu)
                task.into(
                    layout.buildDirectory.dir(
                        "bin/${target.sourceSetPrefix}/${buildType}Executable"
                    )
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
            .findByName("run${capitalizedBuildType}Executable${target.taskSuffix}")
            ?.dependsOn(copyTask)
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



private data class WindowsWixTool(
    val wix: String? = null,
    val candle: String? = null,
    val light: String? = null,
) {
    val modern: Boolean
        get() = wix != null
}

private fun resolveWindowsWixTool(configured: String?): WindowsWixTool {
    fun resolve(candidate: String): WindowsWixTool? {
        val file = File(candidate)
        if (file.isFile) {
            val name = file.name.lowercase()
            if (name == "wix" || name == "wix.exe") return WindowsWixTool(wix = file.absolutePath)
            if (name == "candle" || name == "candle.exe") {
                val lightName = if (name.endsWith(".exe")) "light.exe" else "light"
                val light = file.parentFile.resolve(lightName)
                if (light.isFile) {
                    return WindowsWixTool(candle = file.absolutePath, light = light.absolutePath)
                }
            }
            return null
        }
        if (!file.isDirectory) return null
        listOf(file, file.resolve("bin")).forEach { dir ->
            listOf("wix.exe", "wix").forEach { name ->
                val wix = dir.resolve(name)
                if (wix.isFile) return WindowsWixTool(wix = wix.absolutePath)
            }
            val candle = dir.resolve("candle.exe")
            val light = dir.resolve("light.exe")
            if (candle.isFile && light.isFile) {
                return WindowsWixTool(candle = candle.absolutePath, light = light.absolutePath)
            }
        }
        return null
    }

    listOfNotNull(
        configured?.takeIf(String::isNotBlank),
        System.getenv("COMPOSE_WINDOWS_WIX")?.takeIf(String::isNotBlank),
        System.getenv("WIX_EXECUTABLE")?.takeIf(String::isNotBlank),
        System.getenv("WIX")?.takeIf(String::isNotBlank),
    ).forEach { candidate ->
        resolve(candidate)?.let { return it }
    }

    listOf("wix.exe", "wix").forEach { name ->
        findOnPath(name)?.let { return WindowsWixTool(wix = it) }
    }
    val candle = findOnPath("candle.exe") ?: findOnPath("candle")
    val light = findOnPath("light.exe") ?: findOnPath("light")
    if (candle != null && light != null) {
        return WindowsWixTool(candle = candle, light = light)
    }

    commonWindowsToolRoots().forEach { root ->
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("WiX Toolset", ignoreCase = true) }
            .forEach { dir -> resolve(dir.absolutePath)?.let { return it } }
    }

    throw GradleException(
        "WiX was not found. Install WiX 4+ (wix) or WiX 3 (candle/light), " +
            "or set composeNativeApplication.windowsWixExecutable / COMPOSE_WINDOWS_WIX."
    )
}

private fun resolveWindowsNsisTool(configured: String?): String {
    val candidates =
        listOfNotNull(
            configured?.takeIf(String::isNotBlank),
            System.getenv("COMPOSE_WINDOWS_NSIS")?.takeIf(String::isNotBlank),
            System.getenv("MAKENSIS")?.takeIf(String::isNotBlank),
            System.getenv("NSIS_HOME")?.takeIf(String::isNotBlank),
        )
    candidates.forEach { candidate ->
        val file = File(candidate)
        if (file.isFile) return file.absolutePath
        if (file.isDirectory) {
            listOf("makensis.exe", "makensis").forEach { name ->
                val tool = file.resolve(name)
                if (tool.isFile) return tool.absolutePath
            }
        }
    }
    findOnPath("makensis.exe")?.let { return it }
    findOnPath("makensis")?.let { return it }
    commonWindowsToolRoots().forEach { root ->
        val tool = root.resolve("NSIS/makensis.exe")
        if (tool.isFile) return tool.absolutePath
    }
    throw GradleException(
        "NSIS makensis was not found. Install NSIS or set " +
            "composeNativeApplication.windowsNsisExecutable / COMPOSE_WINDOWS_NSIS."
    )
}

private fun commonWindowsToolRoots(): List<File> =
    listOf("ProgramFiles", "ProgramFiles(x86)")
        .mapNotNull(System::getenv)
        .map(::File)
        .filter(File::isDirectory)

internal fun windowsMsiSource(
    modernWix: Boolean,
    applicationName: String,
    packageName: String,
    packageVersion: String,
    description: String,
    vendor: String,
    distributionDirectory: File,
): String {
    val files =
        distributionDirectory.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(distributionDirectory).invariantSeparatorsPath }
            .toList()
    check(files.isNotEmpty()) { "Windows distribution is empty: " + distributionDirectory }

    val manufacturer = vendor.ifBlank { applicationName }
    val version = windowsMsiVersion(packageVersion)
    val upgradeCode =
        UUID.nameUUIDFromBytes(packageName.toByteArray(Charsets.UTF_8)).toString().uppercase()

    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    if (modernWix) {
        sb.append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\">\n")
        sb.append("  <Package Name=\"").append(xmlEscape(applicationName)).append("\"")
        sb.append(" Manufacturer=\"").append(xmlEscape(manufacturer)).append("\"")
        sb.append(" Version=\"").append(xmlEscape(version)).append("\"")
        sb.append(" UpgradeCode=\"").append(upgradeCode).append("\"")
        sb.append(" Language=\"1033\" InstallerVersion=\"500\" Scope=\"perMachine\">\n")
        sb.append("    <MajorUpgrade DowngradeErrorMessage=\"A newer version is already installed.\" />\n")
        sb.append("    <MediaTemplate EmbedCab=\"yes\" />\n")
        if (description.isNotBlank()) {
            sb.append("    <Property Id=\"ARPCOMMENTS\" Value=\"")
                .append(xmlEscape(description)).append("\" />\n")
        }
        sb.append("    <StandardDirectory Id=\"ProgramFiles64Folder\">\n")
        sb.append("      <Directory Id=\"INSTALLFOLDER\" Name=\"")
            .append(xmlEscape(applicationName.fileSafe())).append("\">\n")
        appendWixDirectoryContents(sb, distributionDirectory, distributionDirectory, files, "        ")
        sb.append("      </Directory>\n")
        sb.append("    </StandardDirectory>\n")
    } else {
        sb.append("<Wix xmlns=\"http://schemas.microsoft.com/wix/2006/wi\">\n")
        sb.append("  <Product Id=\"*\" Name=\"").append(xmlEscape(applicationName)).append("\"")
        sb.append(" Language=\"1033\" Version=\"").append(xmlEscape(version)).append("\"")
        sb.append(" Manufacturer=\"").append(xmlEscape(manufacturer)).append("\"")
        sb.append(" UpgradeCode=\"").append(upgradeCode).append("\">\n")
        sb.append("    <Package InstallerVersion=\"500\" Compressed=\"yes\"")
            .append(" InstallScope=\"perMachine\" Platform=\"x64\" />\n")
        sb.append("    <MajorUpgrade DowngradeErrorMessage=\"A newer version is already installed.\" />\n")
        sb.append("    <MediaTemplate EmbedCab=\"yes\" />\n")
        if (description.isNotBlank()) {
            sb.append("    <Property Id=\"ARPCOMMENTS\" Value=\"")
                .append(xmlEscape(description)).append("\" />\n")
        }
        sb.append("    <Directory Id=\"TARGETDIR\" Name=\"SourceDir\">\n")
        sb.append("      <Directory Id=\"ProgramFiles64Folder\">\n")
        sb.append("        <Directory Id=\"INSTALLFOLDER\" Name=\"")
            .append(xmlEscape(applicationName.fileSafe())).append("\">\n")
        appendWixDirectoryContents(sb, distributionDirectory, distributionDirectory, files, "          ")
        sb.append("        </Directory>\n")
        sb.append("      </Directory>\n")
        sb.append("    </Directory>\n")
    }

    sb.append("    <Feature Id=\"MainFeature\" Title=\"")
        .append(xmlEscape(applicationName)).append("\" Level=\"1\">\n")
    files.forEach { file ->
        val relative = file.relativeTo(distributionDirectory).invariantSeparatorsPath
        sb.append("      <ComponentRef Id=\"cmp_")
            .append(stableInstallerId(relative)).append("\" />\n")
    }
    sb.append("    </Feature>\n")
    sb.append(if (modernWix) "  </Package>\n" else "  </Product>\n")
    sb.append("</Wix>\n")
    return sb.toString()
}

private fun appendWixDirectoryContents(
    sb: StringBuilder,
    root: File,
    directory: File,
    files: List<File>,
    indent: String,
) {
    files.filter { it.parentFile == directory }.forEach { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val id = stableInstallerId(relative)
        sb.append(indent).append("<Component Id=\"cmp_").append(id).append("\" Guid=\"*\">\n")
        sb.append(indent).append("  <File Id=\"fil_").append(id).append("\" Source=\"")
            .append(xmlEscape(file.absolutePath)).append("\" KeyPath=\"yes\" />\n")
        sb.append(indent).append("</Component>\n")
    }
    directory.listFiles().orEmpty()
        .filter(File::isDirectory)
        .sortedBy(File::getName)
        .forEach { child ->
            val relative = child.relativeTo(root).invariantSeparatorsPath
            sb.append(indent).append("<Directory Id=\"dir_")
                .append(stableInstallerId(relative)).append("\" Name=\"")
                .append(xmlEscape(child.name)).append("\">\n")
            appendWixDirectoryContents(sb, root, child, files, indent + "  ")
            sb.append(indent).append("</Directory>\n")
        }
}

internal fun windowsNsisSource(
    applicationName: String,
    packageName: String,
    executableName: String,
    packageVersion: String,
    description: String,
    vendor: String,
    distributionDirectory: File,
    outputFile: File,
): String {
    val app = nsisEscape(applicationName)
    val manufacturer = nsisEscape(vendor.ifBlank { applicationName })
    val uninstallKey =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + nsisEscape(packageName)
    val sb = StringBuilder()
    sb.append("Unicode true\n")
    sb.append("Name \"").append(app).append("\"\n")
    sb.append("OutFile \"").append(nsisEscape(outputFile.absolutePath)).append("\"\n")
    sb.append("InstallDir \"\$PROGRAMFILES64\\").append(nsisEscape(applicationName.fileSafe())).append("\"\n")
    sb.append("InstallDirRegKey HKLM \"").append(uninstallKey).append("\" \"InstallLocation\"\n")
    sb.append("RequestExecutionLevel admin\n")
    sb.append("SetCompressor /SOLID lzma\n")
    sb.append("VIProductVersion \"").append(windowsNsisVersion(packageVersion)).append("\"\n")
    sb.append("VIAddVersionKey \"ProductName\" \"").append(app).append("\"\n")
    sb.append("VIAddVersionKey \"CompanyName\" \"").append(manufacturer).append("\"\n")
    sb.append("VIAddVersionKey \"FileDescription\" \"")
        .append(nsisEscape(description.ifBlank { applicationName })).append("\"\n")
    sb.append("VIAddVersionKey \"FileVersion\" \"").append(nsisEscape(packageVersion)).append("\"\n\n")
    sb.append("VIAddVersionKey \"LegalCopyright\" \"").append(manufacturer).append("\"\n\n")
    sb.append("Page directory\nPage instfiles\nUninstPage uninstConfirm\nUninstPage instfiles\n\n")
    sb.append("Section \"Install\"\n")
    sb.append("  SetShellVarContext all\n")
    sb.append("  SetRegView 64\n")
    sb.append("  SetOutPath \"\$INSTDIR\"\n")
    sb.append("  File /r \"").append(nsisEscape(distributionDirectory.absolutePath)).append("\\*\"\n")
    sb.append("  WriteUninstaller \"\$INSTDIR\\Uninstall.exe\"\n")
    sb.append("  CreateDirectory \"\$SMPROGRAMS\\").append(app).append("\"\n")
    sb.append("  CreateShortCut \"\$SMPROGRAMS\\").append(app).append("\\").append(app)
        .append(".lnk\" \"\$INSTDIR\\").append(nsisEscape(executableName)).append(".exe\"\n")
    sb.append("  WriteRegStr HKLM \"").append(uninstallKey).append("\" \"DisplayName\" \"").append(app).append("\"\n")
    sb.append("  WriteRegStr HKLM \"").append(uninstallKey).append("\" \"DisplayVersion\" \"")
        .append(nsisEscape(packageVersion)).append("\"\n")
    sb.append("  WriteRegStr HKLM \"").append(uninstallKey).append("\" \"Publisher\" \"").append(manufacturer).append("\"\n")
    sb.append("  WriteRegStr HKLM \"").append(uninstallKey).append("\" \"InstallLocation\" \"\$INSTDIR\"\n")
    sb.append("  WriteRegStr HKLM \"").append(uninstallKey)
        .append("\" \"UninstallString\" '\"\$INSTDIR\\Uninstall.exe\"'\n")
    sb.append("  WriteRegDWORD HKLM \"").append(uninstallKey).append("\" \"NoModify\" 1\n")
    sb.append("  WriteRegDWORD HKLM \"").append(uninstallKey).append("\" \"NoRepair\" 1\n")
    sb.append("SectionEnd\n\n")
    sb.append("Section \"Uninstall\"\n")
    sb.append("  SetShellVarContext all\n")
    sb.append("  SetRegView 64\n")
    sb.append("  Delete \"\$SMPROGRAMS\\").append(app).append("\\").append(app).append(".lnk\"\n")
    sb.append("  RMDir \"\$SMPROGRAMS\\").append(app).append("\"\n")
    sb.append("  DeleteRegKey HKLM \"").append(uninstallKey).append("\"\n")
    sb.append("  RMDir /r \"\$INSTDIR\"\n")
    sb.append("SectionEnd\n")
    return sb.toString()
}

internal fun windowsMsiVersion(version: String): String {
    val parts =
        version.substringBefore('-').split('.').take(3).map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }.toMutableList()
    while (parts.size < 3) parts += 0
    require(parts[0] in 0..255 && parts[1] in 0..255 && parts[2] in 0..65535) {
        "Windows MSI version '" + version + "' is outside MSI's 255.255.65535 version range"
    }
    return parts.joinToString(".")
}

internal fun windowsNsisVersion(version: String): String {
    val parts = Regex("\\d+").findAll(version).map { it.value.toInt() }.take(4).toMutableList()
    while (parts.size < 4) parts += 0
    require(parts.all { it in 0..65535 }) {
        "Windows installer version '" + version + "' contains a component outside 0..65535"
    }
    return parts.joinToString(".")
}

private fun stableInstallerId(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

private fun nsisEscape(value: String): String =
    value.replace("$", "$$")
        .replace("\"", "$\\\"")
        .replace("\r", " ")
        .replace("\n", " ")


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

private fun Project.configureSingleMacosPackagingAliases(
    targets: List<DesktopNativeApplicationTarget>
) {
    val macosTargets = targets.filter { it.platform == NativeApplicationPlatform.MACOS }
    if (macosTargets.size != 1) return
    val target = macosTargets.single()
    tasks.register("prepareMacosReleaseAppBundle") { task ->
        task.group = "distribution"
        task.dependsOn("prepare${target.taskSuffix}ReleaseAppBundle")
    }
    tasks.register("packageReleaseDmg") { task ->
        task.group = "distribution"
        task.dependsOn("package${target.taskSuffix}ReleaseDmg")
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

private fun File.containsSdl3Dylib(): Boolean =
    listFiles().orEmpty().any {
        it.isFile && it.extension.equals("dylib", ignoreCase = true) &&
            it.name.contains("SDL3", ignoreCase = true)
    }

private fun checkMacosHost() {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        throw GradleException("macOS application packaging must run on a macOS host")
    }
}

private fun macosDylibDependencies(file: File): List<String> {
    val otool = findOnPath("otool") ?: throw GradleException("otool was not found on PATH")
    val process =
        ProcessBuilder(otool, "-L", file.absolutePath).redirectErrorStream(true).start()
    val lines = process.inputStream.bufferedReader().readLines()
    if (process.waitFor() != 0) {
        throw GradleException("otool -L failed for ${file.absolutePath}: ${lines.joinToString("\\n")}")
    }
    return lines.drop(1).mapNotNull { line ->
        line.trim()
            .substringBefore(" (compatibility version")
            .substringBefore(" (current version")
            .takeIf(String::isNotBlank)
    }
}

private fun resolveMacosRuntimeDependency(dependency: String, configured: Set<File>): File? {
    val name = File(dependency).name
    fun matches(file: File): Boolean =
        file.isFile &&
            (file.name == name ||
                (name.contains("SDL3", ignoreCase = true) &&
                    file.name.contains("SDL3", ignoreCase = true)))
    val configuredMatch =
        configured.asSequence()
            .flatMap { file ->
                if (file.isDirectory) file.listFiles().orEmpty().asSequence() else sequenceOf(file)
            }
            .firstOrNull(::matches)
    if (configuredMatch != null) return configuredMatch

    if (dependency.startsWith('/')) {
        File(dependency).takeIf(File::isFile)?.let { return it }
    }

    listOf("COMPOSE_MACOS_X64_SDL_DYLIB", "COMPOSE_MACOS_ARM64_SDL_DYLIB")
        .mapNotNull(System::getenv)
        .map(::File)
        .firstOrNull(::matches)
        ?.let { return it }

    val searchDirectories = buildList {
        System.getenv("DYLD_LIBRARY_PATH")
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            ?.mapTo(this, ::File)
        add(File("/usr/local/lib"))
        add(File("/opt/homebrew/lib"))
    }
    return searchDirectories.asSequence().map { it.resolve(name) }.firstOrNull(File::isFile)
}

private fun rewriteBundledMacosDylibs(executable: File, frameworks: File) {
    val installNameTool =
        findOnPath("install_name_tool")
            ?: throw GradleException("install_name_tool was not found on PATH")
    val bundled = frameworks.listFiles().orEmpty().filter { it.isFile && it.extension == "dylib" }
    if (bundled.isEmpty()) return
    val bundledByName = bundled.associateBy(File::getName)

    macosDylibDependencies(executable).forEach { dependency ->
        val dependencyName = File(dependency).name
        val dylib =
            bundledByName[dependencyName]
                ?: bundled.firstOrNull {
                    dependencyName.contains("SDL3", ignoreCase = true) &&
                        it.name.contains("SDL3", ignoreCase = true)
                }
                ?: return@forEach
        runCommand(
            installNameTool,
            "-change",
            dependency,
            "@executable_path/../Frameworks/${dylib.name}",
            executable.absolutePath,
        )
    }

    bundled.forEach { dylib ->
        runCommand(installNameTool, "-id", "@rpath/${dylib.name}", dylib.absolutePath)
        macosDylibDependencies(dylib).forEach dependencyLoop@ { dependency ->
            val dependencyFile = bundledByName[File(dependency).name] ?: return@dependencyLoop
            runCommand(
                installNameTool,
                "-change",
                dependency,
                "@loader_path/${dependencyFile.name}",
                dylib.absolutePath,
            )
        }
    }
}

private fun adHocSignMacosBundle(app: File) {
    val codesign = findOnPath("codesign") ?: throw GradleException("codesign was not found on PATH")
    runCommand(
        codesign,
        "--force",
        "--deep",
        "--sign",
        "-",
        "--timestamp=none",
        app.absolutePath,
    )
}

private fun macosInfoPlist(
    applicationName: String,
    packageName: String,
    executableName: String,
    packageVersion: String,
    description: String,
    vendor: String,
    iconName: String?,
): String {
    val shortVersion =
        packageVersion.substringBefore('-').takeIf { it.matches(Regex("\\d+(\\.\\d+){0,2}")) }
            ?: "1.0.0"
    val iconEntry =
        iconName?.let {
            "  <key>CFBundleIconFile</key>\n  <string>${xmlEscape(it)}</string>\n"
        }.orEmpty()
    val copyrightEntry =
        vendor.takeIf(String::isNotBlank)?.let {
            "  <key>NSHumanReadableCopyright</key>\n  <string>${xmlEscape(it)}</string>\n"
        }.orEmpty()
    return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleDisplayName</key>
  <string>${xmlEscape(applicationName)}</string>
  <key>CFBundleExecutable</key>
  <string>${xmlEscape(executableName)}</string>
${iconEntry}  <key>CFBundleIdentifier</key>
  <string>${xmlEscape(packageName)}</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>${xmlEscape(applicationName)}</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${xmlEscape(shortVersion)}</string>
  <key>CFBundleVersion</key>
  <string>${xmlEscape(shortVersion)}</string>
  <key>CFBundleGetInfoString</key>
  <string>${xmlEscape(description)}</string>
${copyrightEntry}  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
"""
}

private fun xmlEscape(value: String): String =
    value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

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
    MACOS,
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
        DesktopNativeApplicationTarget(
            "macosX64",
            "MacosX64",
            NativeApplicationPlatform.MACOS,
            "x86_64",
            "macOS x64",
        ),
        DesktopNativeApplicationTarget(
            "macosArm64",
            "MacosArm64",
            NativeApplicationPlatform.MACOS,
            "arm64",
            "macOS arm64",
        ),
    )

private const val COMPOSE_NATIVE_APPLICATION_EXTENSION_NAME = "composeNativeApplication"
private const val DEFAULT_WINDOWS_SDL_VERSION = "3.4.10"
private const val DEFAULT_WINDOWS_SDL_SHA256 =
    "39dd2ac370bf33d6332a21ed768d8d49c37cc6f3211d788ead765102722639a8"
