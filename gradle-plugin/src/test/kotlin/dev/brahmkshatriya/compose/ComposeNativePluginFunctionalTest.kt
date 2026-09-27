package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class ComposeNativePluginFunctionalTest {
    @Test
    fun substitutesForkComposeModulesInAWebApplicationConsumingTheSharedProject() {
        val projectDir = createTempDirectory("compose-native-web-consumer-test").toFile()
        projectDir.deleteOnExit()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "compose-native-web-consumer-test"
            include(":shared", ":web")
            """.trimIndent()
        )
        projectDir.resolve("shared").mkdirs()
        projectDir.resolve("shared/build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.4.20"
                id("dev.brahmkshatriya.compose")
            }
            kotlin {
                @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                wasmJs()
                sourceSets.commonMain.dependencies {
                    implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                    implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06")
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("web").mkdirs()
        projectDir.resolve("web/build.gradle.kts").writeText(
            """
            plugins { kotlin("multiplatform") version "2.4.20" }
            kotlin {
                @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                wasmJs()
                sourceSets.wasmJsMain.dependencies {
                    implementation(project(":shared"))
                    implementation("org.jetbrains.compose.material:material-ripple:1.13.0-alpha01")
                    implementation("org.jetbrains.compose.material3:material3:1.13.0-alpha01")
                    implementation("org.jetbrains.compose.runtime:runtime:1.13.0-alpha01")
                    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
                    implementation("org.jetbrains.androidx.navigation3:navigation3-runtime:1.1.1")
                    implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
                    implementation("org.jetbrains.androidx.savedstate:savedstate-compose:1.4.0")
                }
            }
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(
                    ":web:dependencies",
                    "--configuration",
                    "wasmJsCompileClasspath",
                    "--no-configuration-cache",
                )
                .build()

        assertContains(
            result.output,
            "org.jetbrains.compose.foundation:foundation:1.13.0-alpha01 -> dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06",
        )
        assertContains(
            result.output,
            "org.jetbrains.compose.material3:material3:1.13.0-alpha01 -> dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06",
        )
        assertContains(
            result.output,
            "org.jetbrains.compose.runtime:runtime:1.13.0-alpha01 -> androidx.compose.runtime:runtime:1.13.0-alpha03",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0 -> androidx.lifecycle:lifecycle-runtime-compose:2.11.0",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.navigation3:navigation3-runtime:1.1.1 -> androidx.navigation3:navigation3-runtime:1.2.0-rc01",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0 -> androidx.navigationevent:navigationevent-compose:1.1.1",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.savedstate:savedstate-compose:1.4.0 -> androidx.savedstate:savedstate-compose:1.5.0-alpha01",
        )
        assertFalse(result.output.contains("org.jetbrains.compose.foundation:foundation-wasm-js:"))
        assertFalse(result.output.contains("org.jetbrains.compose.material3:material3-wasm-js:"))
    }

    @Test
    fun exposesTransitiveCommonMetadataToConcreteNativeCompiles() {
        val projectDir = createTempDirectory("compose-native-native-common-metadata-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories {
                        google()
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
                rootProject.name = "compose-native-native-common-metadata-test"
                """
                    .trimIndent()
            )
        projectDir.resolve("src/commonMain/kotlin").mkdirs()
        projectDir
            .resolve("src/commonMain/kotlin/Example.kt")
            .writeText(
                """
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.saveable.rememberSaveable
                import androidx.navigation3.runtime.NavKey
                import androidx.navigationevent.NavigationEventInfo
                import androidx.navigationevent.compose.rememberNavigationEventState
                import androidx.savedstate.serialization.SavedStateConfiguration

                private class ExampleInfo : NavigationEventInfo()

                fun consumeNavKey(key: NavKey): NavKey = key
                fun savedStateConfiguration(): SavedStateConfiguration = SavedStateConfiguration.DEFAULT

                @Composable
                fun Example() {
                    rememberSaveable { 0 }
                    rememberNavigationEventState(ExampleInfo())
                }
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "2.4.20"
                    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    linuxX64()

                    sourceSets {
                        commonMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.androidx.navigation3:navigation3-ui:1.13.0-alpha06")
                            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0")
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
                .withArguments("compileKotlinLinuxX64", "--no-configuration-cache", "--stacktrace")
                .build()

        assertContains(result.output, "BUILD SUCCESSFUL")
    }

    @Test
    fun resolvesRealAndroidxRuntimeBehindLinuxNativeRedirectShim() {
        val projectDir = createTempDirectory("compose-native-runtime-redirect-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories {
                        google()
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
                rootProject.name = "compose-native-runtime-redirect-test"
                """
                    .trimIndent()
            )
        projectDir.resolve("src/linuxX64Main/kotlin").mkdirs()
        projectDir
            .resolve("src/linuxX64Main/kotlin/Example.kt")
            .writeText(
                """
                import androidx.compose.runtime.Composable

                @Composable
                fun Example() = Unit
                """
                    .trimIndent()
            )
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "2.4.20"
                    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
                    id("dev.brahmkshatriya.compose")
                }

                kotlin {
                    linuxX64()

                    sourceSets {
                        desktopNativeMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha03")
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
                .withArguments("compileKotlinLinuxX64", "--no-configuration-cache", "--stacktrace")
                .build()

        assertContains(result.output, "BUILD SUCCESSFUL")
    }

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
                    kotlin("multiplatform") version "2.4.20"
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
            "org.jetbrains.compose.ui:ui:1.13.0-alpha01",
            message = "The IDE model must contain official Compose UI metadata",
        )
        assertContains(
            commonMainModel,
            "org.jetbrains.compose.ui:ui-unit:1.13.0-alpha01",
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
    fun includesAndroidxCommonMetadataInTheCommonIdeModel() {
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
                    kotlin("multiplatform") version "2.4.20"
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
        assertContains(
            result.output,
            "dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha06",
        )
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
                    kotlin("multiplatform") version "2.4.20"
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
            isOfficialCommonIdeDependency(
                group = "androidx.compose.runtime",
                module = "runtime",
            )
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
                    iosArm64()
                    iosSimulatorArm64()
                    iosX64()
                    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                    wasmJs()

                    sourceSets {
                        desktopNativeMain.dependencies {}
                    }
                }

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
                            "desktopNativeMain",
                            "iosArm64Main",
                            "iosSimulatorArm64Main",
                            "iosX64Main",
                            "wasmJsMain",
                        ).forEach { name ->
                            check(skiaSources in sourceSets.getByName(name).kotlin.srcDirs)
                        }
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
                        listOf(
                            "linuxX64Main",
                            "linuxArm64Main",
                            "mingwX64Main",
                        ).forEach { name ->
                            check(linuxWindowsMain in sourceSets.getByName(name).dependsOn)
                        }
                        listOf("macosX64Main", "macosArm64Main").forEach { name ->
                            val sourceSet = sourceSets.getByName(name)
                            check(desktopNativeMain in sourceSet.dependsOn)
                            check(linuxWindowsMain !in sourceSet.dependsOn)
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

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "-Didea.sync.active=true",
                "verifyDesktopNativeExecutables",
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
