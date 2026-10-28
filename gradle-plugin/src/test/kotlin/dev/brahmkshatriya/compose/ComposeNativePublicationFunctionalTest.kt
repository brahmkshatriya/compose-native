package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner

class ComposeNativePublicationFunctionalTest {
    @Test
    fun rewritesPublishedDesktopNativeSkikoMetadataWithoutChangingJvmMetadata() {
        val projectDir = createTempDirectory("compose-native-published-skiko-test").toFile()
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
                        mavenCentral()
                    }
                }
                rootProject.name = "compose-native-published-skiko-test"
                """
                    .trimIndent()
            )
        projectDir.resolve("src/commonMain/kotlin").mkdirs()
        projectDir.resolve("src/commonMain/kotlin/Example.kt").writeText("fun example() = Unit\n")
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("dev.brahmkshatriya.compose")
                    `maven-publish`
                }

                group = "com.example"
                version = "1.0.0"

                kotlin {
                    jvm()
                    desktopNative()

                    sourceSets {
                        commonMain.dependencies {
                            api("org.jetbrains.skiko:skiko:0.150.1")
                        }
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.skiko:skiko:0.151.5")
                        }
                    }
                }
                """
                    .trimIndent()
            )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "generateMetadataFileForMingwX64Publication",
                "generateMetadataFileForJvmPublication",
                "--no-configuration-cache",
            )
            .build()

        val nativeMetadata =
            projectDir.resolve("build/publications/mingwX64/module.json").readText()
        assertContains(nativeMetadata, "dev.brahmkshatriya.skiko")
        assertContains(nativeMetadata, "0.151.5")
        assertFalse("org.jetbrains.skiko" in nativeMetadata)

        val jvmMetadata = projectDir.resolve("build/publications/jvm/module.json").readText()
        assertContains(jvmMetadata, "org.jetbrains.skiko")
        assertContains(jvmMetadata, "0.150.1")
        assertFalse("dev.brahmkshatriya.skiko" in jvmMetadata)
    }
}
