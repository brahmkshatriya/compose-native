package dev.brahmkshatriya.compose

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

class ComposeNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.addDesktopNativeSourceSets()
    }
}

internal fun Project.configureDesktopNativeExecutable(executableSpec: DesktopNativeExecutable) {
    val entryPoint = executableSpec.requiredEntryPoint()
    val kotlin = extensions.getByName("kotlin")
    @Suppress("UNCHECKED_CAST")
    val targets =
        kotlin.javaClass.methods
            .single { it.name == "getTargets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    DESKTOP_NATIVE_TARGET_SOURCE_SETS.forEach { (nativeTarget, targetName) ->
        val target = targets.getByName(targetName)
        val binaries =
            target.javaClass.methods
                .single {
                    it.name == "getBinaries" &&
                        it.parameterCount == 0 &&
                        it.returnType.name == KOTLIN_NATIVE_BINARY_CONTAINER_CLASS
                }
                .invoke(target)
        val existingExecutables =
            (binaries as Iterable<*>).filterNotNull().filter { binary ->
                binary.javaClass.methods.any {
                    it.name == "setEntryPoint" && it.parameterCount == 1
                }
            }
        if (existingExecutables.isNotEmpty()) {
            existingExecutables.forEach { binary ->
                binary.configureNativeExecutable(executableSpec, nativeTarget)
            }
            return@forEach
        }
        val executable =
            binaries.javaClass.methods.single {
                it.name == "executable" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single() == Action::class.java
            }
        executable.invoke(
            binaries,
            Action<Any> { binary -> binary.configureNativeExecutable(executableSpec, nativeTarget) },
        )
    }
}

internal fun Project.createDesktopNativeTargets() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.forEach { targetName ->
            kotlin.javaClass.methods
                .single { it.name == targetName && it.parameterCount == 0 }
                .invoke(kotlin)
        }
    }
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    nativeTarget: String,
) {
    javaClass.methods
        .single {
            it.name == "setEntryPoint" &&
                it.parameterCount == 1 &&
                it.parameterTypes.single() == String::class.java
        }
        .invoke(this, executableSpec.requiredEntryPoint())
    val linkerOptions =
        executableSpec.linkerOptions +
            if (nativeTarget.startsWith("linux_")) DEFAULT_LINUX_LINKER_OPTIONS else emptyList()
    if (linkerOptions.isNotEmpty()) {
        javaClass.methods
            .single {
                it.name == "linkerOpts" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single() == Iterable::class.java
            }
            .invoke(this, linkerOptions)
    }
}

private fun Project.addDesktopNativeSourceSets() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        (kotlin as ExtensionAware).extensions.add("desktopNative", DesktopNativeTargets(this))
        @Suppress("UNCHECKED_CAST")
        val sourceSets =
            kotlin.javaClass.methods
                .single { it.name == "getSourceSets" && it.parameterCount == 0 }
                .invoke(kotlin) as NamedDomainObjectContainer<Any>
        val desktopNativeMain = sourceSets.maybeCreate("desktopNativeMain")
        val desktopNativeTest = sourceSets.maybeCreate("desktopNativeTest")

        desktopNativeMain.dependsOnSourceSet(sourceSets.getByName("commonMain"))
        desktopNativeTest.dependsOnSourceSet(sourceSets.getByName("commonTest"))

        afterEvaluate {
            DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.forEach { sourceSetPrefix ->
                sourceSets
                    .findByName("${sourceSetPrefix}Main")
                    ?.dependsOnSourceSet(desktopNativeMain)
                sourceSets
                    .findByName("${sourceSetPrefix}Test")
                    ?.dependsOnSourceSet(desktopNativeTest)
            }
        }
    }
}

private fun Any.dependsOnSourceSet(sourceSet: Any) {
    javaClass.methods
        .single { it.name == "dependsOn" && it.parameterCount == 1 }
        .invoke(this, sourceSet)
}

private const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val KOTLIN_NATIVE_BINARY_CONTAINER_CLASS =
    "org.jetbrains.kotlin.gradle.dsl.KotlinNativeBinaryContainer"
private val DEFAULT_LINUX_LINKER_OPTIONS = listOf("-L/usr/lib")

private val DESKTOP_NATIVE_TARGET_SOURCE_SETS =
    mapOf("linux_x64" to "linuxX64", "linux_arm64" to "linuxArm64", "mingw_x64" to "mingwX64")
