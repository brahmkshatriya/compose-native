package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeNativePlatformRoutingTest {
    @Test
    fun classifiesOnlyNonDesktopForkLeafModulesForMetadataRepair() {
        assertEquals(
            NonDesktopForkPlatformRouting.Android,
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-android",
            ),
        )
        assertEquals(
            NonDesktopForkPlatformRouting.Jvm,
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3-desktop",
            ),
        )
        assertEquals(
            NonDesktopForkPlatformRouting.Web,
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-wasm-js",
            ),
        )
        assertEquals(
            NonDesktopForkPlatformRouting.Native,
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3-iosarm64",
            ),
        )
        assertNull(
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-linuxx64",
            )
        )
        assertNull(
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3-macosarm64",
            )
        )
        assertNull(
            nonDesktopForkPlatformRoutingOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation",
            )
        )
        assertNull(
            nonDesktopForkPlatformRoutingOrNull("dev.brahmkshatriya.compose.ui", "ui-iosarm64")
        )
    }

    @Test
    fun keepsOnlyCrossPlatformForkRootsOnNonDesktopTargets() {
        assertEquals(
            true,
            isCrossPlatformForkModule("dev.brahmkshatriya.compose.foundation", "foundation"),
        )
        assertEquals(
            true,
            isCrossPlatformForkModule("dev.brahmkshatriya.compose.foundation", "foundation-android"),
        )
        assertEquals(
            true,
            isCrossPlatformForkModule("dev.brahmkshatriya.compose.material3", "material3-iosarm64"),
        )
        assertEquals(
            false,
            isCrossPlatformForkModule("dev.brahmkshatriya.compose.ui", "ui-iosarm64"),
        )
        assertEquals(
            false,
            isCrossPlatformForkModule(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-layout-iosarm64",
            ),
        )

        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation",
                useNativeRedirects = true,
            )
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3-android",
                useNativeRedirects = false,
            )
        )
        assertEquals(
            "org.jetbrains.compose.ui:ui:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.ui",
                "ui",
                useNativeRedirects = true,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.ui:ui:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.ui",
                "ui",
                useNativeRedirects = false,
                useJvmRedirects = true,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.ui:ui-uikit:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.ui",
                "ui-uikit",
                useNativeRedirects = false,
                useMetadataRedirects = true,
            ),
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation",
                useNativeRedirects = false,
                useMetadataRedirects = true,
            )
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3",
                useNativeRedirects = false,
                useMetadataRedirects = true,
            )
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation",
                useNativeRedirects = false,
                useJvmRedirects = true,
            )
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3",
                useNativeRedirects = false,
                useJvmRedirects = true,
            )
        )
        assertEquals(
            "org.jetbrains.compose.animation:animation:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.animation",
                "animation",
                useNativeRedirects = true,
            ),
        )
        assertEquals(
            "androidx.compose.runtime:runtime:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.runtime",
                "runtime",
                useNativeRedirects = true,
            ),
        )
        assertEquals(
            "androidx.compose.runtime:runtime:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.runtime",
                "runtime",
                useNativeRedirects = false,
            ),
        )
        assertNull(
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-wasm-js",
                useNativeRedirects = false,
                useWebRedirects = true,
            )
        )
        assertEquals(
            "org.jetbrains.compose.animation:animation-wasm-js:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.animation",
                "animation-wasm-js",
                useNativeRedirects = false,
                useWebRedirects = true,
            ),
        )
        assertEquals(
            "androidx.compose.runtime:runtime-wasm-js:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.runtime",
                "runtime-wasm-js",
                useNativeRedirects = false,
                useWebRedirects = true,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.ui:ui-android:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.ui",
                "ui-android",
                useNativeRedirects = false,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.animation:animation:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.animation",
                "animation",
                useNativeRedirects = false,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.foundation:foundation-layout:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.foundation",
                "foundation-layout",
                useNativeRedirects = false,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.material:material-ripple:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.material",
                "material-ripple",
                useNativeRedirects = false,
            ),
        )
        assertEquals(
            "org.jetbrains.compose.material3:material3-ripple:1.13.0-beta01",
            nonDesktopForkSupportCoordinateOrNull(
                "dev.brahmkshatriya.compose.material3",
                "material3-ripple",
                useNativeRedirects = false,
            ),
        )
    }
}
