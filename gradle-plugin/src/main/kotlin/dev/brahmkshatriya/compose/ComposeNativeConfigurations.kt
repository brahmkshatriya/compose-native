package dev.brahmkshatriya.compose

internal fun String.isDesktopNativeConfiguration(): Boolean {
    val normalized = lowercase()
    return DESKTOP_NATIVE_CONFIGURATION_MARKERS.any(normalized::contains)
}

internal fun String.isSharedNativeMetadataConfiguration(): Boolean =
    SHARED_NATIVE_MAIN_CONFIGURATION_PREFIXES.any { startsWith(it, ignoreCase = true) } &&
        contains("metadata", ignoreCase = true)

internal fun String.isMetadataTransformationConfiguration(): Boolean =
    endsWith(RESOLVABLE_METADATA_CONFIGURATION_SUFFIX, ignoreCase = true)

internal fun String.usesNativeOverlay(): Boolean =
    isDesktopNativeConfiguration() || isSharedNativeMetadataConfiguration()

internal fun String.usesRedirectedNativeTarget(): Boolean {
    val normalized = lowercase()
    return REDIRECTED_NATIVE_CONFIGURATION_MARKERS.any(normalized::contains)
}

internal fun String.usesRedirectedWebTarget(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("wasmjs") || normalized.startsWith("js")
}

internal fun String.usesRedirectedJvmTarget(): Boolean = startsWith("jvm", ignoreCase = true)

internal fun String.isAndroidConfiguration(): Boolean = contains("android", ignoreCase = true)

private const val RESOLVABLE_METADATA_CONFIGURATION_SUFFIX = "ResolvableDependenciesMetadata"

private val DESKTOP_NATIVE_CONFIGURATION_MARKERS =
    listOf("desktopnative", "linuxx64", "linuxarm64", "mingwx64", "macosx64", "macosarm64")

private val REDIRECTED_NATIVE_CONFIGURATION_MARKERS =
    listOf(
        "linuxx64",
        "linuxarm64",
        "mingwx64",
        "macosarm64",
        "iosarm64",
        "iossimulatorarm64",
        "iosx64",
        "watchos",
        "tvos",
    )

private val SHARED_NATIVE_MAIN_CONFIGURATION_PREFIXES =
    listOf("desktopNativeMain", "linuxWindowsMain", "linuxMain", "macosMain")
