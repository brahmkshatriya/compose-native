package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeNativeRedirectsTest {
    @Test
    fun redirectsOnlyMarkerCommonMetadataModules() {
        assertEquals(
            true,
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.runtime", "runtime"),
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
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.runtime", "runtime-retain"),
        )
        assertEquals(
            true,
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.ui", "ui-skiko"),
        )
        assertEquals(false, isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.ui", "ui"))
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
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.androidx.collection", "collection"),
        )
        assertEquals(
            false,
            isRedirectedCommonMetadataModule("dev.brahmkshatriya.compose.foundation", "foundation"),
        )
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
        assertEquals(
            true,
            isNativeRedirectImplementation("androidx.navigation3", "navigation3-runtime"),
        )
        assertEquals(
            false,
            isNativeRedirectImplementation("androidx.navigation3", "navigation3-ui"),
        )
        assertEquals(
            true,
            isNativeRedirectImplementation("androidx.navigationevent", "navigationevent-compose"),
        )
        assertEquals(
            true,
            isNativeRedirectImplementation("org.jetbrains.androidx.lifecycle", "lifecycle-runtime"),
        )
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
            "androidx.navigation3:navigation3-runtime:1.2.0",
            nativeRedirectImplementationCoordinateOrNull(
                "androidx.navigation3",
                "navigation3-runtime",
            ),
        )
        assertEquals(
            "androidx.navigation3:navigation3-runtime-linuxx64:1.2.0",
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
            "androidx.compose.runtime:runtime-linuxx64:1.13.0-beta01",
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
            "androidx.compose.runtime:runtime-retain-linuxx64:1.13.0-beta01",
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
            nativeRedirectImplementationCoordinateOrNull("androidx.navigation3", "navigation3-ui"),
        )
    }

    @Test
    fun appliesNativeOverlayToConcreteAndSharedNativeConfigurations() {
        assertEquals(true, "linuxX64CompileKlibraries".usesNativeOverlay())
        assertEquals(true, "linuxMainResolvableDependenciesMetadata".usesNativeOverlay())
        assertEquals(true, "macosMainResolvableDependenciesMetadata".usesNativeOverlay())
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
}
