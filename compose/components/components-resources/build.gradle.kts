plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
    id("maven-publish")
}

group = "org.jetbrains.compose.components"
version = "9999.0.0-SNAPSHOT"

kotlin {
    linuxX64 {
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
        commonMain.dependencies {
            api(project(":compose:runtime:runtime"))
            api(project(":compose:foundation:foundation"))
            api(project(":compose:ui:ui"))
            implementation(libs.kotlinCoroutinesCore)
        }
        linuxMain.dependencies {
            implementation(project(":compose:ui:ui-sdl2"))
        }
    }
}
