package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativeSkikoTest {
    @Test
    fun alignsOnlyDesktopNativeSkikoCoordinates() {
        for (group in listOf(OFFICIAL_SKIKO_GROUP, FORK_SKIKO_GROUP)) {
            for (module in
                listOf(
                    "skiko",
                    "skiko-linuxx64",
                    "skiko-linuxarm64",
                    "skiko-mingwx64",
                    "skiko-macosx64",
                    "skiko-macosarm64",
                )) {
                assertEquals(
                    "$FORK_SKIKO_GROUP:$module:0.156.1",
                    nativeSkikoCoordinateFor(group, module, "0.156.1"),
                )
            }
            for (module in
                listOf(
                    "skiko-awt",
                    "skiko-awt-runtime-linux-x64",
                    "skiko-iosarm64",
                    "skiko-wasm-js",
                )) {
                assertNull(nativeSkikoCoordinateFor(group, module, "0.156.1"))
            }
        }
        assertNull(nativeSkikoCoordinateFor("example", "skiko", "0.156.1"))
    }

    @Test
    fun usesCompatibleSkikoPublicationVersionUnlessLibraryDeclaresOne() {
        val project = ProjectBuilder.builder().build()
        val desktopNative = project.configurations.create("desktopNativeMainImplementation")

        assertEquals("0.156.1", project.nativeSkikoPublicationVersion())

        desktopNative.dependencies.add(
            project.dependencies.create("dev.brahmkshatriya.skiko:skiko:0.151.6")
        )
        assertEquals("0.151.6", project.nativeSkikoPublicationVersion())
    }

    @Test
    fun rewritesOnlyOfficialSkikoInNativePublicationMetadata() {
        val metadata =
            """
            {
              "dependencies": [
                {
                  "group": "org.jetbrains.skiko",
                  "module": "skiko",
                  "version": { "requires": "0.150.1" }
                },
                {
                  "group": "org.jetbrains.skiko",
                  "module": "skiko-awt",
                  "version": { "requires": "0.150.1" }
                }
              ]
            }
            """
                .trimIndent()

        val rewritten = rewriteNativeSkikoPublicationMetadata(metadata, "0.151.5")

        assertEquals(true, "dev.brahmkshatriya.skiko" in rewritten)
        assertEquals(true, "\"requires\": \"0.151.5\"" in rewritten)
        assertEquals(true, "\"module\": \"skiko-awt\"" in rewritten)
        assertEquals(true, "\"requires\": \"0.150.1\"" in rewritten)
    }

    @Test
    fun alignsAlreadyForkedSkikoInNativePublicationMetadata() {
        val metadata =
            """{"dependencies":[{"group":"dev.brahmkshatriya.skiko","module":"skiko","version":{"strictly":"0.153.1"}}]}"""
        assertEquals(
            metadata.replace("0.153.1", "0.156.1"),
            rewriteNativeSkikoPublicationMetadata(metadata, "0.156.1"),
        )
    }

    @Test
    fun identifiesOnlyDesktopNativePublicationMetadataTasks() {
        assertEquals(
            true,
            "generateMetadataFileForLinuxX64Publication".isDesktopNativePublicationMetadataTask(),
        )
        assertEquals(
            true,
            "generateMetadataFileForLinuxArm64Publication".isDesktopNativePublicationMetadataTask(),
        )
        assertEquals(
            true,
            "generateMetadataFileForMingwX64Publication".isDesktopNativePublicationMetadataTask(),
        )
        assertEquals(
            true,
            "generateMetadataFileForMacosX64Publication".isDesktopNativePublicationMetadataTask(),
        )
        assertEquals(
            true,
            "generateMetadataFileForMacosArm64Publication".isDesktopNativePublicationMetadataTask(),
        )
        assertEquals(
            false,
            "generateMetadataFileForJvmPublication".isDesktopNativePublicationMetadataTask(),
        )
    }

    @Test
    fun mapsExplicitSkikoForkDependencyToOfficialCoordinate() {
        val project = ProjectBuilder.builder().build()
        val dependency = project.dependencies.create("dev.brahmkshatriya.skiko:skiko:0.151.5")

        assertEquals(
            ModuleSubstitution(
                officialCoordinate = "org.jetbrains.skiko:skiko",
                forkCoordinate = "dev.brahmkshatriya.skiko:skiko:0.151.5",
            ),
            overlaySubstitutionFor(dependency),
        )
    }
}
