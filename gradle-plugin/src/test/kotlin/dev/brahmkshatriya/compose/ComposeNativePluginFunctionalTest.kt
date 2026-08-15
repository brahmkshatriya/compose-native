package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class ComposeNativePluginFunctionalTest {
    @Test
    fun addsOfficialComposeUiMetadataToTheCommonMainIdeModel() {
        val projectDir = createTempDirectory("compose-native-ide-dependencies-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }
                }
                rootProject.name = "compose-native-ide-dependencies-test"
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "2.4.10"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    jvm()
                    linuxX64()
                    mingwX64()

                    sourceSets {
                        commonMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha06")
                            implementation("org.jetbrains.compose.ui:ui:1.12.0-rc01")
                        }
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha06")
                        }
                    }
                }
                """
                    .trimIndent()
            )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("resolveIdeDependencies", "--no-configuration-cache")
            .build()

        val commonMainModel =
            projectDir.resolve("build/ide/dependencies/json/commonMain.json").readText()
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.ui:ui:1.12.0-rc01",
            message = "The IDE model must contain official Compose UI metadata",
        )
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.ui:ui-unit:1.12.0-rc01",
            message = "The IDE model must contain the metadata that defines Dp and dp",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.foundation:foundation:commonMain:1.12.10-alpha06",
            message = "KGP's normal transformed-metadata resolver must remain active",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.foundation:foundation-layout:1.12.10-alpha06",
            message = "The IDE model must contain transitive fork Foundation metadata",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.animation:animation:1.12.10-alpha06",
            message = "The IDE model must contain transitive fork Animation metadata",
        )
        assertFalse(
            "dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha06" in commonMainModel,
            "The native UI overlay must not leak into commonMain",
        )

        val desktopNativeMainModel =
            projectDir.resolve("build/ide/dependencies/json/desktopNativeMain.json").readText()
        assertContains(
            desktopNativeMainModel,
            "dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha06",
            message = "The IDE model must contain the native UI overlay metadata",
        )
        assertContains(
            desktopNativeMainModel,
            "org.jetbrains.compose.components:components-resources:1.12.0-rc01",
            message = "The IDE model must contain Compose Resources metadata",
        )
    }

    @Test
    fun includesNavigationEventComposeInTheCommonIdeModel() {
        assertTrue(
            isOfficialCommonIdeDependency(
                group = "androidx.navigationevent",
                module = "navigationevent-compose",
            )
        )
        assertFalse(
            isOfficialCommonIdeDependency(
                group = "androidx.navigationevent",
                module = "navigationevent",
            )
        )
    }

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

                tasks.register("linuxX64AggregateResources")

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
                        val runTask = tasks.getByName("runDebugExecutableLinuxX64")
                        val copyTask = tasks.getByName("copyDebugLinuxX64ExecutableResources")
                        check(copyTask in runTask.taskDependencies.getDependencies(runTask))
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
