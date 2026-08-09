import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
    id("maven-publish")
}

group = "org.jetbrains.compose.ui"
version = "9999.0.0-SNAPSHOT"

val glInteropObject = layout.buildDirectory.file("native-support/gl_interop.o")
val clipperEngineObject = layout.buildDirectory.file("native-support/clipper.engine.o")
val desktopSupportObject = layout.buildDirectory.file("native-support/desktop_support.o")
val atspiSupportObject = layout.buildDirectory.file("native-support/atspi_support.o")
val traySupportObject = layout.buildDirectory.file("native-support/tray_support.o")
val dragSupportObject = layout.buildDirectory.file("native-support/drag_support.o")
val nativeSupportArchive = layout.buildDirectory.file("native-support/libcompose_sdl3.a")

val windowsSdlVersion = "3.4.10"
val windowsSdlSha256 = "39dd2ac370bf33d6332a21ed768d8d49c37cc6f3211d788ead765102722639a8"
val windowsSdlArchive =
    layout.buildDirectory.file("windows-sdl/SDL3-devel-$windowsSdlVersion-mingw.tar.gz")
val windowsSdlRoot = layout.buildDirectory.dir("windows-sdl/SDL3-$windowsSdlVersion")
val windowsSdlX64Root = windowsSdlRoot.map { it.dir("x86_64-w64-mingw32") }
val windowsSdlDll = windowsSdlX64Root.map { it.file("bin/SDL3.dll") }

val prepareWindowsSdl by tasks.registering {
    inputs.property("version", windowsSdlVersion)
    inputs.property("sha256", windowsSdlSha256)
    outputs.file(windowsSdlDll)
    doLast {
        val archive = windowsSdlArchive.get().asFile
        archive.parentFile.mkdirs()
        if (!archive.exists()) {
            val temporary = archive.resolveSibling("${archive.name}.download")
            temporary.delete()
            URI.create(
                    "https://github.com/libsdl-org/SDL/releases/download/" +
                        "release-$windowsSdlVersion/SDL3-devel-$windowsSdlVersion-mingw.tar.gz"
                )
                .toURL()
                .openStream()
                .use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.renameTo(archive)) { "Could not move the downloaded SDL archive" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual =
            archive.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        check(actual == windowsSdlSha256) {
            "SDL $windowsSdlVersion checksum mismatch: expected $windowsSdlSha256, got $actual"
        }
        sync {
            from(tarTree(resources.gzip(archive)))
            into(windowsSdlRoot.get().asFile.parentFile)
        }
    }
}

val windowsNativeSupportDirectory = layout.buildDirectory.dir("native-support-windows")
val windowsNativeSupportArchive =
    windowsNativeSupportDirectory.map { it.file("libcompose_sdl3_windows.a") }
val windowsNativeSources =
    listOf(
        "src/nativeInterop/cinterop/gl_interop.cpp",
        "src/nativeInterop/cinterop/clipper2/clipper.engine.cpp",
        "src/nativeInterop/cinterop/windows_desktop_support.cpp",
        "src/nativeInterop/cinterop/windows_drag_support.cpp",
        "src/nativeInterop/cinterop/windows_tray_support.cpp",
    )
val windowsNativeObjects =
    windowsNativeSources.map { source ->
        windowsNativeSupportDirectory.map {
            it.file("${project.file(source).nameWithoutExtension}.o")
        }
    }
val windowsMingwSysroot =
    providers.gradleProperty("compose.windows.mingwSysroot").orElse(
        providers.environmentVariable("KONAN_DATA_DIR").map {
            "$it/dependencies/msys2-mingw-w64-x86_64-2"
        }
    ).orElse(
        "${System.getProperty("user.home")}/.konan/dependencies/msys2-mingw-w64-x86_64-2"
    )
val windowsClang = providers.gradleProperty("compose.windows.clang").orElse("clang++")
val windowsLlvmAr = providers.gradleProperty("compose.windows.llvmAr").orElse("llvm-ar")

val compileWindowsNativeSupport =
    windowsNativeSources.mapIndexed { index, source ->
        val sourceName = project.file(source).nameWithoutExtension
        tasks.register<Exec>(
            "compile${sourceName.replaceFirstChar(Char::uppercaseChar)}WindowsSupport"
        ) {
            dependsOn(prepareWindowsSdl)
            inputs.file(source)
            inputs.dir("src/nativeInterop/cinterop/include")
            inputs.dir(windowsSdlX64Root.map { it.dir("include") })
            outputs.file(windowsNativeObjects[index])
            doFirst {
                val output = windowsNativeObjects[index].get().asFile
                output.parentFile.mkdirs()
                commandLine(
                    windowsClang.get(),
                    "--target=x86_64-pc-windows-gnu",
                    "--sysroot=${windowsMingwSysroot.get()}",
                    "-std=c++17",
                    "-O3",
                    "-DUNICODE",
                    "-D_UNICODE",
                    "-D_WIN32_WINNT=0x0A00",
                    "-Isrc/nativeInterop/cinterop",
                    "-I${windowsSdlX64Root.get().dir("include").asFile.absolutePath}",
                    "-c",
                    source,
                    "-o",
                    output.absolutePath,
                )
            }
        }
    }

val archiveWindowsNativeSupport by tasks.registering(Exec::class) {
    dependsOn(compileWindowsNativeSupport)
    inputs.files(windowsNativeObjects)
    outputs.file(windowsNativeSupportArchive)
    doFirst {
        val archive = windowsNativeSupportArchive.get().asFile
        archive.parentFile.mkdirs()
        archive.delete()
        commandLine(
            windowsLlvmAr.get(),
            "rcs",
            archive.absolutePath,
            *windowsNativeObjects.map { it.get().asFile.absolutePath }.toTypedArray(),
        )
    }
}

val compileGlInterop by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/gl_interop.cpp",
        "src/nativeInterop/cinterop/include/linux_gl.h",
    )
    outputs.file(glInteropObject)
    doFirst { glInteropObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop",
        "-c", "src/nativeInterop/cinterop/gl_interop.cpp",
        "-o", glInteropObject.get().asFile.absolutePath,
    )
}

val compileClipperEngine by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/nativeInterop/cinterop/clipper2"))
    outputs.file(clipperEngineObject)
    doFirst { clipperEngineObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC",
        "-Isrc/nativeInterop/cinterop",
        "-c", "src/nativeInterop/cinterop/clipper2/clipper.engine.cpp",
        "-o", clipperEngineObject.get().asFile.absolutePath,
    )
}

val compileDesktopSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/desktop_support.cpp",
        "src/nativeInterop/cinterop/include/linux_desktop.h",
    )
    outputs.file(desktopSupportObject)
    doFirst { desktopSupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-pthread", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I/usr/include/SDL3",
        "-D_REENTRANT",
        "-I/usr/include/dbus-1.0",
        "-I/usr/lib/dbus-1.0/include",
        "-c", "src/nativeInterop/cinterop/desktop_support.cpp",
        "-o", desktopSupportObject.get().asFile.absolutePath,
    )
}

val compileDragSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/drag_support.cpp",
        "src/nativeInterop/cinterop/include/linux_drag.h",
    )
    outputs.file(dragSupportObject)
    doFirst { dragSupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I/usr/include/SDL3",
        "-D_REENTRANT",
        "-c", "src/nativeInterop/cinterop/drag_support.cpp",
        "-o", dragSupportObject.get().asFile.absolutePath,
    )
}

val compileTraySupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/tray_support.cpp",
        "src/nativeInterop/cinterop/include/linux_tray.h",
    )
    outputs.file(traySupportObject)
    doFirst { traySupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I/usr/include/dbus-1.0",
        "-I/usr/lib/dbus-1.0/include",
        "-c", "src/nativeInterop/cinterop/tray_support.cpp",
        "-o", traySupportObject.get().asFile.absolutePath,
    )
}

val compileAtspiSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/atspi_support.cpp",
        "src/nativeInterop/cinterop/include/linux_atspi.h",
    )
    outputs.file(atspiSupportObject)
    doFirst { atspiSupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I/usr/include/dbus-1.0",
        "-I/usr/lib/dbus-1.0/include",
        "-c", "src/nativeInterop/cinterop/atspi_support.cpp",
        "-o", atspiSupportObject.get().asFile.absolutePath,
    )
}

val archiveNativeSupport by tasks.registering(Exec::class) {
    dependsOn(compileGlInterop, compileClipperEngine, compileDesktopSupport, compileAtspiSupport, compileTraySupport, compileDragSupport)
    inputs.files(glInteropObject, clipperEngineObject, desktopSupportObject, atspiSupportObject, traySupportObject, dragSupportObject)
    outputs.file(nativeSupportArchive)
    doFirst {
        val archive = nativeSupportArchive.get().asFile
        archive.parentFile.mkdirs()
        archive.delete()
    }
    commandLine(
        "ar", "rcs",
        nativeSupportArchive.get().asFile.absolutePath,
        glInteropObject.get().asFile.absolutePath,
        clipperEngineObject.get().asFile.absolutePath,
        desktopSupportObject.get().asFile.absolutePath,
        atspiSupportObject.get().asFile.absolutePath,
        traySupportObject.get().asFile.absolutePath,
        dragSupportObject.get().asFile.absolutePath,
    )
}

kotlin {
    linuxX64 {
        compilerOptions {
            freeCompilerArgs.add("-Xbackend-threads=0")
            freeCompilerArgs.addAll(
                "-include-binary",
                nativeSupportArchive.get().asFile.absolutePath,
            )
        }
        compilations.getByName("main") {
            compileTaskProvider.configure {
                dependsOn(archiveNativeSupport)
                inputs.file(nativeSupportArchive)
            }
            cinterops {
                val sdl2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sdl3.def"))
                }
                val desktop by creating {
                    defFile(project.file("src/nativeInterop/cinterop/desktop.def"))
                }
            }
        }
    }

    mingwX64 {
        compilerOptions {
            freeCompilerArgs.add("-Xbackend-threads=0")
            freeCompilerArgs.addAll(
                "-include-binary",
                windowsNativeSupportArchive.get().asFile.absolutePath,
            )
        }
        compilations.getByName("main") {
            compileTaskProvider.configure {
                dependsOn(archiveWindowsNativeSupport)
                inputs.file(windowsNativeSupportArchive)
            }
            cinterops {
                val sdl2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sdl3.windows.def"))
                    compilerOpts(
                        "-I${windowsSdlX64Root.get().dir("include").asFile.absolutePath}"
                    )
                }
                val desktop by creating {
                    defFile(project.file("src/nativeInterop/cinterop/desktop.windows.def"))
                    compilerOpts(
                        "-Isrc/nativeInterop/cinterop/include",
                        "-I${windowsSdlX64Root.get().dir("include").asFile.absolutePath}",
                    )
                }
            }
        }
    }

    sourceSets {
        linuxMain.dependencies {
            api(project(":compose:ui:ui"))
            implementation(project(":compose:foundation:foundation"))
            implementation(libs.skiko)
        }
        linuxTest.dependencies { implementation(kotlin("test")) }
        mingwX64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            kotlin.exclude("**/LinuxAtSpiAccessibility.kt")
            kotlin.exclude("**/NativeDesktopIntegration.linux.kt")
            kotlin.exclude("**/SdlSkiaLayerComponent.linux.kt")
            dependencies {
                api(project(":compose:ui:ui"))
                implementation(project(":compose:foundation:foundation"))
                implementation(libs.skiko)
            }
        }
        mingwX64Test.dependencies { implementation(kotlin("test")) }
    }
}

tasks.matching {
    it.name == "cinteropSdl2MingwX64" || it.name == "cinteropDesktopMingwX64"
}.configureEach {
    dependsOn(prepareWindowsSdl)
}
