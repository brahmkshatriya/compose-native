package dev.brahmkshatriya.compose

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsInstallerPackagingTest {
    @Test
    fun normalizesComposeVersionsForWindowsInstallers() {
        assertEquals("1.13.0", windowsMsiVersion("1.13.0-alpha03"))
        assertEquals("1.13.0.3", windowsNsisVersion("1.13.0-alpha03"))
        assertEquals("2.0.0", windowsMsiVersion("2"))
        assertEquals("2.0.0.0", windowsNsisVersion("2"))
    }

    @Test
    fun generatesModernAndLegacyWixSourcesForTheWholeDistribution() {
        val distribution = sampleDistribution()

        val modern =
            windowsMsiSource(
                modernWix = true,
                applicationName = "Example App",
                packageName = "com.example.app",
                packageVersion = "1.13.0-alpha03",
                description = "Example & test",
                vendor = "Example Co",
                distributionDirectory = distribution,
            )
        val legacy =
            windowsMsiSource(
                modernWix = false,
                applicationName = "Example App",
                packageName = "com.example.app",
                packageVersion = "1.13.0-alpha03",
                description = "Example & test",
                vendor = "Example Co",
                distributionDirectory = distribution,
            )

        assertContains(modern, "http://wixtoolset.org/schemas/v4/wxs")
        assertContains(modern, "<Package Name=\"Example App\"")
        assertContains(modern, "Version=\"1.13.0\"")
        assertContains(modern, "ARPCOMMENTS")
        assertContains(modern, "Example &amp; test")
        assertContains(modern, "example.exe")
        assertContains(modern, "SDL3.dll")
        assertContains(modern, "strings.bin")
        assertContains(modern, "ProgramFiles64Folder")

        assertContains(legacy, "http://schemas.microsoft.com/wix/2006/wi")
        assertContains(legacy, "<Product Id=\"*\"")
        assertContains(legacy, "Platform=\"x64\"")
        assertContains(legacy, "example.exe")
        assertContains(legacy, "strings.bin")
        assertFalse("http://wixtoolset.org/schemas/v4/wxs" in legacy)
    }

    @Test
    fun generatesNsisInstallerWithInstallAndUninstallMetadata() {
        val distribution = sampleDistribution()
        val output = distribution.parentFile.resolve("Example-installer.exe")

        val script =
            windowsNsisSource(
                applicationName = "Example App",
                packageName = "com.example.app",
                executableName = "example",
                packageVersion = "1.13.0-alpha03",
                description = "Example application",
                vendor = "Example Co",
                distributionDirectory = distribution,
                outputFile = output,
            )

        assertContains(script, "VIProductVersion \"1.13.0.3\"")
        assertContains(script, "VIAddVersionKey \"LegalCopyright\" \"Example Co\"")
        assertContains(script, "File /r")
        assertContains(script, "SetRegView 64")
        assertContains(script, "WriteUninstaller \"\$INSTDIR\\Uninstall.exe\"")
        assertContains(script, "CreateShortCut")
        assertContains(script, "example.exe")
        assertContains(
            script,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\com.example.app",
        )
        assertContains(script, "DeleteRegKey HKLM")
        assertContains(script, "RMDir /r \"\$INSTDIR\"")
        assertTrue(script.endsWith("SectionEnd\n"))
    }

    private fun sampleDistribution(): File {
        val directory = createTempDirectory("compose-native-windows-installer-test").toFile()
        directory.deleteOnExit()
        directory.resolve("example.exe").writeBytes(byteArrayOf(1, 2, 3))
        directory.resolve("SDL3.dll").writeBytes(byteArrayOf(4, 5, 6))
        directory.resolve("resources/i18n").mkdirs()
        directory.resolve("resources/i18n/strings.bin").writeBytes(byteArrayOf(7, 8, 9))
        return directory
    }
}
