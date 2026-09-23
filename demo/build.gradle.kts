@file:OptIn(
    org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
    org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class,
)
@file:Suppress("DEPRECATION")

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.13.0-alpha01"
    id("org.jetbrains.compose.linux.application")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "demo.generated.resources"
    generateResClass = always
}

val wpePrefix = providers.environmentVariable("KTNATIVE_WPE_PREFIX").orElse("/usr").get()
val macosX64SdlDylib =
    providers.environmentVariable("COMPOSE_MACOS_X64_SDL_DYLIB").orNull?.let(::file)
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
    applyHierarchyTemplate {
        common {
            group("desktopNative") {
                group("linux") { withLinux() }
            }
            group("macos") { withMacos() }
        }
    }

    jvm("desktop")

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

    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureMacosDemo() {
        val targetName = name
        val outDirectory = System.getenv("OUT_DIR")?.let(::file) ?: rootProject.file("out")
        val macosSdlDirectory =
            outDirectory.resolve(
                "${rootProject.name}/compose/desktop/desktop-native/build/macos-sdl"
            )
        val sdlLinkerOpts =
            if (name == "macosX64" && macosX64SdlDylib != null) {
                listOf(
                    macosX64SdlDylib.absolutePath,
                    "-rpath", macosX64SdlDylib.parentFile.absolutePath,
                )
            } else {
                listOf(
                    "-F${macosSdlDirectory.absolutePath}",
                    "-framework", "SDL3",
                    "-rpath", macosSdlDirectory.absolutePath,
                )
            }
        compilerOptions { freeCompilerArgs.add("-Xbackend-threads=0") }
        val syncComposeResources =
            tasks.register<Sync>(
                "sync${targetName.replaceFirstChar { it.uppercase() }}ComposeResources"
            ) {
                from(
                    layout.buildDirectory.dir(
                        "generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
                    )
                )
                from(
                    layout.buildDirectory.dir(
                        "generated/compose/resourceGenerator/preparedResources/macosMain/composeResources"
                    )
                )
                from(
                    layout.buildDirectory.dir(
                        "generated/compose/resourceGenerator/preparedResources/${targetName}Main/composeResources"
                    )
                )
                into(
                    layout.buildDirectory.dir(
                        "bin/$targetName/debugExecutable/composeResources/demo.generated.resources"
                    )
                )
            }
        binaries {
            executable {
                baseName = "compose-macos-sdl"
                entryPoint = "dev.demo.main"
                if (targetName == "macosX64") {
                    disableNativeCache(
                        org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion.`2_3_20`,
                        "Work around Kotlin/Native 2.3.20 cache lowering failure for " +
                            "androidx.graphics:graphics-shapes on macOS x64",
                    )
                }
                linkTaskProvider.configure {
                    finalizedBy(syncComposeResources)
                }
                linkerOpts(
                    *sdlLinkerOpts.toTypedArray(),
                    "-framework", "AppKit",
                    "-framework", "Metal",
                    "-framework", "QuartzCore",
                )
            }
        }
    }

    macosX64 { configureMacosDemo() }
    macosArm64 { configureMacosDemo() }

    sourceSets {
        val commonMain by getting
        commonMain.dependencies {
            implementation(project(":compose:animation:animation"))
            implementation(project(":compose:foundation:foundation"))
            implementation(project(":compose:material3:material3"))
            implementation(project(":compose:ui:ui"))
            implementation(project(":navigationevent:navigationevent-compose"))
            implementation(project(":compose:components:components-resources"))
        }
        val desktopMain by getting {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":compose:desktop:desktop"))
            }
        }
        val desktopNativeMain by getting {
            dependencies {
                implementation(project(":compose:desktop:desktop-native"))
                implementation(project(":compose:ui:ui-backhandler"))
            }
        }
        val macosMain by getting {
            dependencies {
                implementation(project(":compose:desktop:desktop-native"))
                implementation(project(":compose:ui:ui-backhandler"))
            }
        }
    }
}

tasks.register("runJvmCatalogue", JavaExec::class.java) {
    dependsOn(":compose:desktop:desktop:jvmJar")
    group = "application"
    description = "Runs the shared Compose component catalogue on JVM desktop"
    mainClass.set("dev.demo.Main_desktopKt")
    val compilation = kotlin.jvm("desktop").compilations["main"]
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
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
