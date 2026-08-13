package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativePluginTest {
    @Test
    fun enablesRequestedCoordinateMatchingForMetadataTransforms() {
        val project = ProjectBuilder.builder().build()

        ComposeNativePlugin().apply(project)

        assertEquals(
            true,
            project.findProperty(
                "kotlin.internal.kmp.allowMatchingByRequestedCoordinatesInMetadataTransformations"
            ),
        )
    }

    @Test
    fun doesNotRegisterAProjectExtension() {
        val project = ProjectBuilder.builder().build()

        ComposeNativePlugin().apply(project)

        assertNull(project.extensions.findByName("composeNative"))
        assertNull(project.extensions.findByName("brahmkshatriyaCompose"))
    }

    @Test
    fun mapsExplicitComposeForkDependencyToOfficialCoordinate() {
        val project = ProjectBuilder.builder().build()
        val dependency =
            project.dependencies.create(
                "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02"
            )

        assertEquals(
            ModuleSubstitution(
                officialCoordinate = "org.jetbrains.compose.foundation:foundation",
                forkCoordinate =
                    "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02",
            ),
            overlaySubstitutionFor(dependency),
        )
    }

    @Test
    fun mapsExplicitSkikoForkDependencyToOfficialCoordinate() {
        val project = ProjectBuilder.builder().build()
        val dependency =
            project.dependencies.create("dev.brahmkshatriya.skiko:skiko:0.151.3")

        assertEquals(
            ModuleSubstitution(
                officialCoordinate = "org.jetbrains.skiko:skiko",
                forkCoordinate = "dev.brahmkshatriya.skiko:skiko:0.151.3",
            ),
            overlaySubstitutionFor(dependency),
        )
    }

    @Test
    fun mapsCommonComposeForkDependencyToJetBrainsAndAndroidXCoordinates() {
        val project = ProjectBuilder.builder().build()
        val dependency =
            project.dependencies.create(
                "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02"
            )

        assertEquals(
            listOf(
                ModuleSubstitution(
                    officialCoordinate = "org.jetbrains.compose.foundation:foundation",
                    forkCoordinate =
                        "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02",
                ),
                ModuleSubstitution(
                    officialCoordinate =
                        "androidx.compose.foundation:foundation-android",
                    forkCoordinate =
                        "dev.brahmkshatriya.compose.foundation:foundation-android:" +
                            "1.12.10-alpha02",
                ),
            ),
            fullForkSubstitutionsFor(dependency),
        )
    }

    @Test
    fun doesNotInventAndroidCoordinateForNonAndroidComposeFamily() {
        val project = ProjectBuilder.builder().build()
        val dependency =
            project.dependencies.create(
                "dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha02"
            )

        assertEquals(
            listOf(
                ModuleSubstitution(
                    officialCoordinate =
                        "org.jetbrains.compose.desktop:desktop-native",
                    forkCoordinate =
                        "dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha02",
                )
            ),
            fullForkSubstitutionsFor(dependency),
        )
    }

    @Test
    fun ignoresOfficialAndUnversionedDependencies() {
        val project = ProjectBuilder.builder().build()

        assertNull(
            overlaySubstitutionFor(
                project.dependencies.create("org.jetbrains.compose.ui:ui:1.12.0-rc01")
            )
        )
        assertNull(
            overlaySubstitutionFor(project.dependencies.create("dev.brahmkshatriya.compose.ui:ui"))
        )
        assertEquals(
            emptyList(),
            fullForkSubstitutionsFor(
                project.dependencies.create("org.jetbrains.compose.ui:ui:1.12.0-rc01")
            ),
        )
        assertEquals(
            emptyList(),
            fullForkSubstitutionsFor(
                project.dependencies.create("dev.brahmkshatriya.compose.ui:ui")
            ),
        )
    }

    @Test
    fun mapsTransitiveJetBrainsComposeModulesUsingDeclaredForkVersion() {
        assertEquals(
            "dev.brahmkshatriya.compose.ui:ui-graphics:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.compose.ui",
                "ui-graphics",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.compose.animation:animation-core:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.compose.animation",
                "animation-core",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.compose.components:components-resources:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.compose.components",
                "components-resources",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.androidx.lifecycle:lifecycle-runtime:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.androidx.lifecycle",
                "lifecycle-runtime",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "org.jetbrains.androidx.lifecycle",
                "lifecycle-runtime",
                "1.12.10-alpha02",
                includeJetBrainsAndroidx = false,
                includeAndroidx = false,
            )
        )
        assertNull(
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime",
                "1.12.10-alpha02",
                includeAndroidx = false,
            )
        )
        assertEquals(
            "dev.brahmkshatriya.compose.runtime:runtime-android:1.12.10-alpha02",
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime-android",
                "1.12.10-alpha02",
                includeAndroidx = true,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime-retain",
                "1.12.10-alpha02",
                includeAndroidx = true,
            )
        )
        assertNull(
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime-annotation",
                "1.12.10-alpha02",
                includeAndroidx = true,
            )
        )
    }

}
