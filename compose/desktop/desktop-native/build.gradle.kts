@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import androidx.build.SoftwareType
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("AndroidXPlugin")
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
    id("JetBrainsAndroidXPlugin")
    id("maven-publish")
}

val glInteropObject = layout.buildDirectory.file("native-support/gl_interop.o")
val clipperEngineObject = layout.buildDirectory.file("native-support/clipper.engine.o")
val nativeDesktopSupportObject = layout.buildDirectory.file("native-support/native_desktop_support.o")
val atspiSupportObject = layout.buildDirectory.file("native-support/atspi_support.o")
val traySupportObject = layout.buildDirectory.file("native-support/tray_support.o")
val dragSupportObject = layout.buildDirectory.file("native-support/drag_support.o")
val nativeSupportArchive = layout.buildDirectory.file("native-support/libcompose_sdl3.a")
val linuxArm64SupportDirectory = layout.buildDirectory.dir("native-support-linux-arm64")
val linuxArm64GlInteropObject = linuxArm64SupportDirectory.map { it.file("gl_interop.o") }
val linuxArm64ClipperEngineObject = linuxArm64SupportDirectory.map { it.file("clipper.engine.o") }
val linuxArm64NativeDesktopSupportObject =
    linuxArm64SupportDirectory.map { it.file("native_desktop_support.o") }
val linuxArm64AtspiSupportObject = linuxArm64SupportDirectory.map { it.file("atspi_support.o") }
val linuxArm64TraySupportObject = linuxArm64SupportDirectory.map { it.file("tray_support.o") }
val linuxArm64DragSupportObject = linuxArm64SupportDirectory.map { it.file("drag_support.o") }
val linuxArm64NativeSupportArchive =
    linuxArm64SupportDirectory.map { it.file("libcompose_sdl3.a") }

fun findKonanLinuxArm64Tool(tool: String): String? {
    val dependencies = File(System.getProperty("user.home"), ".konan/dependencies")
    return dependencies
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith("aarch64-unknown-linux-gnu-gcc-") }
        ?.sortedByDescending { it.name }
        ?.map { it.resolve("bin/aarch64-unknown-linux-gnu-$tool") }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

val hostIsLinuxArm64 =
    System.getProperty("os.arch").lowercase() == "aarch64" ||
        System.getProperty("os.arch").lowercase() == "arm64"
val linuxArm64Cxx =
    providers.gradleProperty("compose.linux.arm64.cxx").orElse(
        providers.provider {
            if (hostIsLinuxArm64) "c++"
            else findKonanLinuxArm64Tool("g++") ?: "aarch64-linux-gnu-g++"
        }
    )
val linuxArm64Ar =
    providers.gradleProperty("compose.linux.arm64.ar").orElse(
        providers.provider {
            if (hostIsLinuxArm64) "ar"
            else findKonanLinuxArm64Tool("ar") ?: "aarch64-linux-gnu-ar"
        }
    )
val linuxArm64SystemIncludes =
    if (hostIsLinuxArm64) emptyList() else listOf("-idirafter", "/usr/include")

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
        "src/nativeInterop/cinterop/windows_native_desktop_support.cpp",
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
        "src/nativeInterop/cinterop/include/native_gl.h",
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

val compileNativeDesktopSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/native_desktop_support.cpp",
        "src/nativeInterop/cinterop/include/native_desktop.h",
    )
    outputs.file(nativeDesktopSupportObject)
    doFirst { nativeDesktopSupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-pthread", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-I/usr/include/SDL3",
        "-D_REENTRANT",
        "-I/usr/include/dbus-1.0",
        "-I/usr/lib/dbus-1.0/include",
        "-c", "src/nativeInterop/cinterop/native_desktop_support.cpp",
        "-o", nativeDesktopSupportObject.get().asFile.absolutePath,
    )
}

val compileDragSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/drag_support.cpp",
        "src/nativeInterop/cinterop/include/native_drag.h",
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
        "src/nativeInterop/cinterop/include/native_tray.h",
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
    dependsOn(compileGlInterop, compileClipperEngine, compileNativeDesktopSupport, compileAtspiSupport, compileTraySupport, compileDragSupport)
    inputs.files(glInteropObject, clipperEngineObject, nativeDesktopSupportObject, atspiSupportObject, traySupportObject, dragSupportObject)
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
        nativeDesktopSupportObject.get().asFile.absolutePath,
        atspiSupportObject.get().asFile.absolutePath,
        traySupportObject.get().asFile.absolutePath,
        dragSupportObject.get().asFile.absolutePath,
    )
}

val compileGlInteropLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/gl_interop.cpp",
        "src/nativeInterop/cinterop/include/native_gl.h",
    )
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64GlInteropObject)
    doFirst {
        val output = linuxArm64GlInteropObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC", "-w",
            "-Isrc/nativeInterop/cinterop",
            *linuxArm64SystemIncludes.toTypedArray(),
            "-c", "src/nativeInterop/cinterop/gl_interop.cpp",
            "-o", output.absolutePath,
        )
    }
}

val compileClipperEngineLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/nativeInterop/cinterop/clipper2"))
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64ClipperEngineObject)
    doFirst {
        val output = linuxArm64ClipperEngineObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC",
            "-Isrc/nativeInterop/cinterop",
            "-c", "src/nativeInterop/cinterop/clipper2/clipper.engine.cpp",
            "-o", output.absolutePath,
        )
    }
}

val compileNativeDesktopSupportLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/native_desktop_support.cpp",
        "src/nativeInterop/cinterop/include/native_desktop.h",
    )
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64NativeDesktopSupportObject)
    doFirst {
        val output = linuxArm64NativeDesktopSupportObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC", "-pthread", "-w",
            "-Isrc/nativeInterop/cinterop/include",
            "-I/usr/include/SDL3",
            "-D_REENTRANT",
            "-I/usr/include/dbus-1.0",
            "-I/usr/lib/dbus-1.0/include",
            *linuxArm64SystemIncludes.toTypedArray(),
            "-c", "src/nativeInterop/cinterop/native_desktop_support.cpp",
            "-o", output.absolutePath,
        )
    }
}

val compileDragSupportLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/drag_support.cpp",
        "src/nativeInterop/cinterop/include/native_drag.h",
    )
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64DragSupportObject)
    doFirst {
        val output = linuxArm64DragSupportObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC", "-w",
            "-Isrc/nativeInterop/cinterop/include",
            "-I/usr/include/SDL3",
            "-D_REENTRANT",
            *linuxArm64SystemIncludes.toTypedArray(),
            "-c", "src/nativeInterop/cinterop/drag_support.cpp",
            "-o", output.absolutePath,
        )
    }
}

val compileTraySupportLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/tray_support.cpp",
        "src/nativeInterop/cinterop/include/native_tray.h",
    )
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64TraySupportObject)
    doFirst {
        val output = linuxArm64TraySupportObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC", "-w",
            "-Isrc/nativeInterop/cinterop/include",
            "-I/usr/include/dbus-1.0",
            "-I/usr/lib/dbus-1.0/include",
            *linuxArm64SystemIncludes.toTypedArray(),
            "-c", "src/nativeInterop/cinterop/tray_support.cpp",
            "-o", output.absolutePath,
        )
    }
}

val compileAtspiSupportLinuxArm64 by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/atspi_support.cpp",
        "src/nativeInterop/cinterop/include/linux_atspi.h",
    )
    inputs.property("compiler", linuxArm64Cxx)
    outputs.file(linuxArm64AtspiSupportObject)
    doFirst {
        val output = linuxArm64AtspiSupportObject.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            linuxArm64Cxx.get(), "-std=c++17", "-O3", "-fPIC", "-w",
            "-Isrc/nativeInterop/cinterop/include",
            "-I/usr/include/dbus-1.0",
            "-I/usr/lib/dbus-1.0/include",
            *linuxArm64SystemIncludes.toTypedArray(),
            "-c", "src/nativeInterop/cinterop/atspi_support.cpp",
            "-o", output.absolutePath,
        )
    }
}

val archiveLinuxArm64NativeSupport by tasks.registering(Exec::class) {
    dependsOn(
        compileGlInteropLinuxArm64,
        compileClipperEngineLinuxArm64,
        compileNativeDesktopSupportLinuxArm64,
        compileAtspiSupportLinuxArm64,
        compileTraySupportLinuxArm64,
        compileDragSupportLinuxArm64,
    )
    inputs.files(
        linuxArm64GlInteropObject,
        linuxArm64ClipperEngineObject,
        linuxArm64NativeDesktopSupportObject,
        linuxArm64AtspiSupportObject,
        linuxArm64TraySupportObject,
        linuxArm64DragSupportObject,
    )
    inputs.property("archiver", linuxArm64Ar)
    outputs.file(linuxArm64NativeSupportArchive)
    doFirst {
        val archive = linuxArm64NativeSupportArchive.get().asFile
        archive.parentFile.mkdirs()
        archive.delete()
        commandLine(
            linuxArm64Ar.get(), "rcs",
            archive.absolutePath,
            linuxArm64GlInteropObject.get().asFile.absolutePath,
            linuxArm64ClipperEngineObject.get().asFile.absolutePath,
            linuxArm64NativeDesktopSupportObject.get().asFile.absolutePath,
            linuxArm64AtspiSupportObject.get().asFile.absolutePath,
            linuxArm64TraySupportObject.get().asFile.absolutePath,
            linuxArm64DragSupportObject.get().asFile.absolutePath,
        )
    }
}

kotlin {
    applyHierarchyTemplate {
        common {
            group("desktopNative") {
                group("linux") { withLinux() }
                group("mingw") { withMingw() }
            }
        }
    }

    linuxX64 {
        binaries.all { linkerOpts("-L/usr/lib") }
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
                val sdl3 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sdl3.def"))
                }
                val nativeDesktop by creating {
                    defFile(project.file("src/nativeInterop/cinterop/native-desktop.def"))
                }
            }
        }
    }

    linuxArm64 {
        compilerOptions {
            freeCompilerArgs.add("-Xbackend-threads=0")
            freeCompilerArgs.addAll(
                "-include-binary",
                linuxArm64NativeSupportArchive.get().asFile.absolutePath,
            )
        }
        compilations.getByName("main") {
            compileTaskProvider.configure {
                dependsOn(archiveLinuxArm64NativeSupport)
                inputs.file(linuxArm64NativeSupportArchive)
            }
            cinterops {
                val sdl3 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sdl3.def"))
                }
                val nativeDesktop by creating {
                    defFile(project.file("src/nativeInterop/cinterop/native-desktop.def"))
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
                val sdl3 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sdl3.windows.def"))
                    compilerOpts(
                        "-I${windowsSdlX64Root.get().dir("include").asFile.absolutePath}"
                    )
                }
                val nativeDesktop by creating {
                    defFile(project.file("src/nativeInterop/cinterop/native-desktop.windows.def"))
                    compilerOpts(
                        "-Isrc/nativeInterop/cinterop/include",
                        "-I${windowsSdlX64Root.get().dir("include").asFile.absolutePath}",
                    )
                }
            }
        }
    }

    sourceSets {
        val desktopNativeMain by getting {
            dependencies {
                api(project(":compose:ui:ui"))
                implementation(project(":compose:foundation:foundation"))
                implementation(libs.skikoNative)
            }
        }
        linuxTest.dependencies { implementation(kotlin("test")) }
        mingwX64Test.dependencies { implementation(kotlin("test")) }
    }
}

tasks.matching {
    it.name == "cinteropSdl3MingwX64" || it.name == "cinteropNativeDesktopMingwX64"
}.configureEach {
    dependsOn(prepareWindowsSdl)
}

androidx {
    name = "Compose Native Desktop"
    type = SoftwareType.PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS
    inceptionYear = "2026"
    description = "Native desktop application host for Compose Multiplatform"
    legacyDisableKotlinStrictApiMode = true
}
