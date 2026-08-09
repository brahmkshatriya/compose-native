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

    sourceSets {
        linuxMain.dependencies {
            api(project(":compose:ui:ui"))
            implementation(project(":compose:foundation:foundation"))
            implementation(libs.skiko)
        }
        linuxTest.dependencies { implementation(kotlin("test")) }
    }
}
