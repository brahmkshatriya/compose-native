# Compose Native

Compose Native brings Compose Multiplatform UI to native Linux and Windows executables. Apps are
compiled with Kotlin/Native and do not require a JVM at runtime.

This repository currently provides development snapshots for:

| Target | Status | Output |
| --- | --- | --- |
| Linux x64 | Available | Native ELF executable, AppDir, tar.gz, or AppImage |
| Windows x64 | Available | Native PE executable and self-contained application directory |
| Linux arm64 | Not yet available | — |
| Windows arm64 | Not yet available | — |
| macOS | Not included in this fork | — |

The APIs and artifacts are still under active development. Pin both the Compose Native and
[Skiko Native](https://github.com/brahmkshatriya/skiko-native) revisions used by your project.

## Features

The native targets include:

* Compose Runtime, state, recomposition, saveable state, and BackHandler
* Compose UI layout, drawing, text, focus, semantics, pointer input, and keyboard input
* Animation, Foundation, Foundation Layout, Material, and Material 3
* Compose resources for strings, plurals, vector drawables, encoded images, and packaged fonts
* Skia GPU rendering, software rendering, renderer fallback, and device/context recovery
* Multiple windows, dialogs, fullscreen, always-on-top windows, and transparent windows
* Decorated and undecorated windows, draggable title regions, and native resize edges
* Clipboard, URI opening, IME text composition, cursors, scrolling, multitouch, and drag-and-drop
* Menu bars, tray icons and menus, desktop notifications, and progress notifications
* CPU framebuffer and OpenGL native-view interop inside Compose layouts
* HiDPI and fractional display scaling
* Dark/light system theme support
* Native Compose UI test hosts

### Platform feature matrix

| Feature | Linux x64 | Windows x64 |
| --- | --- | --- |
| GPU renderer | OpenGL | Direct3D 12 or OpenGL |
| Automatic software fallback | Yes | Yes |
| Multiple windows and dialogs | Yes | Yes |
| Clipboard, URI, keyboard, IME, mouse, touch | Yes | Yes |
| Incoming and outgoing text/file drag-and-drop | Yes | Yes |
| Menu bars, tray menus, notifications | Yes | Yes |
| Transparent windows | Compositor dependent | Compositor dependent |
| Double/triple buffering requests | Yes | Yes |
| System theme | Yes | Yes |
| System accent color | Yes, when exposed by the desktop portal | Not currently exposed |
| Accessibility | AT-SPI | Semantics and native events; full UI Automation provider pending |
| CPU native views | Yes | Yes |
| OpenGL native views | Yes | Yes |
| Demo WebView | WPE WebKit | Not bundled |
| Demo video player | libmpv | Not bundled |

## Prerequisites

All builds require:

* Git
* JDK 21 for Gradle and the Kotlin compiler
* A checkout of [Skiko Native](https://github.com/brahmkshatriya/skiko-native)
* A checkout of this repository

The repositories do not need to be adjacent, but Skiko Native must be published to Maven Local
before building Compose Native.

```shell
git clone https://github.com/brahmkshatriya/skiko-native.git
git clone https://github.com/brahmkshatriya/compose-native.git
```

## Build and run on Linux

Linux builds require an x86_64 Wayland or X11 session, a C++17 compiler, pkg-config, SDL 3,
Fontconfig, D-Bus, EGL/OpenGL, WPE WebKit, and libmpv development files.

Arch Linux:

```shell
sudo pacman -S git jdk21-openjdk gcc pkgconf sdl3 fontconfig dbus wpewebkit mesa mpv
```

Debian or Ubuntu:

```shell
sudo apt install git openjdk-21-jdk g++ pkg-config libsdl3-dev libfontconfig1-dev \
    libdbus-1-dev libwpewebkit-2.0-dev libwpe-1.0-dev libegl-dev libgl-dev libmpv-dev
```

Publish the Linux Skiko Native artifact:

```shell
cd skiko-native
./gradlew -p skiko \
    publishKotlinMultiplatformPublicationToMavenLocal \
    publishLinuxX64PublicationToMavenLocal \
    -Pskiko.awt.enabled=false \
    -Pskiko.native.linux.enabled=true
```

Build and run the complete catalogue from the Compose Native checkout:

```shell
cd compose-native
./gradlew :demo:runLinuxReleaseDistributable
```

The first build downloads the Kotlin/Native toolchain and other Gradle dependencies. Subsequent
builds are incremental.

### Linux packages

Create a portable AppDir:

```shell
./gradlew :demo:prepareLinuxReleaseAppDir
```

Create a tar.gz archive:

```shell
./gradlew :demo:packageLinuxReleaseTarGz
```

Create an AppImage:

```shell
./gradlew :demo:packageLinuxReleaseAppImage
```

AppImage packaging requires `appimagetool` on `PATH`, or its path in `APPIMAGETOOL`. Distribution
outputs are written below:

```text
out/compose-multiplatform-core/demo/build/compose/binaries/main-release/
```

The Linux package uses system SDL, Fontconfig, D-Bus, OpenGL/EGL, WPE WebKit, and libmpv libraries.

## Build the Windows application

The verified cross-build path uses an x86_64 Linux host. Install:

* JDK 21
* Wine for optional local testing
* `clang-cl`, `lld-link`, `llvm-lib`, `clang++`, and `llvm-ar`
* [xwin](https://github.com/Jake-Shadle/xwin)

Create the MSVC-compatible SDK layout and publish the Windows Skiko Native artifact:

```shell
cd skiko-native
xwin --accept-license --arch x86_64 splat --output "$PWD/.xwin"

SKIKO_WINDOWS_SDK_ROOT="$PWD/.xwin" \
./gradlew -p skiko \
    publishKotlinMultiplatformPublicationToMavenLocal \
    publishMingwX64PublicationToMavenLocal \
    -Pskiko.awt.enabled=false \
    -Pskiko.native.windows.enabled=true
```

Then package the Windows catalogue:

```shell
cd ../compose-native
./gradlew :windows-native-demo:packageWindowsRelease
```

The package is written to:

```text
out/compose-multiplatform-core/windows-native-demo/build/windows-package/
```

Copy the entire directory to a Windows x64 machine and run `compose-windows-demo.exe`. The package
already includes SDL3, the required MinGW runtime DLLs, Compose resources, and `icudtl.dat`.

The same Windows target can be built on Windows with Visual Studio Build Tools 2022, the Windows
SDK, LLVM, and `SKIKO_VSBT_PATH`. See the
[Skiko Native build instructions](https://github.com/brahmkshatriya/skiko-native#building-kotlinnative-artifacts)
for the renderer toolchain requirements.

## Use Compose Native in your own application

The catalogue modules are the easiest application templates:

* `demo/build.gradle.kts` for Linux
* `windows-native-demo/build.gradle.kts` for Windows

Both use the familiar Compose Desktop-shaped application API:

```kotlin
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "My Compose Native app",
    ) {
        MaterialTheme {
            Text("Hello from Kotlin/Native")
        }
    }
}
```

### Publish the libraries to Maven Local

Publish the Compose modules and native variants from this checkout:

```shell
./gradlew :mpp:publishComposeJbToMavenLocal \
    -Pcompose.platforms=linux,mingw

./gradlew :compose:ui:ui-sdl2:publishToMavenLocal
```

Development artifacts use version `9999.0.0-SNAPSHOT`. Put `mavenLocal()` before remote
repositories in the consumer build:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-alpha01"
}

repositories {
    mavenLocal()
    mavenCentral()
    google()
}
```

A minimal Kotlin Multiplatform dependency set is:

```kotlin
kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }
    mingwX64 {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:9999.0.0-SNAPSHOT")
            implementation("org.jetbrains.compose.ui:ui:9999.0.0-SNAPSHOT")
            implementation("org.jetbrains.compose.foundation:foundation:9999.0.0-SNAPSHOT")
            implementation("org.jetbrains.compose.material3:material3:9999.0.0-SNAPSHOT")
            implementation(
                "org.jetbrains.compose.components:components-resources:9999.0.0-SNAPSHOT"
            )
        }
        linuxX64Main.dependencies {
            implementation("org.jetbrains.compose.ui:ui-sdl2:9999.0.0-SNAPSHOT")
        }
        mingwX64Main.dependencies {
            implementation("org.jetbrains.compose.ui:ui-sdl2:9999.0.0-SNAPSHOT")
        }
    }
}
```

Set `entryPoint` to the fully qualified package name of your top-level `main` function. Consumer
projects also need the matching
`org.jetbrains.skiko:skiko:0.0.1-linux-native-SNAPSHOT` publication in Maven Local.

## Demo catalogue

The Linux and Windows demo applications share the same catalogue. It includes pages for:

* Material controls, text fields, selection, lists, navigation, dialogs, and animations
* Compose strings, vector resources, images, and packaged fonts
* Graphics, gradients, paths, effects, and transforms
* Clipboard, URI launching, drag sources, and external drops
* Multiple windows, dialogs, transparency, fullscreen, menus, trays, and notifications
* CPU and OpenGL native views
* Web browsing and video playback status

On Linux, the browser and video pages use WPE WebKit and libmpv. On Windows, those two integrations
are reported as unavailable; the remaining catalogue pages are shared.

## Configuration

Skiko Native supports renderer selection, software fallback, VSync, framebuffer selection, GPU
priority, cache limits, pixel geometry, and FPS diagnostics. The most commonly useful settings are:

```shell
SKIKO_RENDER_API=OPENGL
SKIKO_FRAME_BUFFERING=TRIPLE
SKIKO_VSYNC_ENABLED=true
SKIKO_GPU_PRIORITY=discrete
SKIKO_FPS_ENABLED=true
```

Supported Windows renderer values are `DIRECT3D`, `OPENGL`, `SOFTWARE_FAST`, and
`SOFTWARE_COMPAT`. Linux supports `OPENGL`, `SOFTWARE_FAST`, and `SOFTWARE_COMPAT`. See the
[Skiko Native README](https://github.com/brahmkshatriya/skiko-native) for all renderer settings.

Demo-specific options:

```shell
KTNATIVE_WEBVIEW_URL=https://example.com
KTNATIVE_WEBVIEW_DEBUG=1
KTNATIVE_MAX_FPS=60
```

## Current limitations

* Native desktop artifacts are development snapshots and are not published to Maven Central.
* Linux and Windows targets are currently x64 only.
* Windows WebView2 and libmpv integrations are not bundled.
* Windows accessibility does not yet expose a complete UI Automation provider.
* Windows OpenGL native views require an OpenGL window; the packaged catalogue selects OpenGL by
  default for those pages.
* Transparent windows depend on compositor support.
* A triple-buffering request can create a three-buffer swap chain, but final presentation behavior
  remains driver/compositor controlled.
* SVG files are not currently supported by the native Compose resource reader.

## License

Compose sources retain their upstream AndroidX and JetBrains licenses. Packaged third-party runtime
components retain their own licenses; the Windows distribution includes the SDL license.
