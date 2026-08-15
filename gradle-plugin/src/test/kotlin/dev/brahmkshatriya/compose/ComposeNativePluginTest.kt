package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativePluginTest {
    @Test
    fun enablesRequestedCoordinateMatchingOnlyWhenKotlinNeedsTheFlag() {
        assertEquals(true, null.requiresRequestedCoordinateMatchingFlag())
        assertEquals(true, "2.3.20-release-208".requiresRequestedCoordinateMatchingFlag())
        assertEquals(false, "2.4.0-release-281".requiresRequestedCoordinateMatchingFlag())
        assertEquals(false, "3.0.0".requiresRequestedCoordinateMatchingFlag())
    }

    @Test
    fun identifiesDesktopNativeConfigurations() {
        assertEquals(true, "linuxX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "linuxArm64RuntimeKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "mingwX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isDesktopNativeConfiguration(),
        )
        assertEquals(
            false,
            "commonMainResolvableDependenciesMetadata".isDesktopNativeConfiguration(),
        )
    }

    @Test
    fun identifiesSharedNativeMetadataConfigurationsForDisambiguation() {
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            false,
            "linuxMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            false,
            "compileDesktopNativeMainKotlinMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(false, "linuxX64CompileKlibraries".isSharedNativeMetadataConfiguration())
        assertEquals(
            false,
            "commonMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
    }

    @Test
    fun identifiesMetadataTransformationConfigurations() {
        assertEquals(
            true,
            "commonMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            false,
            "allSourceSetsCompileDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            false,
            "macosArm64CompilationDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(false, "commonMainImplementation".isMetadataTransformationConfiguration())
    }

    @Test
    fun appliesNativeOverlayToConcreteAndSharedNativeConfigurations() {
        assertEquals(true, "linuxX64CompileKlibraries".usesNativeOverlay())
        assertEquals(false, "linuxMainResolvableDependenciesMetadata".usesNativeOverlay())
        assertEquals(true, "desktopNativeMainImplementation".usesNativeOverlay())
        assertEquals(false, "commonMainResolvableDependenciesMetadata".usesNativeOverlay())
    }

    @Test
    fun mapsUnpublishedNativeMetadataModulesToFork() {
        assertEquals(
            mapOf(
                "org.jetbrains.compose.ui:ui-skiko" to
                    ModuleSubstitution(
                        officialCoordinate = "org.jetbrains.compose.ui:ui-skiko",
                        forkCoordinate = "dev.brahmkshatriya.compose.ui:ui-skiko:1.12.10-alpha06",
                    )
            ),
            nativeMetadataSubstitutionsFor("1.12.10-alpha06"),
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
                forkCoordinate = "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02",
            ),
            overlaySubstitutionFor(dependency),
        )
    }

    @Test
    fun mapsExplicitSkikoForkDependencyToOfficialCoordinate() {
        val project = ProjectBuilder.builder().build()
        val dependency = project.dependencies.create("dev.brahmkshatriya.skiko:skiko:0.151.4")

        assertEquals(
            ModuleSubstitution(
                officialCoordinate = "org.jetbrains.skiko:skiko",
                forkCoordinate = "dev.brahmkshatriya.skiko:skiko:0.151.4",
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
                    officialCoordinate = "androidx.compose.foundation:foundation-android",
                    forkCoordinate =
                        "dev.brahmkshatriya.compose.foundation:foundation-android:" +
                            "1.12.10-alpha02",
                ),
            ),
            fullForkSubstitutionsFor(dependency),
        )
    }

    @Test
    fun identifiesAProjectThatDirectlyConsumesTheComposeNativeProject() {
        val root = ProjectBuilder.builder().withName("root").build()
        val producer = ProjectBuilder.builder().withName("app").withParent(root).build()
        val consumer = ProjectBuilder.builder().withName("android").withParent(root).build()
        val unrelated = ProjectBuilder.builder().withName("unrelated").withParent(root).build()
        consumer.configurations
            .create("implementation")
            .dependencies
            .add(consumer.dependencies.project(mapOf("path" to producer.path)))

        assertEquals(true, consumer.directlyDependsOn(producer))
        assertEquals(false, unrelated.directlyDependsOn(producer))
    }

    @Test
    fun mapsAllAndroidConsumerCoordinatesDirectlyToTheForkAndroidArtifact() {
        val project = ProjectBuilder.builder().build()
        val dependency =
            project.dependencies.create(
                "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02"
            )
        val forkAndroid = "dev.brahmkshatriya.compose.foundation:foundation-android:1.12.10-alpha02"

        assertEquals(
            listOf(
                ModuleSubstitution(
                    officialCoordinate = "org.jetbrains.compose.foundation:foundation",
                    forkCoordinate = forkAndroid,
                ),
                ModuleSubstitution(
                    officialCoordinate = "androidx.compose.foundation:foundation-android",
                    forkCoordinate = forkAndroid,
                ),
                ModuleSubstitution(
                    officialCoordinate = "dev.brahmkshatriya.compose.foundation:foundation",
                    forkCoordinate = forkAndroid,
                ),
            ),
            androidApplicationConsumerSubstitutionsFor(dependency),
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
                    officialCoordinate = "org.jetbrains.compose.desktop:desktop-native",
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
                includeNativeOnlyCompose = true,
                includeAndroidx = false,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "org.jetbrains.compose.components",
                "components-resources",
                "1.12.10-alpha02",
                includeAndroidx = false,
            )
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
