package dev.brahmkshatriya.compose

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinUnresolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.klibExtra

class ComposeNativeIdeMetadataTest {
    @Test
    fun givesCommonIdeFallbackItsOwnIdentityWithoutHidingMissingPlatforms() {
        val directory = createTempDirectory("compose-native-ide-common").toFile()
        val artifact = directory.resolve("foundation.jar")
        ZipOutputStream(artifact.outputStream()).use { zip ->
            mapOf(
                    "commonMain/default/manifest" to "unique_name=foundation_commonMain\n",
                    "commonMain/default/linkdata/package_foundation/0.knm" to "Canvas",
                    "linuxMain/default/linkdata/package_foundation/0.knm" to "LinuxOnly",
                )
                .forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
        }
        val root = foundationIdeDependency(artifact)
        val unresolved =
            IdeaKotlinUnresolvedBinaryDependency("Missing Linux ARM64 binary", root.coordinates)
        val prepared =
            prepareForkIdeDependencies(setOf(root, unresolved), directory.resolve("cache"))
        assertTrue(unresolved in prepared)
        val common = prepared.filterIsInstance<IdeaKotlinResolvedBinaryDependency>().single()
        assertEquals("commonMain", common.coordinates?.sourceSetName)
        assertFalse(common.coordinates == unresolved.coordinates)
        ZipFile(common.classpath.single()).use { zip ->
            assertEquals(
                "Canvas",
                zip.getInputStream(zip.getEntry("default/linkdata/package_foundation/0.knm"))
                    .bufferedReader()
                    .readText(),
            )
            assertTrue(zip.entries().asSequence().none { it.name.contains("linuxMain") })
        }
        assertEquals(
            prepared,
            prepareForkIdeDependencies(setOf(root, unresolved), directory.resolve("cache")),
        )
    }

    @Test
    fun prefersKotlinTransformedMetadataOverTheCommonIdeFallback() {
        val directory = createTempDirectory("compose-native-ide-existing").toFile()
        val root = foundationIdeDependency(directory.resolve("foundation.jar"))
        val common =
            root.copy(
                coordinates = root.coordinates!!.copy(sourceSetName = "commonMain"),
                classpath = IdeaKotlinClasspath(directory.resolve("transformed.klib")),
            )
        assertEquals(
            setOf(common),
            prepareForkIdeDependencies(setOf(root, common), directory.resolve("cache")),
        )
        assertFalse(directory.resolve("cache").exists())
    }

    @Test
    fun importsDesktopKotlinAndNestedInteropWithoutChangingTargetManifests() {
        val directory = createTempDirectory("compose-native-ide-desktop").toFile()
        val artifact = directory.resolve("desktop-native.jar")
        val interopPrefix = "desktopNativeMain-cinterop/desktop-native-cinterop-sdl3"
        val interopManifest =
            """
            unique_name=dev.brahmkshatriya.compose.desktop\:desktop-native-cinterop-sdl3
            builtins_platform=NATIVE
            interop=true
            package=sdl3
            native_targets=linux_x64
            commonizer_native_targets=linux_x64
            commonizer_target=(linux_x64)
            """
                .trimIndent()
        writeIdeMetadataFixture(
            artifact,
            mapOf(
                "desktopNativeMain/default/manifest" to
                    "unique_name=desktop-native_desktopNativeMain\n",
                "desktopNativeMain/default/linkdata/package_window/0.knm" to "Window",
                "$interopPrefix/default/manifest" to interopManifest,
                "$interopPrefix/default/linkdata/package_sdl3/0.knm" to "SDL_SetClipboardText",
                "linuxMain/default/manifest" to "unique_name=linuxOnly\n",
                "linuxMain/default/linkdata/package_linux/0.knm" to "LinuxOnly",
                "macosMain/default/manifest" to "unique_name=macosOnly\n",
                "macosMain/default/linkdata/package_macos/0.knm" to "MacOnly",
                "linuxMain-cinterop/linux/default/manifest" to
                    "unique_name=linuxInterop\ninterop=true\n",
            ),
        )
        val root =
            foundationIdeDependency(artifact)
                .copy(
                    coordinates =
                        IdeaKotlinBinaryCoordinates(
                            "dev.brahmkshatriya.compose.desktop",
                            "desktop-native",
                            "1.13.0-beta01",
                        )
                )
        val unresolved =
            IdeaKotlinUnresolvedBinaryDependency("Missing macOS binary", root.coordinates)
        val prepared =
            prepareForkIdeDependencies(
                setOf(root, unresolved),
                directory.resolve("cache"),
                "desktopNativeMain",
            )
        assertTrue(unresolved in prepared)
        val resolved = prepared.filterIsInstance<IdeaKotlinResolvedBinaryDependency>()
        assertEquals(2, resolved.size)
        val desktop = resolved.single { it.coordinates?.sourceSetName == "desktopNativeMain" }
        val interop = resolved.single { it.klibExtra?.isInterop == true }
        assertEquals("dev.brahmkshatriya.compose.desktop", interop.coordinates?.group)
        assertEquals("desktop-native-cinterop-sdl3", interop.coordinates?.module)
        assertEquals("linux_x64", interop.coordinates?.sourceSetName)
        assertEquals(listOf("linux_x64"), interop.klibExtra?.nativeTargets)
        assertEquals("sdl3", interop.klibExtra?.packageFqName)
        assertNull(root.klibExtra)
        ZipFile(interop.classpath.single()).use { zip ->
            assertEquals(
                interopManifest,
                zip.getInputStream(zip.getEntry("default/manifest")).bufferedReader().readText(),
            )
            assertTrue(zip.getEntry("default/linkdata/package_sdl3/0.knm") != null)
        }
        ZipFile(desktop.classpath.single()).use { zip ->
            assertTrue(zip.getEntry("default/linkdata/package_window/0.knm") != null)
            assertTrue(
                zip.entries().asSequence().none {
                    it.name.contains("sdl3") ||
                        it.name.contains("linux") ||
                        it.name.contains("macos")
                }
            )
        }
        assertEquals(
            prepared,
            prepareForkIdeDependencies(
                setOf(root, unresolved),
                directory.resolve("cache"),
                "desktopNativeMain",
            ),
        )
        // Existing KGP fragment libraries win over the archive fallback, including CInterop
        // libraries.
        assertEquals(
            resolved.toSet(),
            prepareForkIdeDependencies(
                setOf(root) + resolved,
                directory.resolve("cache"),
                "desktopNativeMain",
            ),
        )
        for (sourceSet in listOf("commonMain", "skiaTargetMain", "androidMain")) {
            assertEquals(
                setOf(root),
                prepareForkIdeDependencies(setOf(root), directory.resolve("other-cache"), sourceSet),
            )
        }
        assertFalse(directory.resolve("other-cache").exists())
    }

    private fun writeIdeMetadataFixture(artifact: File, entries: Map<String, String>) {
        ZipOutputStream(artifact.outputStream()).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun foundationIdeDependency(artifact: File) =
        IdeaKotlinResolvedBinaryDependency(
            binaryType = "KOTLIN_COMPILE",
            classpath = IdeaKotlinClasspath(artifact),
            coordinates =
                IdeaKotlinBinaryCoordinates(
                    "dev.brahmkshatriya.compose.foundation",
                    "foundation",
                    "1.13.0-beta01",
                ),
        )
}
