package dev.brahmkshatriya.compose

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection

internal fun Project.configureNativeLibraryConventions(
    extension: ComposeNativeApplicationExtension
) {
    val host = hostDesktopNativeTarget(System.getProperty("os.name"), System.getProperty("os.arch"))
    listOf("linuxX64", "linuxArm64", "macosX64", "macosArm64").forEach { target ->
        extension
            .libraryDirectories(target)
            .from(
                providers.provider {
                    desktopNativeLibraryDirectories(target, host?.factoryMethodName) {
                            providers.environmentVariable(it).orNull
                        }
                        .map(::file)
                }
            )
    }
    extension.linuxX64RuntimeFiles.from(
        providers
            .environmentVariable("LINUX_X64_RUNTIME_DIR")
            .map { listOf(file(it)) }
            .orElse(emptyList())
    )
    extension.linuxArm64RuntimeFiles.from(
        providers
            .environmentVariable("LINUX_ARM64_RUNTIME_DIR")
            .map { listOf(file(it)) }
            .orElse(emptyList())
    )
}

internal fun ComposeNativeApplicationExtension.libraryDirectories(
    target: String
): ConfigurableFileCollection =
    when (target) {
        "linuxX64" -> linuxX64LibraryDirectories
        "linuxArm64" -> linuxArm64LibraryDirectories
        "macosX64" -> macosX64LibraryDirectories
        "macosArm64" -> macosArm64LibraryDirectories
        else -> error("Unsupported desktop library target: $target")
    }

/** Unqualified environment overrides describe the host, never a different target architecture. */
internal fun desktopNativeLibraryDirectories(
    target: String,
    hostTarget: String?,
    environment: (String) -> String?,
): List<String> {
    val prefix =
        when (target) {
            "linuxX64" -> "LINUX_X64"
            "linuxArm64" -> "LINUX_ARM64"
            "macosX64" -> "MACOS_X64"
            "macosArm64" -> "MACOS_ARM64"
            else -> error("Unsupported desktop library target: $target")
        }
    fun paths(variable: String) =
        environment(variable)?.split(File.pathSeparatorChar)?.filter(String::isNotBlank).orEmpty()
    return buildList {
            addAll(paths("${prefix}_NATIVE_LIBRARY_DIRS"))
            if (target == hostTarget) {
                addAll(paths("SDL3_LIBRARY_DIR"))
                if (target.startsWith("linux")) addAll(paths("LINUX_NATIVE_LIBRARY_DIRS"))
            }
            when (target) {
                "linuxX64" ->
                    addAll(
                        listOf(
                            "/usr/lib/x86_64-linux-gnu",
                            "/lib/x86_64-linux-gnu",
                            "/usr/x86_64-linux-gnu/lib",
                        )
                    )
                "linuxArm64" ->
                    addAll(
                        listOf(
                            "/usr/lib/aarch64-linux-gnu",
                            "/lib/aarch64-linux-gnu",
                            "/usr/aarch64-linux-gnu/lib",
                        )
                    )
                "macosX64" -> addAll(listOf("/usr/local/opt/sdl3/lib", "/usr/local/lib"))
                "macosArm64" -> addAll(listOf("/opt/homebrew/opt/sdl3/lib", "/opt/homebrew/lib"))
            }
            if (target == hostTarget && target.startsWith("linux")) {
                addAll(listOf("/usr/local/lib", "/usr/lib64", "/usr/lib", "/lib64", "/lib"))
            }
        }
        .distinct()
}

internal fun Project.configureDesktopNativeLibraries(targetName: String) {
    if (targetName == "mingwX64") return // Windows SDL is supplied by prepareWindowsX64SdlRuntime.
    val extension = extensions.getByType(ComposeNativeApplicationExtension::class.java)
    val options =
        extension.libraryDirectories(targetName).files.map { "-L${it.absolutePath}" } +
            if (targetName.startsWith("linux")) listOf("-lEGL") else listOf("-lSDL3")
    val targets = kotlinNativeTargets()
    val target = targets.getByName(targetName)
    (target.nativeBinaries() as Iterable<*>)
        .filterNotNull()
        .filter { it.isNativeExecutable() }
        .forEach { binary -> binary.addNativeLinkerOptions(options) }
}

/** Reject host SDL libraries when packaging a cross-compiled executable. */
internal fun findLinuxSdlRuntime(directories: Iterable<File>, architecture: String): File? =
    directories
        .asSequence()
        .flatMap { directory ->
            val preferred =
                listOf(directory.resolve("libSDL3.so.0"), directory.resolve("libSDL3.so"))
            (preferred +
                    directory
                        .listFiles()
                        .orEmpty()
                        .filter { it.name.startsWith("libSDL3.so.") }
                        .sortedBy { it.name })
                .asSequence()
        }
        .firstOrNull { it.isFile && it.isLinuxLibraryFor(architecture) }

private fun File.isLinuxLibraryFor(architecture: String): Boolean {
    val header = inputStream().use { it.readNBytes(20) }
    if (
        header.size < 20 ||
            header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() ||
            header[5] != 1.toByte()
    )
        return false
    val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
    return machine ==
        when (architecture) {
            "x86_64" -> 62
            "aarch64" -> 183
            else -> return false
        }
}

internal fun linuxStripToolNames(architecture: String, hostMatches: Boolean): List<String> =
    if (hostMatches) listOf("strip", "llvm-strip")
    else
        listOf(
            "${if (architecture == "aarch64") "aarch64" else "x86_64"}-linux-gnu-strip",
            "llvm-strip",
        )
