package dev.brahmkshatriya.compose

/** Target identifiers are shared by target creation, source hierarchy, linking and packaging. */
internal enum class NativeApplicationPlatform {
    LINUX,
    WINDOWS,
    MACOS,
}

internal data class DesktopNativeTarget(
    val sourceSetPrefix: String,
    val konanName: String,
    val taskSuffix: String,
    val platform: NativeApplicationPlatform,
    val packageArchitecture: String,
    val displayName: String,
)

internal val DESKTOP_NATIVE_TARGETS =
    listOf(
        DesktopNativeTarget(
            "linuxX64",
            "linux_x64",
            "LinuxX64",
            NativeApplicationPlatform.LINUX,
            "x86_64",
            "Linux x64",
        ),
        DesktopNativeTarget(
            "linuxArm64",
            "linux_arm64",
            "LinuxArm64",
            NativeApplicationPlatform.LINUX,
            "aarch64",
            "Linux arm64",
        ),
        DesktopNativeTarget(
            "mingwX64",
            "mingw_x64",
            "MingwX64",
            NativeApplicationPlatform.WINDOWS,
            "x86_64",
            "Windows x64",
        ),
        DesktopNativeTarget(
            "macosX64",
            "macos_x64",
            "MacosX64",
            NativeApplicationPlatform.MACOS,
            "x86_64",
            "macOS x64",
        ),
        DesktopNativeTarget(
            "macosArm64",
            "macos_arm64",
            "MacosArm64",
            NativeApplicationPlatform.MACOS,
            "arm64",
            "macOS arm64",
        ),
    )

internal val DESKTOP_NATIVE_TARGET_SOURCE_SETS =
    DESKTOP_NATIVE_TARGETS.associate { it.konanName to it.sourceSetPrefix }
internal val DESKTOP_NATIVE_PUBLICATION_TARGET_NAMES =
    DESKTOP_NATIVE_TARGETS.map { it.sourceSetPrefix }
internal val LINUX_SOURCE_SET_NAMES =
    DESKTOP_NATIVE_TARGETS.filter { it.platform == NativeApplicationPlatform.LINUX }
        .map { it.sourceSetPrefix }
internal val MACOS_SOURCE_SET_NAMES =
    DESKTOP_NATIVE_TARGETS.filter { it.platform == NativeApplicationPlatform.MACOS }
        .map { it.sourceSetPrefix }
internal val SKIA_TARGET_MAIN_SOURCE_SET_NAMES =
    listOf("desktopNativeMain", "iosArm64Main", "iosSimulatorArm64Main", "iosX64Main", "wasmJsMain")
internal val APPLE_SOURCE_SET_NAMES =
    MACOS_SOURCE_SET_NAMES +
        listOf(
            "iosArm64",
            "iosSimulatorArm64",
            "iosX64",
            "watchosArm32",
            "watchosArm64",
            "watchosDeviceArm64",
            "watchosSimulatorArm64",
            "watchosX64",
            "tvosArm64",
            "tvosSimulatorArm64",
            "tvosX64",
        )
