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
                include(":app")
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
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha12")
                            implementation("org.jetbrains.compose.ui:ui:1.12.0-rc01")
                        }
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha12")
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
                            api("dev.brahmkshatriya.material-kolor:material-kolor:5.0.1")
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
            "dev.brahmkshatriya.compose.foundation:foundation:commonMain:1.12.10-alpha12",
            message = "KGP's normal transformed-metadata resolver must remain active",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.foundation:foundation-layout:1.12.10-alpha12",
            message = "The IDE model must contain transitive fork Foundation metadata",
        )
        assertContains(
            commonMainModel,
            "dev.brahmkshatriya.compose.animation:animation:1.12.10-alpha12",
            message = "The IDE model must contain transitive fork Animation metadata",
        )
        assertFalse(
            "dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha12" in commonMainModel,
            "The native UI overlay must not leak into commonMain",
        )

        val desktopNativeMainModel =
            projectDir.resolve("build/ide/dependencies/json/desktopNativeMain.json").readText()
        assertContains(
            desktopNativeMainModel,
            "dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha12",
            message = "The IDE model must contain the native UI overlay metadata",
        )
        assertContains(
            desktopNativeMainModel,
            "dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha12",
            message = "The IDE model must contain the desktop window API metadata",
        )
        assertContains(
            desktopNativeMainModel,
            "dev.brahmkshatriya.material-kolor:material-kolor:5.0.1",
            message = "The IDE model must contain metadata exported by a project dependency",
        )
        assertContains(
            desktopNativeMainModel,
            "org.jetbrains.compose.components:components-resources:1.12.0-rc01",
            message = "The IDE model must contain Compose Resources metadata",
        )
    }

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
                    kotlin("multiplatform") version "2.4.10"
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
                    macosX64 {
                        binaries.executable {
                            entryPoint = "com.example.main"
                        }
                    }
                    macosArm64 {
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
                        val nativeTarget =
                            org.gradle.api.attributes.Attribute.of(
                                "org.jetbrains.kotlin.native.target",
                                String::class.java,
                            )
                        check(
                            configurations
                                .getByName("desktopNativeMainResolvableDependenciesMetadata")
                                .attributes
                                .getAttribute(nativeTarget) == "linux_x64"
                        )
                        listOf(
                            "linuxX64Main",
                            "linuxArm64Main",
                            "mingwX64Main",
                            "macosX64Main",
                            "macosArm64Main",
                        ).forEach { name ->
                            check(desktopNativeMain in sourceSets.getByName(name).dependsOn)
                        }
                        listOf(
                            "linkDebugExecutableLinuxX64",
                            "linkDebugExecutableLinuxArm64",
                            "linkDebugExecutableMingwX64",
                            "copyDebugMingwX64ExecutableRuntime",
                            "copyReleaseMingwX64ExecutableRuntime",
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
                        val runTask = tasks.getByName("runDebugExecutableLinuxX64")
                        val copyTask = tasks.getByName("copyDebugLinuxX64ExecutableResources")
                        check(copyTask in runTask.taskDependencies.getDependencies(runTask))

                        listOf("Debug", "Release").forEach { buildType ->
                            val windowsRunTask =
                                tasks.getByName("run${'$'}{buildType}ExecutableMingwX64")
                            val runtimeCopyTask =
                                tasks.getByName("copy${'$'}{buildType}MingwX64ExecutableRuntime")
                            val windowsLinkTask =
                                tasks.getByName("link${'$'}{buildType}ExecutableMingwX64")
                            check(
                                runtimeCopyTask in
                                    windowsRunTask.taskDependencies.getDependencies(windowsRunTask)
                            )
                            check(
                                windowsLinkTask in
                                    runtimeCopyTask.taskDependencies.getDependencies(runtimeCopyTask)
                            )
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
                    kotlin("multiplatform") version "2.3.20"
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
