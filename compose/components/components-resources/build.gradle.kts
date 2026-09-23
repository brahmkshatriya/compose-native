@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
@file:Suppress("DEPRECATION")

import androidx.build.SoftwareType
import org.jetbrains.androidx.build.ComposePlatforms

val requestedComposePlatforms =
    providers.gradleProperty("compose.platforms").orNull?.let(ComposePlatforms::parse)
fun composePlatformEnabled(platform: ComposePlatforms): Boolean =
    requestedComposePlatforms == null || platform in requestedComposePlatforms

plugins {
    id("AndroidXPlugin")
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
    id("JetBrainsAndroidXPlugin")
    id("maven-publish")
}

kotlin {
    applyHierarchyTemplate {
        common {
            group("desktopNative") {
                group("linux") { withLinux() }
                group("mingw") { withMingw() }
                group("macos") { withMacos() }
            }
        }
    }

    if (composePlatformEnabled(ComposePlatforms.Desktop)) jvm {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=org.jetbrains.compose.resources.ExperimentalResourceApi",
                "-opt-in=org.jetbrains.compose.resources.InternalResourceApi",
                "-opt-in=androidx.compose.ui.InternalComposeUiApi",
                "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            )
        }
    }

    val desktopNativeCompilerOptions: org.jetbrains.kotlin.gradle.dsl.KotlinNativeCompilerOptions.() -> Unit = {
        freeCompilerArgs.addAll(
            "-Xbackend-threads=0",
            "-opt-in=org.jetbrains.compose.resources.ExperimentalResourceApi",
            "-opt-in=org.jetbrains.compose.resources.InternalResourceApi",
            "-opt-in=androidx.compose.ui.InternalComposeUiApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
        )
    }
    if (composePlatformEnabled(ComposePlatforms.LinuxX64)) {
        linuxX64 { compilerOptions(desktopNativeCompilerOptions) }
    }
    if (composePlatformEnabled(ComposePlatforms.LinuxArm64)) {
        linuxArm64 { compilerOptions(desktopNativeCompilerOptions) }
    }
    if (composePlatformEnabled(ComposePlatforms.MacosX64)) {
        macosX64 { compilerOptions(desktopNativeCompilerOptions) }
    }
    if (composePlatformEnabled(ComposePlatforms.MacosArm64)) {
        macosArm64 { compilerOptions(desktopNativeCompilerOptions) }
    }
    if (composePlatformEnabled(ComposePlatforms.MingwX64)) mingwX64 {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xbackend-threads=0",
                "-opt-in=org.jetbrains.compose.resources.ExperimentalResourceApi",
                "-opt-in=org.jetbrains.compose.resources.InternalResourceApi",
                "-opt-in=androidx.compose.ui.InternalComposeUiApi",
                "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            )
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":compose:runtime:runtime"))
                api(project(":compose:foundation:foundation"))
                api(project(":compose:ui:ui"))
                implementation(libs.kotlinCoroutinesCore)
            }
        }

        findByName("jvmMain")?.apply {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.skiko)
            }
        }

        findByName("desktopNativeMain")?.apply {
            dependencies {
                implementation(project(":compose:desktop:desktop-native"))
            }
        }
    }
}

androidx {
    name = "Compose Components Resources"
    type = SoftwareType.PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS
    inceptionYear = "2026"
    description = "Resource APIs and runtime support for Compose Multiplatform"
    legacyDisableKotlinStrictApiMode = true
}
