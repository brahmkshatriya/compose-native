import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

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
        .resolve("compose/ui/ui-sdl2/build/windows-sdl/SDL3-3.4.10/x86_64-w64-mingw32")
val windowsMingwSysroot =
    providers.gradleProperty("compose.windows.mingwSysroot").orElse(
        providers.environmentVariable("KONAN_DATA_DIR").map {
            "$it/dependencies/msys2-mingw-w64-x86_64-2"
        }
    ).orElse("${System.getProperty("user.home")}/.konan/dependencies/msys2-mingw-w64-x86_64-2")
val windowsIcuData by
    configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

dependencies {
    windowsIcuData(
        "org.jetbrains.skiko:skiko-mingwx64:${libs.versions.skiko.get()}:icudtl@dat"
    )
}

val windowsTarget =
    kotlin.mingwX64 {
        binaries {
            executable {
                baseName = "compose-windows-demo"
                entryPoint = "androidx.compose.demo.windows.main"
                linkerOpts("-L${windowsSdlDirectory.resolve("lib").absolutePath}")
            }
        }
    }

kotlin.sourceSets {
    val mingwX64Main by getting {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("demo/src/linuxMain/kotlin"))
        dependencies {
            implementation(project(":compose:animation:animation"))
            implementation(project(":compose:components:components-resources"))
            implementation(project(":compose:foundation:foundation"))
            implementation(project(":compose:material3:material3"))
            implementation(project(":compose:ui:ui-backhandler"))
            implementation(project(":compose:ui:ui-sdl2"))
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

releaseExecutable.linkTaskProvider.configure {
    dependsOn(":compose:ui:ui-sdl2:prepareWindowsSdl")
}

tasks.register<Sync>("packageWindowsRelease") {
    group = "distribution"
    description = "Builds a self-contained Windows catalogue distribution."
    dependsOn(releaseExecutable.linkTaskProvider)
    dependsOn(":compose:ui:ui-sdl2:prepareWindowsSdl")
    dependsOn("prepareComposeResourcesTaskForCommonMain")
    from(releaseExecutable.outputFile)
    from(windowsSdlDll)
    from(windowsCxxRuntimes)
    from(windowsIcuData) { rename { "icudtl.dat" } }
    from(windowsSdlLicense) { rename { "SDL3-LICENSE.txt" } }
    from(preparedComposeResources) {
        into("resources/composeResources/demo.generated.resources")
    }
    into(layout.buildDirectory.dir("windows-package"))
}
