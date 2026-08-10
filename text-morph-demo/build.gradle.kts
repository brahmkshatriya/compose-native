plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-alpha01"
}

kotlin {
    val linuxTarget =
        if (System.getProperty("os.arch").equals("aarch64", ignoreCase = true) ||
            System.getProperty("os.arch").equals("arm64", ignoreCase = true)
        ) {
            linuxArm64()
        } else {
            linuxX64()
        }
    linuxTarget.apply {
        compilerOptions {
            freeCompilerArgs.add("-Xbackend-threads=0")
        }
        binaries {
            executable {
                baseName = "text-morph-demo"
                entryPoint = "dev.textmorph.main"
                linkerOpts("-Wl,--as-needed")
            }
        }
    }

    sourceSets {
        linuxMain.dependencies {
            implementation(project(":compose:material3:material3"))
            implementation(project(":compose:ui:ui-sdl3"))
        }
    }
}
