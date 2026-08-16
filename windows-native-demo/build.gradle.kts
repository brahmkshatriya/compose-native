import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-alpha01"
}

compose.resources {
    publicResClass = true
    packageOfResClass = "demo.generated.resources"
    generateResClass = always
    customDirectory("commonMain", providers.provider {
        rootProject.layout.projectDirectory.dir("demo/src/commonMain/composeResources")
    })
}

val windowsSdlDirectory =
    (rootProject.extra["outDir"] as File)
        .resolve(rootProject.name)
        .resolve("compose/desktop/desktop-native/build/windows-sdl/SDL3-3.4.10/x86_64-w64-mingw32")
val windowsMingwSysroot =
    providers.gradleProperty("compose.windows.mingwSysroot").orElse(
        providers.environmentVariable("KONAN_DATA_DIR").map {
            "$it/dependencies/msys2-mingw-w64-x86_64-2"
        }
    ).orElse("${System.getProperty("user.home")}/.konan/dependencies/msys2-mingw-w64-x86_64-2")
val webView2Version = "1.0.4129.50"
val webView2Archive = layout.buildDirectory.file("webview2/Microsoft.Web.WebView2.$webView2Version.nupkg")
val webView2Sdk = layout.buildDirectory.dir("webview2/sdk")
val webView2Library = layout.buildDirectory.file("webview2/lib/app-webview-windows.a")
val mpvBuild = "20260610-git-304426c"
val mpvArchive = layout.buildDirectory.file("mpv/mpv-dev-x86_64-$mpvBuild.7z")
val mpvSdk = layout.buildDirectory.dir("mpv/sdk")
val mpvLibrary = layout.buildDirectory.file("mpv/lib/app-mpv-windows.a")
val windowsIcuData by
    configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

dependencies {
    windowsIcuData(
        "dev.brahmkshatriya.skiko:skiko-mingwx64:${libs.versions.skikoNative.get()}:icudtl@dat"
    )
}

val windowsTarget =
    kotlin.mingwX64 {
        compilations.getByName("main") {
            cinterops.create("appWebView") {
                defFile(rootProject.file("demo/src/nativeInterop/cinterop/app-webview.def"))
                includeDirs(rootProject.file("demo/src/nativeInterop/cinterop/include"))
                compilerOpts("-DKTNATIVE_APP_WEBVIEW_NO_DEMO_GL")
            }
            cinterops.create("appMpv") {
                defFile(rootProject.file("demo/src/nativeInterop/cinterop/app-mpv.def"))
                includeDirs(rootProject.file("demo/src/nativeInterop/cinterop/include"))
            }
        }
        binaries {
            executable {
                baseName = "compose-windows-demo"
                entryPoint = "androidx.compose.demo.windows.main"
                linkerOpts("-L${windowsSdlDirectory.resolve("lib").absolutePath}")
                linkerOpts("-lole32", "-lwindowscodecs", "-luuid")
                linkerOpts(mpvSdk.get().file("libmpv.dll.a").asFile.absolutePath)
            }
        }
    }

kotlin.sourceSets {
    val mingwX64Main by getting {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("demo/src/desktopNativeMain/kotlin"))
        dependencies {
            implementation(project(":compose:animation:animation"))
            implementation(project(":compose:components:components-resources"))
            implementation(project(":compose:foundation:foundation"))
            implementation(project(":compose:material3:material3"))
            implementation(project(":compose:ui:ui-backhandler"))
            implementation(project(":compose:desktop:desktop-native"))
        }
    }
}

val releaseExecutable = windowsTarget.binaries.getExecutable(NativeBuildType.RELEASE)
val windowsSdlDll = windowsSdlDirectory.resolve("bin/SDL3.dll")
val windowsSdlLicense = windowsSdlDirectory.resolve("share/licenses/SDL3/LICENSE.txt")
val preparedComposeResources =
    layout.buildDirectory.dir(
        "generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
    )
val windowsCxxRuntimes =
    windowsMingwSysroot.map { root ->
        listOf("libstdc++-6.dll", "libgcc_s_seh-1.dll", "libwinpthread-1.dll").map {
            file("$root/bin/$it")
        }
    }

val downloadWebView2 by tasks.registering {
    outputs.file(webView2Archive)
    doLast {
        val destination = webView2Archive.get().asFile
        if (!destination.isFile) {
            destination.parentFile.mkdirs()
            URI(
                "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/" +
                    "$webView2Version/microsoft.web.webview2.$webView2Version.nupkg"
            ).toURL().openStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
    }
}

val prepareWindowsWebView2 by tasks.registering(Sync::class) {
    dependsOn(downloadWebView2)
    from({ zipTree(webView2Archive.get().asFile) })
    into(webView2Sdk)
}

val compileWindowsWebView2 by tasks.registering(Exec::class) {
    dependsOn(prepareWindowsWebView2)
    val source = file("src/nativeInterop/cinterop/app_webview_windows.cpp")
    val output = layout.buildDirectory.file("webview2/obj/app-webview-windows.o")
    inputs.file(source)
    inputs.file(webView2Sdk.map { it.file("build/native/include/WebView2.h") })
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        commandLine(
            "clang++",
            "--target=x86_64-pc-windows-gnu",
            "--sysroot=${windowsMingwSysroot.get()}",
            "-std=c++17",
            "-O2",
            "-fms-extensions",
            "-DUNICODE",
            "-D_UNICODE",
            "-D_WIN32_WINNT=0x0A00",
            "-I${rootProject.file("demo/src/nativeInterop/cinterop/include")}",
            "-I${file("src/nativeInterop/cinterop")}",
            "-I${webView2Sdk.get().file("build/native/include").asFile}",
            "-I${webView2Sdk.get().file("build/native/include-winrt").asFile}",
            "-c",
            source,
            "-o",
            output.get().asFile,
        )
    }
}

val archiveWindowsWebView2 by tasks.registering(Exec::class) {
    dependsOn(compileWindowsWebView2)
    val objectFile = layout.buildDirectory.file("webview2/obj/app-webview-windows.o")
    inputs.file(objectFile)
    outputs.file(webView2Library)
    doFirst {
        webView2Library.get().asFile.parentFile.mkdirs()
        commandLine("llvm-ar", "rcs", webView2Library.get().asFile, objectFile.get().asFile)
    }
}

val downloadWindowsMpv by tasks.registering {
    outputs.file(mpvArchive)
    doLast {
        val destination = mpvArchive.get().asFile
        if (!destination.isFile) {
            destination.parentFile.mkdirs()
            URI(
                "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260610/" +
                    "mpv-dev-x86_64-$mpvBuild.7z"
            ).toURL().openStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "8cbb25ea784f01afbb3f904217cab1317430a8bcfd5680fd827a866367f71cc9") {
            "Unexpected MPV archive checksum: $actual"
        }
    }
}

val prepareWindowsMpv by tasks.registering(Exec::class) {
    dependsOn(downloadWindowsMpv)
    inputs.file(mpvArchive)
    outputs.files(
        mpvSdk.map { it.file("include/mpv/client.h") },
        mpvSdk.map { it.file("libmpv.dll.a") },
        mpvSdk.map { it.file("libmpv-2.dll") },
    )
    doFirst {
        mpvSdk.get().asFile.mkdirs()
        commandLine(
            "7z",
            "x",
            "-y",
            "-aoa",
            "-o${mpvSdk.get().asFile.absolutePath}",
            mpvArchive.get().asFile.absolutePath,
        )
    }
}

val compileWindowsMpv by tasks.registering(Exec::class) {
    dependsOn(prepareWindowsMpv)
    dependsOn(":compose:desktop:desktop-native:prepareWindowsSdl")
    val source = rootProject.file("demo/src/nativeInterop/cinterop/app_mpv.cpp")
    val output = layout.buildDirectory.file("mpv/obj/app-mpv-windows.o")
    inputs.file(source)
    inputs.file(rootProject.file("demo/src/nativeInterop/cinterop/include/app_mpv.h"))
    inputs.file(mpvSdk.map { it.file("include/mpv/client.h") })
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        commandLine(
            "clang++",
            "--target=x86_64-pc-windows-gnu",
            "--sysroot=${windowsMingwSysroot.get()}",
            "-std=c++17",
            "-O2",
            "-I${rootProject.file("demo/src/nativeInterop/cinterop/include")}",
            "-I${windowsSdlDirectory.resolve("include")}",
            "-I${mpvSdk.get().file("include").asFile}",
            "-c",
            source,
            "-o",
            output.get().asFile,
        )
    }
}

val archiveWindowsMpv by tasks.registering(Exec::class) {
    dependsOn(compileWindowsMpv)
    val objectFile = layout.buildDirectory.file("mpv/obj/app-mpv-windows.o")
    inputs.file(objectFile)
    outputs.file(mpvLibrary)
    doFirst {
        mpvLibrary.get().asFile.parentFile.mkdirs()
        commandLine("llvm-ar", "rcs", mpvLibrary.get().asFile, objectFile.get().asFile)
    }
}

windowsTarget.compilations.getByName("main").compileTaskProvider.configure {
    dependsOn(archiveWindowsWebView2)
    dependsOn(archiveWindowsMpv)
    inputs.file(webView2Library)
    inputs.file(mpvLibrary)
    compilerOptions.freeCompilerArgs.addAll(
        "-include-binary",
        webView2Library.get().asFile.absolutePath,
        "-include-binary",
        mpvLibrary.get().asFile.absolutePath,
    )
}

releaseExecutable.linkTaskProvider.configure {
    dependsOn(":compose:desktop:desktop-native:prepareWindowsSdl")
    dependsOn(prepareWindowsMpv)
}

tasks.register<Sync>("packageWindowsRelease") {
    group = "distribution"
    description = "Builds a self-contained Windows catalogue distribution."
    dependsOn(releaseExecutable.linkTaskProvider)
    dependsOn(":compose:desktop:desktop-native:prepareWindowsSdl")
    dependsOn(prepareWindowsWebView2)
    dependsOn(prepareWindowsMpv)
    dependsOn("prepareComposeResourcesTaskForCommonMain")
    from(releaseExecutable.outputFile)
    from(windowsSdlDll)
    from(webView2Sdk.map { it.file("build/native/x64/WebView2Loader.dll") })
    from(mpvSdk.map { it.file("libmpv-2.dll") })
    from(windowsCxxRuntimes)
    from(windowsIcuData) { rename { "icudtl.dat" } }
    from(windowsSdlLicense) { rename { "SDL3-LICENSE.txt" } }
    from(preparedComposeResources) {
        into("resources/composeResources/demo.generated.resources")
    }
    into(layout.buildDirectory.dir("windows-package"))
}
