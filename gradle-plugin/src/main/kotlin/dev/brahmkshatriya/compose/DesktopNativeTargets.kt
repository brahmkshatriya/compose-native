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
        get() {
            createTargets()
            return binaryContainer
        }

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
