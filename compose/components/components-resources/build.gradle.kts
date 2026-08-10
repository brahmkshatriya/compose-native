@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
    id("maven-publish")
}

group = "org.jetbrains.compose.components"
version = "9999.0.0-SNAPSHOT"

kotlin {
    applyHierarchyTemplate {
        common {
            group("desktopNative") {
                group("linux") { withLinux() }
                group("mingw") { withMingw() }
            }
        }
    }

    val linuxCompilerOptions: org.jetbrains.kotlin.gradle.dsl.KotlinNativeCompilerOptions.() -> Unit = {
        freeCompilerArgs.addAll(
            "-Xbackend-threads=0",
            "-opt-in=org.jetbrains.compose.resources.ExperimentalResourceApi",
            "-opt-in=org.jetbrains.compose.resources.InternalResourceApi",
            "-opt-in=androidx.compose.ui.InternalComposeUiApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
        )
    }
    linuxX64 { compilerOptions(linuxCompilerOptions) }
    linuxArm64 { compilerOptions(linuxCompilerOptions) }
    mingwX64 {
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

        val desktopNativeMain by getting {
            dependencies {
                implementation(project(":compose:ui:ui-sdl3"))
            }
        }
    }
}
