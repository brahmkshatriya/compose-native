plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-alpha01"
    id("org.jetbrains.compose.linux.application")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "demo.generated.resources"
    generateResClass = always
}

val wpePrefix = providers.environmentVariable("KTNATIVE_WPE_PREFIX").orElse("/usr").get()
val appWebViewObject = layout.buildDirectory.file("native-support/app_webview.o")
val appMpvObject = layout.buildDirectory.file("native-support/app_mpv.o")

val compileAppWebView by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/app_webview.cpp",
        "src/nativeInterop/cinterop/include/app_webview.h",
    )
    outputs.file(appWebViewObject)
    doFirst { appWebViewObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I$wpePrefix/include/wpe-webkit-2.0",
        "-I$wpePrefix/include/wpe-webkit-2.0/wpe-platform",
        "-I$wpePrefix/include/wpe-1.0",
        "-I/usr/include/glib-2.0",
        "-I/usr/lib/glib-2.0/include",
        "-I/usr/include/libsoup-3.0",
        "-I/usr/include/sysprof-6",
        "-I/usr/include/libxml2",
        "-c", "src/nativeInterop/cinterop/app_webview.cpp",
        "-o", appWebViewObject.get().asFile.absolutePath,
    )
}

val compileAppMpv by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/app_mpv.cpp",
        "src/nativeInterop/cinterop/include/app_mpv.h",
    )
    outputs.file(appMpvObject)
    doFirst { appMpvObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O2", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-c", "src/nativeInterop/cinterop/app_mpv.cpp",
        "-o", appMpvObject.get().asFile.absolutePath,
    )
}

kotlin {
    linuxX64 {
        compilerOptions {
            // Let Kotlin/Native LLVM code generation use all available processors.
            freeCompilerArgs.add("-Xbackend-threads=0")
        }
        compilations.getByName("main") {
            compileTaskProvider.configure {
                dependsOn(compileAppWebView, compileAppMpv)
                inputs.files(appWebViewObject, appMpvObject)
            }
            cinterops {
                val appWebView by creating {
                    defFile(project.file("src/nativeInterop/cinterop/app-webview.def"))
                }
                val appMpv by creating {
                    defFile(project.file("src/nativeInterop/cinterop/app-mpv.def"))
                }
            }
        }
        binaries {
            executable {
                baseName = "compose-wayland"
                entryPoint = "dev.demo.main"
                linkTaskProvider.configure {
                    dependsOn(compileAppWebView, compileAppMpv)
                    inputs.files(appWebViewObject, appMpvObject)
                }
                // Kotlin/Native's Linux POSIX KLIB lists -lcrypt unconditionally even though this
                // application has no crypt() references. Do not retain that unused legacy DSO.
                linkerOpts(
                    "-Wl,--as-needed",
                    appWebViewObject.get().asFile.absolutePath,
                    appMpvObject.get().asFile.absolutePath,
                    "-L$wpePrefix/lib",
                    "-lWPEWebKit-2.0",
                    "-lEGL",
                    "-lGL",
                    "-lglib-2.0",
                    "-lgobject-2.0",
                    "-lgio-2.0",
                    "-lstdc++",
                    "-lmpv",
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":compose:components:components-resources"))
        }
        linuxMain.dependencies {
            implementation(project(":compose:material3:material3"))
            implementation(project(":compose:ui:ui-sdl2"))
            implementation(project(":compose:ui:ui-backhandler"))
        }
    }
}

linuxNativeApplication {
    applicationName.set("Compose Linux Demo")
    packageName.set("org.jetbrains.compose.demo")
    executableName.set("compose-wayland")
    packageVersion.set("1.0.0")
    description.set("Linux native Compose component, resource, and platform demonstrations")
    vendor.set("JetBrains")
    categories.set(listOf("Development", "Utility"))
}
