package dev.brahmkshatriya.compose

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Invokes WiX to build a Windows Installer package")
abstract class PackageWindowsMsiTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val packageVersion: Property<String>
    @get:Input abstract val applicationDescription: Property<String>
    @get:Input abstract val vendor: Property<String>

    @get:Input @get:Optional abstract val wixExecutable: Property<String>

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
                spec.args("build", source.absolutePath, "-arch", "x64", "-out", output.absolutePath)
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
                spec.args("-nologo", "-out", output.absolutePath, objectFile.absolutePath)
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

    @get:Input @get:Optional abstract val nsisExecutable: Property<String>

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
        )
        .forEach { candidate ->
            resolve(candidate)?.let {
                return it
            }
        }

    listOf("wix.exe", "wix").forEach { name ->
        findOnPath(name)?.let {
            return WindowsWixTool(wix = it)
        }
    }
    val candle = findOnPath("candle.exe") ?: findOnPath("candle")
    val light = findOnPath("light.exe") ?: findOnPath("light")
    if (candle != null && light != null) {
        return WindowsWixTool(candle = candle, light = light)
    }

    commonWindowsToolRoots().forEach { root ->
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("WiX Toolset", ignoreCase = true) }
            .forEach { dir ->
                resolve(dir.absolutePath)?.let {
                    return it
                }
            }
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
    findOnPath("makensis.exe")?.let {
        return it
    }
    findOnPath("makensis")?.let {
        return it
    }
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
