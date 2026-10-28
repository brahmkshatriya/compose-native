package dev.brahmkshatriya.compose

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync

internal fun Project.addConventionalMainKotlinSources() {
    val sourceSets = kotlinSourceSets()
    val desktopNativeMain = sourceSets.getByName("desktopNativeMain")
    desktopNativeMain.addKotlinSourceDirectory(
        layout.projectDirectory.dir("src/main/kotlin").asFile
    )
}

internal fun Project.addConventionalMainComposeResources() {
    pluginManager.withPlugin("org.jetbrains.compose") {
        val mergedDirectory =
            layout.buildDirectory.dir("generated/composeNative/desktopNativeMain/composeResources")
        val prepareResources =
            tasks.register("prepareDesktopNativeComposeResources", Sync::class.java) { task ->
                task.from(layout.projectDirectory.dir("src/main/composeResources"))
                task.from(layout.projectDirectory.dir("src/desktopNativeMain/composeResources"))
                task.into(mergedDirectory)
                task.includeEmptyDirs = false
                task.duplicatesStrategy = DuplicatesStrategy.FAIL
            }
        val compose = extensions.getByName("compose") as ExtensionAware
        val resources = compose.extensions.getByName("resources")
        val customDirectory =
            resources.javaClass.methods.first {
                it.name == "customDirectory" && it.parameterCount == 2
            }
        customDirectory.invoke(
            resources,
            "desktopNativeMain",
            prepareResources.map { mergedDirectory.get() },
        )
    }
}

internal fun Project.configureExecutableResourceCopyTasks(target: DesktopNativeTarget) {
    val binarySourceSetPrefix = target.sourceSetPrefix
    val binaryTaskSuffix = target.taskSuffix
    val aggregateTaskName = "${binarySourceSetPrefix}AggregateResources"
    if (tasks.findByName(aggregateTaskName) == null) return
    listOf("debug", "release").forEach { buildType ->
        val capitalizedBuildType = buildType.replaceFirstChar(Char::uppercaseChar)
        val linkTaskName = "link${capitalizedBuildType}Executable$binaryTaskSuffix"
        val linkTask = tasks.findByName(linkTaskName) ?: return@forEach
        val copyTaskName = "copy${capitalizedBuildType}${binaryTaskSuffix}ExecutableResources"
        val copyTask =
            tasks.findByName(copyTaskName)
                ?: tasks
                    .register(copyTaskName, Copy::class.java) { task ->
                        task.dependsOn(aggregateTaskName)
                        task.from(
                            layout.buildDirectory.dir(
                                "kotlin-multiplatform-resources/aggregated-resources/$binarySourceSetPrefix"
                            )
                        )
                        task.into(
                            layout.buildDirectory.dir(
                                "bin/$binarySourceSetPrefix/${buildType}Executable/resources"
                            )
                        )
                    }
                    .get()
        linkTask.finalizedBy(copyTask)
        tasks
            .findByName("run${capitalizedBuildType}Executable$binaryTaskSuffix")
            ?.dependsOn(copyTask)
    }
}
