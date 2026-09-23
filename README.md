# Compose Native

Compose Multiplatform for Linux and Windows Kotlin/Native. It produces native executables with no
JVM requirement and can be added to an existing multiplatform project without replacing official
Compose on unsupported targets.

Supported native targets: Linux x64, Linux arm64, Windows x64, macOS x64, and macOS arm64.

## Installation

The Gradle plugin is published to Maven Central, so add it to plugin resolution in
`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Apply the plugin alongside the official Compose plugin:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.13.0-alpha01"
    id("dev.brahmkshatriya.compose") version "1.13.0-alpha02"
}
```

Then choose how broadly you want to use the fork.

### Native only

Use official Compose in `commonMain` and the fork only in `desktopNativeMain`. Android, JVM, Apple,
JS, and Wasm continue using official Compose.

```kotlin
val composeNativeVersion = "1.13.0-alpha02"

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
            implementation("org.jetbrains.compose.foundation:foundation:1.13.0-alpha01")
            implementation("org.jetbrains.compose.material3:material3:1.13.0-alpha01")
        }

        desktopNativeMain.dependencies {
            implementation("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.material3:material3:$composeNativeVersion")
            implementation(
                "dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion"
            )
        }
    }
}
```

This option keeps Android, JVM desktop, Apple, JS, and Wasm on official Compose while overlaying
only the Linux and Windows Kotlin/Native desktop targets.

### Full fork

Put the fork dependencies in `commonMain` to use the modified `foundation` and `material3` on
Android, JVM desktop, JS, Wasm JS, and iOS. On macOS, Linux, and Windows Kotlin/Native, the same
fork version selects the larger native closure required by the native desktop backend.

```kotlin
val composeNativeVersion = "1.13.0-alpha02"

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.material3:material3:$composeNativeVersion")
        }

        desktopNativeMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion"
            )
        }
    }
}
```

On Android, JVM desktop, JS, Wasm JS, and iOS, the application opts into this fork only for
`foundation` and `material3`; the remaining Compose, AndroidX, and Skiko dependencies stay on their
official JetBrains/AndroidX coordinates. macOS x64/arm64, Linux x64/arm64, and Windows x64 use the
larger native fork closure and native Skiko required by the desktop-native backend.

The published Apple targets are `iosArm64`, `iosSimulatorArm64`, `macosX64`, and `macosArm64`.
iOS uses official support libraries and Skiko; macOS uses the native fork closure.

To run the JVM component catalogue against the in-tree fork implementation:

```bash
./gradlew :demo:runJvmCatalogue
```

<details>
<summary><strong>Fork-specific Compose changes</strong></summary>

### Foundation: stackable sticky headers

`LazyColumn`, `LazyRow`, and lazy grids add an `isSlidable` argument to `stickyHeader`:

```kotlin
LazyColumn {
    stickyHeader(isSlidable = false) {
        Text("Always pinned")
    }

    stickyHeader(isSlidable = true) {
        Text("Pushed away by the next header")
    }
}
```

`isSlidable = true` keeps the normal sticky-header behavior. With `false`, the header stays pinned
and later sticky headers stack after it. The default is `true`, so existing calls keep their normal
behavior. Sticky-header positioning also accounts for the lazy container's top content padding.

### Material 3: desktop bottom-sheet behavior

The fork adjusts `BottomSheetScaffold` without adding public API:

- nested scrollable content no longer causes an extra sheet bounce;
- mouse-wheel delta is not handed to the sheet when a nested child reaches its scroll boundary;
- mouse dragging does not move/open the sheet, while touch dragging continues to work normally;
- sheet anchor updates and placement no longer apply duplicate visual movement.

</details>

`desktopNative` creates `linuxX64`, `linuxArm64`, and `mingwX64` plus a shared
`desktopNativeMain` source set. `desktop-native` provides the native window/application APIs and
brings in the native Skiko dependency.

## System requirements

JDK 21 and the Kotlin/Native toolchain are required.

On macOS, the desktop-native backend selects Metal when a system Metal device is available and
falls back to OpenGL otherwise. Set `COMPOSE_MACOS_RENDER_API=AUTO`, `METAL`, or `OPENGL` to
override the selection (`AUTO` is the default). Native OpenGL interop requires the OpenGL renderer.

On Linux, install SDL3, Fontconfig, D-Bus, and OpenGL/EGL development libraries. For example:

```shell
# Arch Linux
sudo pacman -S jdk21-openjdk gcc pkgconf sdl3 fontconfig dbus mesa

# Debian / Ubuntu with SDL3 packages available
sudo apt install openjdk-21-jdk g++ pkg-config libsdl3-dev \
    libfontconfig1-dev libdbus-1-dev libegl-dev libgl-dev
```

Windows x64 builds do not require a separately installed SDL3 SDK. The Gradle plugin downloads the
matching SDL3 bundle for linking and stages SDL3, Skia ICU data, and the MinGW runtime DLLs for
executable run tasks and Windows distributions.

## Published targets

| Target | Fork artifacts |
| --- | --- |
| Linux x64 | Yes |
| Linux arm64 | Yes |
| Windows x64 | Yes |
| Android | Yes |
| JS | Yes |
| Wasm JS | Yes |
| JVM desktop | Yes |
| iOS arm64 | Yes |
| iOS simulator arm64 | Yes |
| macOS arm64 | Yes |
| macOS x64 | Yes |
| Windows arm64 | No |

## Packaging

When an executable is configured, the plugin also uses `src/main/kotlin` as native desktop source
and `src/main/composeResources` as native Compose resources. Linux builds get AppDir/AppImage tasks;
Windows x64 gets a self-contained distribution directory and zip task.

Application metadata can be customized with `composeNativeApplication`:

```kotlin
composeNativeApplication {
    applicationName.set("Example")
    packageName.set("com.example.app")
    executableName.set("example")
}
```

## Versions

| Component | Version |
| --- | --- |
| Compose Native plugin / fork | `1.13.0-alpha02` |
| JetBrains Compose plugin | `1.13.0-alpha01` |
| Kotlin / Compose compiler | `2.3.20` |
| Native Skiko | `0.153.1` |

## Limitations

- Windows arm64 is not supported.
- Windows accessibility does not yet expose a complete UI Automation provider.
- Transparent windows depend on compositor support.
- Linux arm64 cross-linking from x64 requires an arm64 sysroot with the native dependencies.
- SVG files are not currently supported by the native Compose resource reader.

See [PUBLISHING.md](PUBLISHING.md) for release and publication details.

## License

Compose sources retain their upstream AndroidX and JetBrains licenses. Packaged third-party runtime
components retain their respective licenses.
