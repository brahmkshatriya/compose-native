package dev.brahmkshatriya.compose

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class DesktopNativeLibrariesTest {
    @Test
    fun isolatesHostOverridesFromCrossTargetLibraryPaths() {
        val environment =
            mapOf(
                "SDL3_LIBRARY_DIR" to "/host/sdl",
                "LINUX_NATIVE_LIBRARY_DIRS" to "/host/other",
                "LINUX_ARM64_NATIVE_LIBRARY_DIRS" to "/cross/sdl${File.pathSeparator}/cross/system",
            )
        val host = desktopNativeLibraryDirectories("linuxX64", "linuxX64", environment::get)
        val arm = desktopNativeLibraryDirectories("linuxArm64", "linuxX64", environment::get)
        val mac = desktopNativeLibraryDirectories("macosArm64", "linuxX64", environment::get)
        assertTrue("/host/sdl" in host)
        assertTrue("/host/other" in host)
        assertEquals(listOf("/cross/sdl", "/cross/system"), arm.take(2))
        assertFalse(arm.any { it.startsWith("/host") || it == "/usr/lib" })
        assertFalse(
            mac.any {
                it.startsWith("/host") || it.startsWith("/cross") || it.startsWith("/usr/lib/")
            }
        )
        assertTrue("/opt/homebrew/opt/sdl3/lib" in mac)
    }

    @Test
    fun packagesArm64SdlWithoutRunningTheTargetOrUsingHostLibraries() {
        val directory = createTempDirectory("compose-native-arm64-package").toFile()
        val hostSdl = elf(directory.resolve("host/libSDL3.so.0"), 62)
        val armSdl = elf(directory.resolve("arm/libSDL3.so.0.4.10"), 183)
        val search = listOf(hostSdl.parentFile, armSdl.parentFile)
        assertEquals(armSdl, findLinuxSdlRuntime(search, "aarch64"))
        assertEquals(hostSdl, findLinuxSdlRuntime(search, "x86_64"))
        assertNull(findLinuxSdlRuntime(listOf(hostSdl.parentFile), "aarch64"))

        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.createComposeNativeApplicationExtension()
        val extension = project.extensions.getByType(ComposeNativeApplicationExtension::class.java)
        extension.linuxArm64LibraryDirectories.setFrom(search)
        val task = project.tasks.register("prepareArm64", PrepareLinuxAppDirTask::class.java).get()
        task.applicationName.set("Example")
        task.packageName.set("com.example")
        task.executableName.set("example")
        task.packageVersion.set("1.0.0")
        task.applicationDescription.set("Example")
        task.vendor.set("")
        task.categories.set(listOf("Audio"))
        task.startupWmClass.set("Example")
        task.stripExecutable.set(false)
        task.bundleSdl.set(true)
        task.targetArchitecture.set("aarch64")
        task.releaseExecutable.set(elf(directory.resolve("example.kexe"), 183))
        task.sdlRuntimeFiles.from(
            findLinuxSdlRuntime(extension.linuxArm64LibraryDirectories.files, "aarch64")
        )
        val extra = directory.resolve("libextra.so").apply { writeText("extra runtime") }
        task.runtimeFiles.from(extra)
        task.appDir.set(directory.resolve("Example.AppDir"))
        task.prepare()
        val root = task.appDir.get().asFile
        assertTrue(
            root.resolve("usr/lib/libSDL3.so.0").readBytes().contentEquals(armSdl.readBytes())
        )
        assertEquals("extra runtime", root.resolve("usr/lib/libextra.so").readText())
        assertTrue(root.resolve("usr/bin/example").isFile)
        assertTrue(root.resolve("AppRun").canExecute())

        // Explicit runtimes take precedence over automatic discovery.
        val explicit =
            elf(directory.resolve("explicit/libSDL3.so.0"), 183).apply { appendText("override") }
        task.runtimeFiles.from(explicit)
        task.prepare()
        assertTrue(
            root.resolve("usr/lib/libSDL3.so.0").readBytes().contentEquals(explicit.readBytes())
        )
    }

    @Test
    fun doesNotUseTheHostStripForADifferentArchitecture() {
        assertFalse("strip" in linuxStripToolNames("aarch64", hostMatches = false))
        assertEquals(
            "aarch64-linux-gnu-strip",
            linuxStripToolNames("aarch64", hostMatches = false).first(),
        )
        assertTrue("llvm-strip" in linuxStripToolNames("aarch64", hostMatches = false))
        assertEquals("strip", linuxStripToolNames("x86_64", hostMatches = true).first())
    }

    private fun elf(file: File, machine: Int): File =
        file.apply {
            parentFile.mkdirs()
            writeBytes(
                ByteArray(64).apply {
                    this[0] = 0x7f
                    this[1] = 'E'.code.toByte()
                    this[2] = 'L'.code.toByte()
                    this[3] = 'F'.code.toByte()
                    this[4] = 2
                    this[5] = 1
                    this[18] = machine.toByte()
                    this[19] = (machine shr 8).toByte()
                }
            )
        }
}
