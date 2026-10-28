package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class DesktopNativeApplicationFunctionalTest {
    @Test
    fun createsDesktopNativeExecutablesAndSourceSetHierarchy() {
        val projectDir = createTempDirectory("compose-native-hierarchy-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories {
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories { mavenCentral() }
                }
                rootProject.name = "compose-native-hierarchy-test"
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                    import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer

                    plugins {
                        kotlin("multiplatform") version "$functionalTestKotlinVersion"
                        id("dev.brahmkshatriya.compose")
                    }

                kotlin {
                    desktopNative {
                        binaries.executable {
                            entryPoint = "com.example.main"
                            linkerOpts("-L" + file("custom linker path").absolutePath)
                        }
                    }
                    iosArm64()
                    iosSimulatorArm64()
                    iosX64()
                    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                    wasmJs()

                    sourceSets {
                        desktopNativeMain.dependencies {}
                    }
                }

                composeNativeApplication {
                    linuxX64LibraryDirectories.setFrom(file("libraries/linux x64"))
                    linuxArm64LibraryDirectories.setFrom(file("libraries/linux arm64"))
                    macosX64LibraryDirectories.setFrom(file("libraries/macos x64"))
                    macosArm64LibraryDirectories.setFrom(file("libraries/macos arm64"))
                }

                fun org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet.withDependsOnClosure(): Set<org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet> =
                    setOf(this) + dependsOn.flatMap { it.withDependsOnClosure() }

                val kotlinForVerification = project.extensions.getByName("kotlin")
                @Suppress("UNCHECKED_CAST")
                val targetsForVerification =
                    kotlinForVerification.javaClass.methods
                        .first { it.name == "getTargets" && it.parameterCount == 0 }
                        .invoke(kotlinForVerification) as org.gradle.api.NamedDomainObjectContainer<Any>
                val linuxX64TargetForVerification = targetsForVerification.getByName("linuxX64")

                tasks.register("linuxX64AggregateResources")

                tasks.register("verifyDesktopNativeExecutables") {
                    doLast {
                        val sourceSets =
                            (project.extensions.getByName("kotlin") as KotlinSourceSetContainer)
                                .sourceSets
                        val desktopNativeMain = sourceSets.getByName("desktopNativeMain")
                        check(file("src/main/kotlin") in desktopNativeMain.kotlin.srcDirs)
                        val skiaSources = file("src/skiaTargetMain/kotlin")
                        listOf(
                            "linuxX64Main",
                            "linuxArm64Main",
                            "mingwX64Main",
                            "macosX64Main",
                            "macosArm64Main",
                            "iosArm64Main",
                            "iosSimulatorArm64Main",
                            "iosX64Main",
                            "wasmJsMain",
                        ).forEach { name ->
                            val target = sourceSets.getByName(name)
                            check(skiaSources !in target.kotlin.srcDirs)
                            check(sourceSets.getByName("skiaTargetMain") in target.withDependsOnClosure())
                        }
                        val skia = sourceSets.getByName("skiaTargetMain")
                        check(sourceSets.filter { skiaSources in it.kotlin.srcDirs } == listOf(skia))
                        check(sourceSets.getByName("commonMain") in skia.dependsOn)
                        check(skia in desktopNativeMain.dependsOn)
                        check(project.extensions.findByName("composeNativeApplication") != null)
                        val nativeTarget =
                            org.gradle.api.attributes.Attribute.of(
                                "org.jetbrains.kotlin.native.target",
                                String::class.java,
                            )
                        check(
                            configurations
                                .getByName("desktopNativeMainResolvableDependenciesMetadata")
                                .attributes
                                .getAttribute(nativeTarget) == null
                        )
                        val linuxWindowsMain = sourceSets.getByName("linuxWindowsMain")
                        check(desktopNativeMain in linuxWindowsMain.dependsOn)
                        val linuxMain = sourceSets.getByName("linuxMain")
                        check(linuxWindowsMain in linuxMain.dependsOn)
                        listOf("linuxX64Main", "linuxArm64Main").forEach { name ->
                            check(linuxMain in sourceSets.getByName(name).dependsOn)
                        }
                        check(linuxWindowsMain in sourceSets.getByName("mingwX64Main").dependsOn)
                        val macosMain = sourceSets.getByName("macosMain")
                        check(desktopNativeMain in macosMain.dependsOn)
                        check(sourceSets.getByName("appleMain") in macosMain.dependsOn)
                        listOf("macosX64Main", "macosArm64Main").forEach { name ->
                            val sourceSet = sourceSets.getByName(name)
                            check(macosMain in sourceSet.dependsOn)
                            check(linuxWindowsMain !in sourceSet.dependsOn)
                        }
                        listOf("Main", "Test").forEach { compilation ->
                            listOf("apple", "macos", "linux").forEach { platform ->
                                val sharedName = platform + compilation
                                val directory = file("src/" + sharedName + "/kotlin")
                                val owners = sourceSets.filter { directory in it.kotlin.srcDirs }
                                check(owners.map { it.name } == listOf(sharedName)) {
                                    "A shared directory must have one IDE source-set owner: " + owners
                                }
                            }
                            val apple = sourceSets.getByName("apple" + compilation)
                            val macos = sourceSets.getByName("macos" + compilation)
                            val linux = sourceSets.getByName("linux" + compilation)
                            check(sourceSets.getByName("common" + compilation) in apple.dependsOn)
                            check(apple in macos.dependsOn)
                            check(sourceSets.getByName("desktopNative" + compilation) in macos.dependsOn)
                            check(sourceSets.getByName("linuxWindows" + compilation) in linux.dependsOn)
                            listOf("macosX64", "macosArm64", "iosArm64", "iosSimulatorArm64", "iosX64").forEach { target ->
                                check(apple in sourceSets.getByName(target + compilation).dependsOn)
                            }
                            listOf("macosX64", "macosArm64").forEach { target ->
                                check(macos in sourceSets.getByName(target + compilation).dependsOn)
                            }
                            listOf("linuxX64", "linuxArm64").forEach { target ->
                                check(linux in sourceSets.getByName(target + compilation).dependsOn)
                            }
                            check(linux !in sourceSets.getByName("macosArm64" + compilation).dependsOn)
                            check(macos !in sourceSets.getByName("iosArm64" + compilation).dependsOn)
                        }
                        check(sourceSets.findByName("desktopMain") == null)
                        listOf(
                            "linkDebugExecutableLinuxX64",
                            "linkReleaseExecutableLinuxX64",
                            "linkReleaseExecutableLinuxArm64",
                            "linkReleaseExecutableMingwX64",
                            "linkReleaseExecutableMacosX64",
                            "linkReleaseExecutableMacosArm64",
                            "copyReleaseMingwX64ExecutableRuntime",
                            "prepareWindowsX64CxxRuntime",
                            "prepareLinuxX64ReleaseAppDir",
                            "packageLinuxX64ReleaseAppImage",
                            "prepareLinuxArm64ReleaseAppDir",
                            "packageLinuxArm64ReleaseAppImage",
                            "prepareWindowsX64ReleaseDistribution",
                            "packageWindowsX64ReleaseZip",
                            "packageWindowsX64ReleaseMsi",
                            "packageWindowsX64ReleaseInstallerExe",
                            "packageWindowsX64ReleaseInstaller",
                            "prepareMacosX64ReleaseAppBundle",
                            "packageMacosX64ReleaseDmg",
                            "prepareMacosArm64ReleaseAppBundle",
                            "packageMacosArm64ReleaseDmg",
                        ).forEach { name ->
                            check(tasks.findByName(name) != null)
                        }
                        check(tasks.findByName("runDebugExecutableLinuxX64") != null)
                        listOf(
                            "runDebugExecutableMingwX64",
                            "runDebugExecutableMacosX64",
                            "runDebugExecutableMacosArm64",
                        ).forEach { name ->
                            check(tasks.findByName(name) == null)
                        }
                        val runTask = tasks.getByName("runDebugExecutableDesktop")
                        check(runTask is org.gradle.api.tasks.Exec)
                        val copyTask = tasks.getByName("copyDebugLinuxX64ExecutableResources")
                        check(copyTask in runTask.taskDependencies.getDependencies(runTask))
                        check(tasks.findByName("runReleaseExecutableDesktop") is org.gradle.api.tasks.Exec)

                        val importedTargetName =
                            linuxX64TargetForVerification.javaClass.methods
                                .first { it.name == "getTargetName" && it.parameterCount == 0 }
                                .invoke(linuxX64TargetForVerification)
                        check(importedTargetName == "linuxX64")

                        val binaries =
                            linuxX64TargetForVerification.javaClass.methods
                                .first { it.name == "getBinaries" && it.parameterCount == 0 }
                                .invoke(linuxX64TargetForVerification) as Iterable<*>
                        val debugExecutable =
                            binaries.filterNotNull().first { binary ->
                                binary.javaClass.methods
                                    .firstOrNull {
                                        it.name == "getName" && it.parameterCount == 0
                                    }
                                    ?.invoke(binary)
                                    ?.toString() == "debugExecutable"
                            }
                        val importedRunTaskName =
                            debugExecutable.javaClass.methods
                                .first { it.name == "getRunTaskName" && it.parameterCount == 0 }
                                .invoke(debugExecutable)
                        check(importedRunTaskName == "runDebugExecutableLinuxX64")

                        listOf("linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64").forEach { name ->
                            val target = targetsForVerification.getByName(name)
                            val targetBinaries = target.javaClass.methods
                                .first { it.name == "getBinaries" && it.parameterCount == 0 }
                                .invoke(target) as Iterable<*>
                            targetBinaries.filterNotNull().filter { binary ->
                                binary.javaClass.methods.any { it.name == "setEntryPoint" && it.parameterCount == 1 }
                            }.forEach { binary ->
                                val options = binary.javaClass.methods
                                    .first { it.name == "getLinkerOpts" && it.parameterCount == 0 }
                                    .invoke(binary) as List<*>
                                check(("-L" + file("custom linker path").absolutePath) in options) { options }
                                check(("-lEGL" in options) == name.startsWith("linux")) { options }
                                check(("-lSDL3" in options) == name.startsWith("macos")) { options }
                                val platformPaths = options.filterIsInstance<String>().filter { it.contains("libraries/") }
                                if (name == "mingwX64") check(platformPaths.isEmpty())
                                else {
                                    val directory = when (name) {
                                        "linuxX64" -> "linux x64"
                                        "linuxArm64" -> "linux arm64"
                                        "macosX64" -> "macos x64"
                                        else -> "macos arm64"
                                    }
                                    check(platformPaths == listOf("-L" + file("libraries/" + directory).absolutePath)) { options }
                                }
                            }
                        }

                        listOf("LinuxX64" to "linux x64", "LinuxArm64" to "linux arm64").forEach { (suffix, directory) ->
                            val prepare = tasks.getByName("prepare" + suffix + "ReleaseAppDir") as dev.brahmkshatriya.compose.PrepareLinuxAppDirTask
                            check(prepare.sdlRuntimeFiles.files == setOf(file("libraries/" + directory + "/libSDL3.so.0")))
                        }

                        val windowsRunTask = tasks.getByName("runReleaseExecutableMingwX64")
                        val runtimeCopyTask = tasks.getByName("copyReleaseMingwX64ExecutableRuntime")
                        val windowsLinkTask = tasks.getByName("linkReleaseExecutableMingwX64")
                        check(
                            runtimeCopyTask in windowsRunTask.taskDependencies.getDependencies(windowsRunTask)
                        )
                        check(
                            windowsLinkTask in runtimeCopyTask.taskDependencies.getDependencies(runtimeCopyTask)
                        )
                    }
                }
                """
                    .trimIndent()
            )

        mapOf(
                "commonMain" to "expect fun skiaText(): String",
                "skiaTargetMain" to
                    "private val text = \"shared\"\nactual fun skiaText(): String = text",
                "desktopNativeMain" to
                    "expect fun platformName(): String\nfun main() { check(skiaText() == \"shared\") }",
                "linuxMain" to "actual fun platformName(): String = \"linux\"",
                "macosMain" to "actual fun platformName(): String = \"macos\"",
                "mingwX64Main" to "actual fun platformName(): String = \"windows\"",
            )
            .forEach { (sourceSet, code) ->
                projectDir.resolve("src/$sourceSet/kotlin/Platform.kt").apply {
                    parentFile.mkdirs()
                    writeText("package com.example\n$code\n")
                }
            }

        listOf("linux x64" to 62, "linux arm64" to 183).forEach { (name, machine) ->
            projectDir.resolve("libraries/$name/libSDL3.so.0").apply {
                parentFile.mkdirs()
                writeBytes(
                    byteArrayOf(0x7f, 69, 76, 70, 2, 1) +
                        ByteArray(12) +
                        byteArrayOf(machine.toByte(), 0)
                )
            }
        }

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "-Didea.sync.active=true",
                "verifyDesktopNativeExecutables",
                "compileKotlinLinuxX64",
                "compileKotlinLinuxArm64",
                "--no-configuration-cache",
            )
            .build()
    }

    @Test
    fun supportsConventionalAndKmpComposeResourceDirectories() {
        val projectDir = createTempDirectory("compose-native-resources-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories {
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                rootProject.name = "compose-native-resources-test"
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("org.jetbrains.kotlin.plugin.compose")
                    id("org.jetbrains.compose")
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    desktopNative {
                        binaries.executable {
                            entryPoint = "com.example.main"
                        }
                    }
                }
                """
                    .trimIndent()
            )
        projectDir.resolve("src/main/composeResources/files/from-main.txt").apply {
            parentFile.mkdirs()
            writeText("main")
        }
        projectDir.resolve("src/desktopNativeMain/composeResources/files/from-kmp.txt").apply {
            parentFile.mkdirs()
            writeText("desktopNativeMain")
        }

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("prepareDesktopNativeComposeResources", "--no-configuration-cache")
            .build()

        val mergedResources =
            projectDir.resolve(
                "build/generated/composeNative/desktopNativeMain/composeResources/files"
            )
        assertTrue(mergedResources.resolve("from-main.txt").isFile)
        assertTrue(mergedResources.resolve("from-kmp.txt").isFile)
    }
}
