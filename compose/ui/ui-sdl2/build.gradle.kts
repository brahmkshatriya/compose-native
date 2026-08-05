plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("AndroidXComposePlugin")
}

val nativeSupportObject = layout.buildDirectory.file("native-support/native_support.o")
val clipperEngineObject = layout.buildDirectory.file("native-support/clipper.engine.o")
val desktopSupportObject = layout.buildDirectory.file("native-support/desktop_support.o")
val atspiSupportObject = layout.buildDirectory.file("native-support/atspi_support.o")
val traySupportObject = layout.buildDirectory.file("native-support/tray_support.o")
val dragSupportObject = layout.buildDirectory.file("native-support/drag_support.o")
val nativeSupportArchive = layout.buildDirectory.file("native-support/libcompose_sdl2.a")

val compileNativeSupport by tasks.registering(Exec::class) {
    inputs.files(
        "src/nativeInterop/cinterop/native_support.cpp",
        "src/nativeInterop/cinterop/include/cairo_compose.h",
    )
    outputs.file(nativeSupportObject)
    doFirst { nativeSupportObject.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-std=c++17", "-O3", "-fPIC", "-fpermissive", "-w",
        "-Isrc/nativeInterop/cinterop/include",
        "-Isrc/nativeInterop/cinterop",
        "-I/usr/include/SDL2",
        "-D_REENTRANT",
        "-I/usr/include/cairo",
        "-I/usr/include/pango-1.0",
        "-I/usr/include/harfbuzz",
        "-I/usr/include/glib-2.0",
        "-I/usr/lib/glib-2.0/include",
        "-I/usr/include/freetype2",
        "-I/usr/include/pixman-1",
        "-c", "src/nativeInterop/cinterop/native_support.cpp",
        "-o", nativeSupportObject.get().asFile.absolutePath,
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
        "c++", "-std=c++17", "-O3", "-fPIC", "-w",
        "-Isrc/nativeInterop/cinterop/include",
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
        "-I/usr/include/SDL2",
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
    dependsOn(compileNativeSupport, compileClipperEngine, compileDesktopSupport, compileAtspiSupport, compileTraySupport, compileDragSupport)
    inputs.files(nativeSupportObject, clipperEngineObject, desktopSupportObject, atspiSupportObject, traySupportObject, dragSupportObject)
    outputs.file(nativeSupportArchive)
    doFirst { nativeSupportArchive.get().asFile.parentFile.mkdirs() }
    commandLine(
        "ar", "rcs",
        nativeSupportArchive.get().asFile.absolutePath,
        nativeSupportObject.get().asFile.absolutePath,
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
                    defFile(project.file("src/nativeInterop/cinterop/sdl2.def"))
                }
                val cairo by creating {
                    defFile(project.file("src/nativeInterop/cinterop/cairo.def"))
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
        }
        linuxTest.dependencies { implementation(kotlin("test")) }
    }
}
