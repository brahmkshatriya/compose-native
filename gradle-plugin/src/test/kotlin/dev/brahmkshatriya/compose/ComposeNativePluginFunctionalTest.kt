package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import org.gradle.testkit.runner.GradleRunner

class ComposeNativePluginFunctionalTest {
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
                        kotlin("multiplatform") version "2.3.20"
                        id("dev.brahmkshatriya.compose")
                    }

                kotlin {
                    desktopNative {
                        binaries.executable {
                            entryPoint = "com.example.main"
                        }
                    }

                    sourceSets {
                        desktopNativeMain.dependencies {}
                    }
                }

                tasks.register("verifyDesktopNativeExecutables") {
                    doLast {
                        val sourceSets =
                            (project.extensions.getByName("kotlin") as KotlinSourceSetContainer)
                                .sourceSets
                        val desktopNativeMain = sourceSets.getByName("desktopNativeMain")
                        check(file("src/main/kotlin") in desktopNativeMain.kotlin.srcDirs)
                        check(project.extensions.findByName("composeNativeApplication") != null)
                        listOf("linuxX64Main", "linuxArm64Main", "mingwX64Main").forEach { name ->
                            check(desktopNativeMain in sourceSets.getByName(name).dependsOn)
                        }
                        listOf(
                            "linkDebugExecutableLinuxX64",
                            "linkDebugExecutableLinuxArm64",
                            "linkDebugExecutableMingwX64",
                            "prepareLinuxX64ReleaseAppDir",
                            "packageLinuxX64ReleaseAppImage",
                            "prepareLinuxArm64ReleaseAppDir",
                            "packageLinuxArm64ReleaseAppImage",
                            "prepareWindowsX64ReleaseDistribution",
                            "packageWindowsX64ReleaseZip",
                        ).forEach { name ->
                            check(tasks.findByName(name) != null)
                        }
                    }
                }
                """
                    .trimIndent()
            )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("verifyDesktopNativeExecutables", "--no-configuration-cache")
            .build()
    }
}
