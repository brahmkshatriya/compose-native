package dev.brahmkshatriya.compose

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner

class ComposeNativeDependencyFunctionalTest {
    @Test
    fun substitutesForkComposeModulesInAWebApplicationConsumingTheSharedProject() {
        val projectDir = createTempDirectory("compose-native-web-consumer-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "compose-native-web-consumer-test"
                include(":shared", ":web")
                """
                    .trimIndent()
            )
        projectDir.resolve("shared").mkdirs()
        projectDir
            .resolve("shared/build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
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
                """
                    .trimIndent()
            )
        projectDir.resolve("web").mkdirs()
        projectDir
            .resolve("web/build.gradle.kts")
            .writeText(
                """
                plugins { kotlin("multiplatform") version "$functionalTestKotlinVersion" }
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
                """
                    .trimIndent()
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
            "org.jetbrains.compose.runtime:runtime:1.13.0-alpha01 -> androidx.compose.runtime:runtime:1.13.0-beta01",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0 -> androidx.lifecycle:lifecycle-runtime-compose:2.11.0",
        )
        assertContains(
            result.output,
            "org.jetbrains.androidx.navigation3:navigation3-runtime:1.1.1 -> androidx.navigation3:navigation3-runtime:1.2.0",
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
    fun substitutesFullForkRootsFromUpstreamIosDependencyInApplicationConsumer() {
        val projectDir = createTempDirectory("compose-native-ios-full-fork-consumer-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "compose-native-ios-full-fork-consumer-test"
                include(":shared", ":upstreamLib", ":ios")
                """
                    .trimIndent()
            )
        projectDir.resolve("shared").mkdirs()
        projectDir
            .resolve("shared/build.gradle.kts")
            .writeText(
                """
                plugins {
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("dev.brahmkshatriya.compose")
                }
                kotlin {
                    iosArm64()
                    sourceSets.commonMain.dependencies {
                        implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                        implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06")
                    }
                }
                """
                    .trimIndent()
            )
        projectDir.resolve("upstreamLib").mkdirs()
        projectDir
            .resolve("upstreamLib/build.gradle.kts")
            .writeText(
                """
                plugins { kotlin("multiplatform") version "$functionalTestKotlinVersion" }
                kotlin {
                    iosArm64()
                    sourceSets.commonMain.dependencies {
                        api("org.jetbrains.compose.material3:material3:1.13.0-alpha01")
                    }
                }
                """
                    .trimIndent()
            )
        projectDir.resolve("ios").mkdirs()
        projectDir
            .resolve("ios/build.gradle.kts")
            .writeText(
                """
                plugins { kotlin("multiplatform") version "$functionalTestKotlinVersion" }
                kotlin {
                    iosArm64()
                    sourceSets.iosArm64Main.dependencies {
                        implementation(project(":shared"))
                        implementation(project(":upstreamLib"))
                    }
                }

                tasks.register("printIosSupportRequests") {
                    doLast {
                        configurations.getByName("iosArm64CompileKlibraries")
                            .incoming.resolutionResult.allDependencies
                            .forEach { dependency ->
                                val requested = dependency.requested.displayName
                                if (
                                    requested.contains("ui-uikit") ||
                                        requested.contains("runtime-annotation")
                                ) {
                                    println("REQUEST=" + requested)
                                }
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
                    ":ios:dependencyInsight",
                    "--configuration",
                    "iosArm64CompileKlibraries",
                    "--dependency",
                    "material3",
                    "--no-configuration-cache",
                )
                .build()

        assertContains(
            result.output,
            "dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06",
        )
        assertContains(
            result.output,
            "requested org.jetbrains.compose.material3:material3:1.13.0-alpha01",
        )
        assertFalse(
            result.output.contains(
                "org.jetbrains.compose.material3:material3-iosarm64:1.13.0-alpha01"
            )
        )

        val supportResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(":ios:printIosSupportRequests", "--no-configuration-cache")
                .build()

        assertContains(
            supportResult.output,
            "REQUEST=org.jetbrains.compose.ui:ui-uikit:1.13.0-alpha01",
        )
        assertContains(
            supportResult.output,
            "REQUEST=androidx.compose.runtime:runtime-annotation:1.13.0-beta01",
        )
        assertFalse(
            supportResult.output.contains(
                "REQUEST=dev.brahmkshatriya.compose.ui:ui-uikit:1.13.0-alpha06"
            )
        )
        assertFalse(
            supportResult.output.contains(
                "REQUEST=org.jetbrains.compose.runtime:runtime-annotation:1.13.0-alpha01"
            )
        )
    }

    @Test
    fun repairsPublishedIosForkSupportMetadataBeforeResolution() {
        val projectDir = createTempDirectory("compose-native-ios-metadata-repair-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                        maven("https://packages.jetbrains.team/maven/p/cmp/dev")
                    }
                }
                rootProject.name = "compose-native-ios-metadata-repair-test"
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
                    iosArm64()
                    sourceSets.commonMain.dependencies {
                        implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                        implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06")
                    }
                }

                tasks.register("printIosRequests") {
                    doLast {
                        configurations.getByName("iosArm64CompileKlibraries")
                            .incoming.resolutionResult.allDependencies
                            .forEach { dependency ->
                                val requested = dependency.requested.displayName
                                if (
                                    requested.contains("ui-uikit") ||
                                        requested.contains("runtime-annotation")
                                ) {
                                    println("REQUEST=" + requested)
                                }
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
                .withArguments("printIosRequests", "--no-configuration-cache")
                .build()

        assertContains(result.output, "REQUEST=org.jetbrains.compose.ui:ui-uikit:1.13.0-alpha01")
        assertContains(
            result.output,
            "REQUEST=androidx.compose.runtime:runtime-annotation:1.13.0-beta01",
        )
        assertFalse(
            result.output.contains("REQUEST=dev.brahmkshatriya.compose.ui:ui-uikit:1.13.0-alpha06")
        )
        assertFalse(
            result.output.contains(
                "REQUEST=org.jetbrains.compose.runtime:runtime-annotation:1.13.0-alpha01"
            )
        )
    }

    @Test
    fun keepsFullForkRootsButRoutesJvmSupportModulesUpstream() {
        val projectDir = createTempDirectory("compose-native-jvm-full-fork-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "compose-native-jvm-full-fork-test"
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
                    sourceSets {
                        commonMain.dependencies {
                            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06")
                            implementation("dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha06")
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
                    "jvmCompileClasspath",
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
            "dev.brahmkshatriya.compose.ui:ui:1.13.0-alpha06 -> org.jetbrains.compose.ui:ui:1.13.0-beta01",
        )
        assertFalse(
            result.output.contains(
                "dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha06 -> org.jetbrains.compose.foundation:foundation"
            )
        )
        assertFalse(
            result.output.contains(
                "dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha06 -> org.jetbrains.compose.material3:material3"
            )
        )
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
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("org.jetbrains.kotlin.plugin.compose") version "$functionalTestKotlinVersion"
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
                    kotlin("multiplatform") version "$functionalTestKotlinVersion"
                    id("org.jetbrains.kotlin.plugin.compose") version "$functionalTestKotlinVersion"
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
}
