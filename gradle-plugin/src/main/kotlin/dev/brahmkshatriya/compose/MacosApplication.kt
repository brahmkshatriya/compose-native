package dev.brahmkshatriya.compose

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
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

        contents
            .resolve("Info.plist")
            .writeText(
                macosInfoPlist(
                    applicationName.get(),
                    packageName.get(),
                    executableName.get(),
                    packageVersion.get(),
                    applicationDescription.get(),
                    vendor.orNull.orEmpty(),
                    iconName,
                )
            )
        contents.resolve("PkgInfo").writeText("APPL????")
        adHocSignMacosBundle(app)
    }

    private fun bundleMacosSdlIfNeeded(executable: File, frameworks: File) {
        if (!bundleSdl.get() || frameworks.containsSdl3Dylib()) return
        val dependency =
            macosDylibDependencies(executable).firstOrNull {
                File(it).name.contains("SDL3", ignoreCase = true)
            }
                ?: throw GradleException(
                    "Could not locate the SDL 3 dependency of ${releaseExecutable.get().asFile}. " +
                        "Add it to composeNativeApplication.${runtimePropertyName()}."
                )
        val source =
            resolveMacosRuntimeDependency(dependency, runtimeFiles.files)
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
abstract class PackageMacosDmgTask @Inject constructor(private val execOperations: ExecOperations) :
    DefaultTask() {
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

internal fun Project.configureMacosPackaging(target: DesktopNativeTarget) {
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
    val appBundle =
        extension.distributionDirectory.dir(
            extension.applicationName.zip(extension.packageVersion) { app, version ->
                "${app.fileSafe()}-$version-macos-${target.packageArchitecture}.app"
            }
        )
    val runtimeFiles =
        if (target.sourceSetPrefix == "macosArm64") extension.macosArm64RuntimeFiles
        else extension.macosX64RuntimeFiles
    val linkTaskName = "linkReleaseExecutable$binaryTaskSuffix"
    val copyTaskName = "copyRelease${binaryTaskSuffix}ExecutableResources"
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
    tasks.register("package${target.taskSuffix}ReleaseDmg", PackageMacosDmgTask::class.java) { task
        ->
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

internal fun Project.configureSingleMacosPackagingAliases(targets: List<DesktopNativeTarget>) {
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

private fun File.containsSdl3Dylib(): Boolean =
    listFiles().orEmpty().any {
        it.isFile &&
            it.extension.equals("dylib", ignoreCase = true) &&
            it.name.contains("SDL3", ignoreCase = true)
    }

private fun checkMacosHost() {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        throw GradleException("macOS application packaging must run on a macOS host")
    }
}

private fun macosDylibDependencies(file: File): List<String> {
    val otool = findOnPath("otool") ?: throw GradleException("otool was not found on PATH")
    val process = ProcessBuilder(otool, "-L", file.absolutePath).redirectErrorStream(true).start()
    val lines = process.inputStream.bufferedReader().readLines()
    if (process.waitFor() != 0) {
        throw GradleException(
            "otool -L failed for ${file.absolutePath}: ${lines.joinToString("\\n")}"
        )
    }
    return lines.drop(1).mapNotNull { line ->
        line
            .trim()
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
        configured
            .asSequence()
            .flatMap { file ->
                if (file.isDirectory) file.listFiles().orEmpty().asSequence() else sequenceOf(file)
            }
            .firstOrNull(::matches)
    if (configuredMatch != null) return configuredMatch

    if (dependency.startsWith('/')) {
        File(dependency).takeIf(File::isFile)?.let {
            return it
        }
    }

    listOf("COMPOSE_MACOS_X64_SDL_DYLIB", "COMPOSE_MACOS_ARM64_SDL_DYLIB")
        .mapNotNull(System::getenv)
        .map(::File)
        .firstOrNull(::matches)
        ?.let {
            return it
        }

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
        macosDylibDependencies(dylib).forEach dependencyLoop@{ dependency ->
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
    runCommand(codesign, "--force", "--deep", "--sign", "-", "--timestamp=none", app.absolutePath)
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
        iconName
            ?.let { "  <key>CFBundleIconFile</key>\n  <string>${xmlEscape(it)}</string>\n" }
            .orEmpty()
    val copyrightEntry =
        vendor
            .takeIf(String::isNotBlank)
            ?.let { "  <key>NSHumanReadableCopyright</key>\n  <string>${xmlEscape(it)}</string>\n" }
            .orEmpty()
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
