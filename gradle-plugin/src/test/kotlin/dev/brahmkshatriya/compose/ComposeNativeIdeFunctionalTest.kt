package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class ComposeNativeIdeFunctionalTest {
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
                include(":app")
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    jvm()
                    linuxX64()
                    mingwX64()

                    sourceSets {
                        commonMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha14")
                            implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
                        }
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.13.0-alpha05")
                            implementation(project(":app"))
                        }
                    }
                }
                """
                    .trimIndent()
            )
        projectDir.resolve("app").mkdirs()
        projectDir
            .resolve("app/build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform")
                }

                kotlin {
                    jvm()
                    linuxX64()
                    mingwX64()

                    sourceSets {
                        commonMain.dependencies {
                            api("dev.brahmkshatriya.material-kolor:material-kolor:5.0.2")
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
            "org.jetbrains.compose.ui:ui:1.13.0-beta01",
            message = "The IDE model must contain official Compose UI metadata",
        )
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.ui:ui-unit:1.13.0-beta01",
            message = "The IDE model must contain the metadata that defines Dp and dp",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.foundation:foundation:commonMain:1.12.10-alpha14",
            message = "The IDE model must preserve full-fork common Foundation metadata",
        )
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.foundation:foundation-layout:1.12.0-rc01",
            message = "The IDE model must contain transitive upstream Foundation metadata",
        )
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.animation:animation:1.12.0-rc01",
            message = "The IDE model must contain transitive upstream Animation metadata",
        )
        assertFalse(
            "dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha05" in commonMainModel,
            "The native UI overlay must not leak into commonMain",
        )

        val desktopNativeMainModel =
            projectDir.resolve("build/ide/dependencies/json/desktopNativeMain.json").readText()
        assertContains(
            desktopNativeMainModel,
            "dev.brahmkshatriya.compose.desktop:desktop-native:desktopNativeMain:1.13.0-alpha05",
            message = "The IDE model must contain shared desktop-native metadata",
        )
        assertFalse(
            "dev.brahmkshatriya.compose.ui:ui-linuxx64:" in desktopNativeMainModel,
            "The all-desktop source set must not be modeled as a Linux leaf target",
        )
        assertFalse(
            "dev.brahmkshatriya.material-kolor:material-kolor-linuxx64:" in desktopNativeMainModel,
            "Project dependencies must not leak Linux leaf KLIBs into desktopNativeMain",
        )
    }

    @Test
    fun includesAndroidxCommonMetadataInTheCommonIdeModel() {
        assertTrue(isSkiaBackendIdeDependency("org.jetbrains.compose.ui", "ui-skiko"))
        assertTrue(isSkiaBackendIdeDependency("org.jetbrains.skiko", "skiko"))
        assertFalse(isCommonComposeIdeDependency("org.jetbrains.compose.ui", "ui-skiko"))
        assertFalse(isSkiaBackendIdeDependency("dev.brahmkshatriya.skiko", "skiko"))
        assertTrue(
            isCommonComposeIdeDependency(
                group = "dev.brahmkshatriya.compose.foundation",
                module = "foundation",
            )
        )
        assertTrue(
            isCommonComposeIdeDependency(
                group = "dev.brahmkshatriya.compose.material3",
                module = "material3",
            )
        )
        assertFalse(
            isCommonComposeIdeDependency(group = "dev.brahmkshatriya.compose.ui", module = "ui")
        )
        assertTrue(
            isOfficialCommonIdeDependency(
                group = "androidx.navigationevent",
                module = "navigationevent-compose",
            )
        )
        assertTrue(
            isOfficialCommonIdeDependency(
                group = "androidx.navigationevent",
                module = "navigationevent",
            )
        )
    }

    @Test
    fun keepsFullForkCommonMetadataCoordinatesOnFork() {
        val projectDir = createTempDirectory("compose-native-common-metadata-test").toFile()
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
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "compose-native-common-metadata-test"
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    linuxX64()
                    js()

                    sourceSets {
                        commonMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.androidx.collection:collection:1.13.0-alpha06")
                        }
                    }
                }
                """
                    .trimIndent()
            )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(
                    "dependencies",
                    "--configuration",
                    "commonMainResolvableDependenciesMetadata",
                    "--no-configuration-cache",
                )
                .build()

        assertContains(
            result.output,
            "dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06",
        )
        assertContains(
            result.output,
            "dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06",
        )
        assertContains(result.output, "dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha06")
        assertContains(
            result.output,
            "dev.brahmkshatriya.androidx.collection:collection:1.13.0-alpha06",
        )
    }

    @Test
    fun keepsOfficialUiSkikoInCustomIntermediateMetadata() {
        val projectDir = createTempDirectory("compose-native-skia-metadata-test").toFile()
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
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "compose-native-skia-metadata-test"
                """
                    .trimIndent()
            )
        projectDir.resolve("src/skiaMain/kotlin").mkdirs()
        projectDir
            .resolve("src/skiaMain/kotlin/SkiaMetadata.kt")
            .writeText(
                """
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.staticCompositionLocalOf
                import androidx.compose.ui.graphics.ImageBitmap
                import androidx.compose.ui.graphics.toComposeImageBitmap
                import org.jetbrains.skia.Image

                val LocalMetadataValue = staticCompositionLocalOf { 0 }

                @Composable
                fun metadataValue(): Int = LocalMetadataValue.current

                fun Image.asComposeBitmap(): ImageBitmap = toComposeImageBitmap()
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    jvm()
                    desktopNative()

                    sourceSets {
                        val skiaMain by creating {
                            dependsOn(commonMain.get())
                            dependencies {
                                implementation("androidx.compose.runtime:runtime:1.13.0-alpha03")
                                implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
                                implementation("org.jetbrains.skiko:skiko:0.152.0-alpha02")
                            }
                        }
                        jvmMain.get().dependsOn(skiaMain)
                        linuxX64Main.get().dependsOn(skiaMain)
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha05")
                        }
                    }
                }
                """
                    .trimIndent()
            )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("compileSkiaMainKotlinMetadata", "--no-configuration-cache")
                .build()

        assertContains(result.output, "BUILD SUCCESSFUL")
    }

    @Test
    fun includesAndroidxRuntimeInTheCommonMetadataRepair() {
        assertTrue(
            isOfficialCommonIdeDependency(group = "androidx.compose.runtime", module = "runtime")
        )
        assertTrue(
            isOfficialCommonIdeDependency(
                group = "androidx.compose.runtime",
                module = "runtime-annotation",
            )
        )
        assertTrue(
            isOfficialCommonIdeDependency(
                group = "androidx.compose.runtime",
                module = "runtime-saveable",
            )
        )
    }
}
