package dev.brahmkshatriya.compose

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

internal fun Project.selectDesktopNativeExecutableTargets(): List<DesktopNativeTarget> {
    val targetNames = kotlinNativeTargets().names
    return DESKTOP_NATIVE_TARGETS.filter { it.sourceSetPrefix in targetNames }
        .ifEmpty {
            createDesktopNativeTargets()
            DESKTOP_NATIVE_TARGETS
        }
}

internal fun Project.configureDesktopNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    executableTargets: List<DesktopNativeTarget>,
) {
    val targets = kotlinNativeTargets()
    val hostTarget =
        hostDesktopNativeTarget(System.getProperty("os.name"), System.getProperty("os.arch"))
    executableTargets.forEach { target ->
        targets
            .getByName(target.sourceSetPrefix)
            .configureNativeExecutable(
                executableSpec = executableSpec,
                buildTypes =
                    if (target.konanName == hostTarget?.nativeTarget) {
                        setOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE)
                    } else {
                        setOf(NativeBuildType.RELEASE)
                    },
            )
    }
    if (hostTarget != null) configureHostDesktopRunAliases(hostTarget)
}

internal fun Project.createDesktopNativeTargets() {
    val targets = kotlinNativeTargets()
    DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.forEach { targetName ->
        if (targetName !in targets.names) createKotlinNativeTarget(targetName)
    }
}

private fun Project.configureHostDesktopRunAliases(hostTarget: HostDesktopNativeTarget) {
    afterEvaluate {
        listOf("Debug", "Release").forEach { buildType ->
            val sourceTaskName = "run${buildType}Executable${hostTarget.concreteTaskSuffix}"
            val sourceTask = tasks.findByName(sourceTaskName) as? Exec ?: return@forEach
            val aliasTaskName = "run${buildType}Executable$HOST_DESKTOP_TARGET_NAME_CAPITALIZED"
            if (tasks.findByName(aliasTaskName) == null) {
                tasks.register(aliasTaskName, Exec::class.java) { alias ->
                    alias.group = sourceTask.group
                    alias.description =
                        "Executes Kotlin/Native ${buildType.lowercase()} executable for the current desktop host."
                    alias.enabled = sourceTask.enabled
                    alias.commandLine(sourceTask.commandLine)
                    alias.workingDir = sourceTask.workingDir
                    alias.environment(sourceTask.environment)
                    alias.dependsOn(sourceTask.taskDependencies.getDependencies(sourceTask))
                }
            }
        }
    }
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    buildTypes: Set<NativeBuildType>,
) {
    val binaries = nativeBinaries()
    val existingExecutables =
        (binaries as Iterable<*>).filterNotNull().filter { it.isNativeExecutable() }
    val configuredBuildTypes =
        existingExecutables.mapNotNull { binary ->
            val buildType = binary.nativeBuildType()
            if (buildType in buildTypes) {
                binary.configureNativeExecutable(executableSpec)
                buildType
            } else {
                null
            }
        }
    val missingBuildTypes = buildTypes - configuredBuildTypes.toSet()
    if (missingBuildTypes.isEmpty()) return

    binaries.createNativeExecutables(
        missingBuildTypes,
        Action<Any> { binary -> binary.configureNativeExecutable(executableSpec) },
    )
}

private fun Any.configureNativeExecutable(executableSpec: DesktopNativeExecutable) {
    setNativeEntryPoint(executableSpec.requiredEntryPoint())
    addNativeLinkerOptions(executableSpec.linkerOptions)
}

private const val HOST_DESKTOP_TARGET_NAME_CAPITALIZED = "Desktop"
