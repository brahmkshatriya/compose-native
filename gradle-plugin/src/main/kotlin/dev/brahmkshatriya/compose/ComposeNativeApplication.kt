package dev.brahmkshatriya.compose

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

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

    /** Target library search paths, also used to locate SDL for Linux packaging. */
    val linuxX64LibraryDirectories: ConfigurableFileCollection = objects.fileCollection()
    val linuxArm64LibraryDirectories: ConfigurableFileCollection = objects.fileCollection()
    val macosX64LibraryDirectories: ConfigurableFileCollection = objects.fileCollection()
    val macosArm64LibraryDirectories: ConfigurableFileCollection = objects.fileCollection()

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
            configureNativeLibraryConventions(this)
        }
}

internal fun Project.configureDesktopNativeApplicationConventions() {
    afterEvaluate {
        val executableTargets =
            DESKTOP_NATIVE_TARGETS.filter { target ->
                val binaryTaskSuffix = target.taskSuffix
                tasks.findByName("linkDebugExecutable$binaryTaskSuffix") != null ||
                    tasks.findByName("linkReleaseExecutable$binaryTaskSuffix") != null
            }
        if (executableTargets.isEmpty()) return@afterEvaluate

        addConventionalMainKotlinSources()
        addConventionalMainComposeResources()
        executableTargets.forEach { configureDesktopNativeLibraries(it.sourceSetPrefix) }
        executableTargets.forEach(::configureExecutableResourceCopyTasks)
        executableTargets.forEach { target ->
            when (target.platform) {
                NativeApplicationPlatform.LINUX -> configureLinuxPackaging(target)
                NativeApplicationPlatform.WINDOWS -> configureWindowsPackaging(target)
                NativeApplicationPlatform.MACOS -> configureMacosPackaging(target)
            }
        }
        configureSingleLinuxPackagingAliases(executableTargets)
        configureSingleMacosPackagingAliases(executableTargets)
    }
}

private const val COMPOSE_NATIVE_APPLICATION_EXTENSION_NAME = "composeNativeApplication"
private const val DEFAULT_WINDOWS_SDL_VERSION = "3.4.10"
private const val DEFAULT_WINDOWS_SDL_SHA256 =
    "39dd2ac370bf33d6332a21ed768d8d49c37cc6f3211d788ead765102722639a8"
