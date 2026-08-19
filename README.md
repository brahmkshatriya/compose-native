# Compose Native

Compose Multiplatform for Linux and Windows Kotlin/Native. It produces native executables with no
JVM requirement and can be added to an existing multiplatform project without replacing official
Compose on unsupported targets.

Supported native targets: Linux x64, Linux arm64, and Windows x64.

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
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha09"
}
```

Then choose how broadly you want to use the fork.

### Native only

Use official Compose in `commonMain` and the fork only in `desktopNativeMain`. Android, JVM, Apple,
JS, and Wasm continue using official Compose.

```kotlin
val composeNativeVersion = "1.12.10-alpha09"

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui:1.12.0-rc01")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0-rc01")
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
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

This is the option to use when the same project also targets JVM desktop or Apple, where fork
artifacts are not currently published.

### Full fork

Put the fork dependencies in `commonMain` to use them on every published fork target: Android, JS,
Wasm JS, Linux, and Windows.

```kotlin
val composeNativeVersion = "1.12.10-alpha09"

kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
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

Do not use the full-fork setup in a project that also targets JVM desktop or Apple until fork
variants for those platforms are published.

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

On Linux, install SDL3, Fontconfig, D-Bus, and OpenGL/EGL development libraries. For example:

```shell
# Arch Linux
sudo pacman -S jdk21-openjdk gcc pkgconf sdl3 fontconfig dbus mesa

# Debian / Ubuntu with SDL3 packages available
sudo apt install openjdk-21-jdk g++ pkg-config libsdl3-dev \
    libfontconfig1-dev libdbus-1-dev libegl-dev libgl-dev
```

Windows x64 builds do not require a separately installed SDL3 SDK. The Gradle plugin downloads the
matching SDL3 bundle for linking and includes the runtime in its Windows distribution tasks.

## Published targets

| Target | Fork artifacts |
| --- | --- |
| Linux x64 | Yes |
| Linux arm64 | Yes |
| Windows x64 | Yes |
| Android | Yes |
| JS | Yes |
| Wasm JS | Yes |
| JVM desktop | No |
| iOS / macOS | No |
| Windows arm64 | No |

JVM desktop and Apple projects can still use official JetBrains Compose alongside the native fork.



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
| Compose Native plugin / fork | `1.12.10-alpha09` |
| JetBrains Compose plugin | `1.12.0-rc01` |
| Kotlin / Compose compiler | `2.3.20` |
| Native Skiko | `0.151.4` |

## Limitations

- JVM desktop and Apple fork artifacts are not published yet.
- Windows arm64 is not supported.
- Windows accessibility does not yet expose a complete UI Automation provider.
- Transparent windows depend on compositor support.
- Linux arm64 cross-linking from x64 requires an arm64 sysroot with the native dependencies.
- SVG files are not currently supported by the native Compose resource reader.

See [PUBLISHING.md](PUBLISHING.md) for release and publication details.

## License

Compose sources retain their upstream AndroidX and JetBrains licenses. Packaged third-party runtime
components retain their respective licenses.
