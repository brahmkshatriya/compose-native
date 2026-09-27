package dev.brahmkshatriya.compose

import org.gradle.api.Action
import org.gradle.api.Project

/** Adds all desktop-native Kotlin targets when invoked from the `kotlin` block. */
open class DesktopNativeTargets internal constructor(private val project: Project) {
    private var targetsCreated = false
    private val binaryContainer = DesktopNativeBinaries { executable ->
        createTargets()
        project.configureDesktopNativeExecutable(executable)
    }
    val binaries: DesktopNativeBinaries
        get() = binaryContainer

    operator fun invoke() {
        createTargets()
    }

    private fun createTargets() {
        if (targetsCreated) return
        targetsCreated = true
        project.createDesktopNativeTargets()
    }
}

open class DesktopNativeBinaries
internal constructor(private val onExecutable: (DesktopNativeExecutable) -> Unit) {
    internal var executable: DesktopNativeExecutable? = null
        private set

    fun executable(action: Action<DesktopNativeExecutable>) {
        check(executable == null) {
            "desktopNative.binaries.executable may only be configured once"
        }
        executable = DesktopNativeExecutable().also(action::execute)
        onExecutable(executable!!)
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
    val os = osName.lowercase()
    val arch = architecture.lowercase()
    val x64 = arch in setOf("amd64", "x86_64", "x64")
    val arm64 = arch in setOf("aarch64", "arm64")
    return when {
        os.contains("linux") && x64 ->
            HostDesktopNativeTarget("linuxX64", "linux_x64", "LinuxX64")
        os.contains("linux") && arm64 ->
            HostDesktopNativeTarget("linuxArm64", "linux_arm64", "LinuxArm64")
        os.contains("windows") && x64 ->
            HostDesktopNativeTarget("mingwX64", "mingw_x64", "MingwX64")
        os.contains("mac") && x64 ->
            HostDesktopNativeTarget("macosX64", "macos_x64", "MacosX64")
        os.contains("mac") && arm64 ->
            HostDesktopNativeTarget("macosArm64", "macos_arm64", "MacosArm64")
        else -> null
    }
}
