package dev.brahmkshatriya.compose

import org.gradle.api.Action
import org.gradle.api.Project

/**
 * Configures desktop-native executables and provides a shortcut to declare every desktop target.
 */
open class DesktopNativeTargets internal constructor(private val project: Project) {
    val binaries = DesktopNativeBinaries()

    operator fun invoke() {
        project.createDesktopNativeTargets()
    }
}

open class DesktopNativeBinaries internal constructor() {
    internal var executable: DesktopNativeExecutable? = null
        private set

    fun executable(action: Action<DesktopNativeExecutable>) {
        check(executable == null) {
            "desktopNative.binaries.executable may only be configured once"
        }
        val spec = DesktopNativeExecutable()
        action.execute(spec)
        spec.requiredEntryPoint()
        executable = spec
    }
}

open class DesktopNativeExecutable {
    lateinit var entryPoint: String
    internal val linkerOptions = mutableListOf<String>()

    fun linkerOpts(vararg options: String) {
        linkerOptions += options
    }

    internal fun requiredEntryPoint(): String {
        check(::entryPoint.isInitialized && entryPoint.isNotBlank()) {
            "desktopNative.binaries.executable requires a non-blank entryPoint"
        }
        return entryPoint
    }
}

internal data class HostDesktopNativeTarget(
    val factoryMethodName: String,
    val nativeTarget: String,
    val concreteTaskSuffix: String,
)

internal fun hostDesktopNativeTarget(
    osName: String,
    architecture: String,
): HostDesktopNativeTarget? {
    val targetName =
        when {
            osName.contains("linux", ignoreCase = true) -> "linux"
            osName.contains("windows", ignoreCase = true) -> "mingw"
            osName.contains("mac", ignoreCase = true) -> "macos"
            else -> return null
        } +
            when (architecture.lowercase()) {
                "amd64",
                "x86_64",
                "x64" -> "X64"
                "aarch64",
                "arm64" -> "Arm64"
                else -> return null
            }
    val target =
        DESKTOP_NATIVE_TARGETS.singleOrNull { it.sourceSetPrefix == targetName } ?: return null
    return HostDesktopNativeTarget(target.sourceSetPrefix, target.konanName, target.taskSuffix)
}
