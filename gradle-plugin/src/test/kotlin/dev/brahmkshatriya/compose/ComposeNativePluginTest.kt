package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.api.attributes.Attribute
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
        assertEquals(true, "macosX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "macosArm64RuntimeKlibraries".isDesktopNativeConfiguration())
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
    fun doesNotForceSharedNativeMetadataToOneLeafTarget() {
        val project = ProjectBuilder.builder().build()
        ComposeNativePlugin().apply(project)
        val desktopMetadata =
            project.configurations.create("desktopNativeMainResolvableDependenciesMetadata")
        val linuxWindowsMetadata =
            project.configurations.create("linuxWindowsMainResolvableDependenciesMetadata")
        val commonMetadata =
            project.configurations.create("commonMainResolvableDependenciesMetadata")
        val nativeTarget = Attribute.of("org.jetbrains.kotlin.native.target", String::class.java)

        assertNull(desktopMetadata.attributes.getAttribute(nativeTarget))
        assertNull(linuxWindowsMetadata.attributes.getAttribute(nativeTarget))
        assertNull(commonMetadata.attributes.getAttribute(nativeTarget))
    }

    @Test
    fun identifiesSharedNativeMetadataConfigurationsForDisambiguation() {
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            true,
            "linuxWindowsMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
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
            true,
            "skiaMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            true,
            "appleMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
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
    fun redirectsOnlyMarkerCommonMetadataModules() {
        assertEquals(
            true,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.compose.runtime",
                "runtime",
            ),
        )
        assertEquals(
            true,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.compose.runtime",
                "runtime-saveable",
            ),
        )
        assertEquals(
            false,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.compose.runtime",
                "runtime-retain",
            ),
        )
        assertEquals(
            true,
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.ui", "ui-skiko"),
        )
        assertEquals(
            false,
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.ui", "ui"),
        )
        assertEquals(
            true,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.androidx.lifecycle",
                "lifecycle-runtime-compose",
            ),
        )
        assertEquals(
            true,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.androidx.navigation3",
                "navigation3-ui",
            ),
        )
        assertEquals(
            false,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.androidx.collection",
                "collection",
            ),
        )
        assertEquals(
            false,
            isRedirectedCommonMetadataModule(
                "dev.brahmkshatriya.compose.foundation",
                "foundation",
            ),
        )
    }

    @Test
    fun identifiesKotlinMetadataCompilationTasks() {
        assertEquals(true, "compileCommonMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(true, "compileSkiaMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(true, "compileAppleMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(false, "compileKotlinLinuxX64".isKotlinMetadataCompilationTask())
        assertEquals(false, "transformSkiaMainDependenciesMetadata".isKotlinMetadataCompilationTask())
    }

    @Test
    fun identifiesRedirectedNativeImplementations() {
        assertEquals(true, "linuxX64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(true, "macosArm64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(true, "iosSimulatorArm64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(false, "macosX64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(false, "commonMainResolvableDependenciesMetadata".usesRedirectedNativeTarget())

        assertEquals(true, isNativeRedirectImplementation("androidx.compose.runtime", "runtime"))
        assertEquals(true, isNativeRedirectImplementation("androidx.savedstate", "savedstate"))
        assertEquals(true, isNativeRedirectImplementation("androidx.navigation3", "navigation3-runtime"))
        assertEquals(false, isNativeRedirectImplementation("androidx.navigation3", "navigation3-ui"))
        assertEquals(true, isNativeRedirectImplementation("androidx.navigationevent", "navigationevent-compose"))
        assertEquals(true, isNativeRedirectImplementation("org.jetbrains.androidx.lifecycle", "lifecycle-runtime"))
        assertEquals(true, isNativeRedirectImplementation("androidx.collection", "collection"))

        assertEquals(
            "androidx.navigationevent:navigationevent:1.1.1",
            nativeRedirectImplementationCoordinateOrNull(
                "org.jetbrains.androidx.navigationevent",
                "navigationevent",
            ),
        )
        assertEquals(
            "androidx.lifecycle:lifecycle-runtime:2.11.0",
            nativeRedirectImplementationCoordinateOrNull(
                "org.jetbrains.androidx.lifecycle",
                "lifecycle-runtime",
            ),
        )
        assertEquals(
            "androidx.navigation3:navigation3-runtime:1.2.0-rc01",
            nativeRedirectImplementationCoordinateOrNull(
                "androidx.navigation3",
                "navigation3-runtime",
            ),
        )
        assertEquals(
            "androidx.navigation3:navigation3-runtime-linuxx64:1.2.0-rc01",
            nativeRedirectImplementationCoordinateOrNull(
                "androidx.navigation3",
                "navigation3-runtime-linuxx64",
            ),
        )
        assertEquals(
            "androidx.navigationevent:navigationevent-linuxx64:1.1.1",
            nativeRedirectImplementationCoordinateOrNull(
                "org.jetbrains.androidx.navigationevent",
                "navigationevent-linuxx64",
            ),
        )
        assertEquals(
            "androidx.compose.runtime:runtime-linuxx64:1.13.0-alpha03",
            nativeRedirectImplementationCoordinateOrNull(
                "dev.brahmkshatriya.compose.runtime",
                "runtime-linuxx64",
            ),
        )
        assertEquals(
            "androidx.savedstate:savedstate-linuxx64:1.5.0-alpha01",
            nativeRedirectImplementationCoordinateOrNull(
                "dev.brahmkshatriya.androidx.savedstate",
                "savedstate-linuxx64",
            ),
        )
        assertEquals(
            "androidx.compose.runtime:runtime-retain-linuxx64:1.13.0-alpha03",
            nativeRedirectImplementationCoordinateOrNull(
                "dev.brahmkshatriya.compose.runtime",
                "runtime-retain-linuxx64",
            ),
        )
        assertEquals(
            "androidx.collection:collection-linuxx64:1.5.0",
            nativeRedirectImplementationCoordinateOrNull(
                "dev.brahmkshatriya.androidx.collection",
                "collection-linuxx64",
            ),
        )
        assertEquals(
            null,
            nativeRedirectImplementationCoordinateOrNull(
                "androidx.navigation3",
                "navigation3-ui",
            ),
        )
    }

    @Test
    fun appliesNativeOverlayToConcreteAndSharedNativeConfigurations() {
        assertEquals(true, "linuxX64CompileKlibraries".usesNativeOverlay())
        assertEquals(false, "linuxMainResolvableDependenciesMetadata".usesNativeOverlay())
        assertEquals(false, "skiaMainResolvableDependenciesMetadata".usesNativeOverlay())
        assertEquals(false, "appleMainResolvableDependenciesMetadata".usesNativeOverlay())
        assertEquals(true, "desktopNativeMainImplementation".usesNativeOverlay())
        assertEquals(true, "macosX64CompileKlibraries".usesNativeOverlay())
        assertEquals(true, "macosArm64RuntimeKlibraries".usesNativeOverlay())
        assertEquals(false, "commonMainResolvableDependenciesMetadata".usesNativeOverlay())
    }

    @Test
    fun preservesRedirectImplementationsForRedirectedNativePlatforms() {
        assertEquals(true, "linuxX64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(true, "linuxArm64RuntimeKlibraries".usesRedirectedNativeTarget())
        assertEquals(true, "mingwX64CompilationDependenciesMetadata".usesRedirectedNativeTarget())
        assertEquals(false, "desktopNativeMainImplementation".usesRedirectedNativeTarget())
        assertEquals(false, "macosX64CompileKlibraries".usesRedirectedNativeTarget())
        assertEquals(true, "macosArm64RuntimeKlibraries".usesRedirectedNativeTarget())
        assertEquals(false, "commonMainResolvableDependenciesMetadata".usesRedirectedNativeTarget())
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
    fun usesCompatibleSkikoPublicationVersionUnlessLibraryDeclaresOne() {
        val project = ProjectBuilder.builder().build()
        val desktopNative = project.configurations.create("desktopNativeMainImplementation")

        assertEquals("0.153.1", project.nativeSkikoPublicationVersion())

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
        assertNull(
            composeForkCoordinateFor(
                "org.jetbrains.compose.ui",
                "ui-graphics",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.compose.ui:ui-graphics:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.compose.ui",
                "ui-graphics",
                "1.12.10-alpha02",
                useDesktopNativeFork = true,
                includeAndroidx = false,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "androidx.compose.ui",
                "ui-android",
                "1.12.10-alpha02",
                includeAndroidx = true,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "org.jetbrains.compose.animation",
                "animation-core",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02",
            composeForkCoordinateFor(
                "org.jetbrains.compose.foundation",
                "foundation",
                "1.12.10-alpha02",
                includeAndroidx = false,
            ),
        )
        assertNull(
            composeForkCoordinateFor(
                "org.jetbrains.compose.foundation",
                "foundation-layout",
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
                useDesktopNativeFork = true,
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
        assertEquals(
            "dev.brahmkshatriya.compose.runtime:runtime-retain:1.12.10-alpha02",
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime-retain",
                "1.12.10-alpha02",
                includeAndroidx = true,
            ),
        )
        assertEquals(
            "dev.brahmkshatriya.compose.runtime:runtime-annotation:1.12.10-alpha02",
            composeForkCoordinateFor(
                "androidx.compose.runtime",
                "runtime-annotation",
                "1.12.10-alpha02",
                includeAndroidx = true,
            ),
        )
    }
}
