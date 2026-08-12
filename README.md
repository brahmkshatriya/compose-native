# Compose Native

Compose Native adds Linux and Windows Kotlin/Native targets to Compose Multiplatform. It produces
native executables with no JVM requirement while allowing the same project to keep using official
JetBrains Compose on Android, JVM, Apple, JS, and Wasm.

The Gradle plugin works alongside `org.jetbrains.compose`: the official plugin continues to provide
Compose resources and application tasks, while Compose Native adds only the desktop-native target
DSL. You explicitly declare every Compose artifact and version that your project uses; the plugin
does not add, replace, or select dependencies.

> Compose Native is experimental. Pin its version and test upgrades before shipping them.

## What you get

- Compose Runtime, UI, Animation, Foundation, Material, and Material 3 on Kotlin/Native desktop
- Linux x64, Linux arm64, and Windows x64 executables
- SDL3 windows, dialogs, menus, trays, notifications, clipboard, drag-and-drop, and IME input
- Skia GPU rendering with software fallback and renderer recovery
- Compose resources, packaged fonts, native views, and native Compose UI test hosts
- A `desktopNative` target group and shared `desktopNativeMain` source set

## Fork-specific Compose changes

These changes are included whenever you declare the corresponding fork artifacts. If an Android
dependency brings the original AndroidX artifact into the same graph, use the explicit consumer-side
substitution shown below.

### Foundation: stackable sticky headers

`LazyColumn`, `LazyRow`, and lazy grids gain an `isSlidable` argument on `stickyHeader`:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Contacts(contacts: List<Contact>) {
    LazyColumn(contentPadding = PaddingValues(top = 16.dp)) {
        stickyHeader(isSlidable = false) {
            Text("Contacts")
        }

        contacts.groupBy(Contact::firstLetter).forEach { (letter, group) ->
            stickyHeader(isSlidable = true) {
                Text(letter.toString())
            }
            items(group) { contact ->
                Text(contact.name)
            }
        }
    }
}
```

- `isSlidable = true` preserves the usual behavior: the next sticky header pushes this one away.
- `isSlidable = false` keeps the header pinned, allowing later sticky headers to stack after it.
- Sticky-header positioning accounts for the lazy container's top content padding.

The parameter defaults to `true`, so existing `stickyHeader` calls retain their normal behavior.

### Material 3: better desktop bottom sheets

The fork changes `BottomSheetScaffold` behavior without requiring a new public API:

- A nested vertically scrollable child no longer causes the bottom sheet to make an extra bounce.
- Mouse dragging does not accidentally open or move the bottom sheet; direct sheet dragging remains
  touch-oriented.
- When a nested child reaches its scroll boundary, remaining mouse-wheel delta is not passed to the
  bottom sheet.
- Sheet placement and anchor movement are handled separately, avoiding duplicate visual movement.

Touch dragging and nested touch scrolling continue to settle the sheet through its normal anchors.

## Supported targets

| Target | Published fork artifacts |
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

The native Compose artifacts depend on `dev.brahmkshatriya.skiko:skiko:0.151.3` in their published
metadata, so Gradle selects the matching Linux or Windows artifact automatically. JS and Wasm fork
metadata use official JetBrains Skiko `0.150.1`; Android Compose does not use Skiko.

## Installation

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha01"
}
```

### Only Native

```kotlin
kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }

    sourceSets {
        desktopNativeMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha01"
            )
        }
    }
}
```

### Use fork-specific changes

Declare the forked Foundation and Material 3 modules directly. This makes the selected fork version
part of the build instead of plugin configuration:

```kotlin
val composeForkVersion = "1.12.10-alpha01"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.foundation:" +
                    "foundation:$composeForkVersion"
            )
            implementation(
                "dev.brahmkshatriya.compose.material3:" +
                    "material3:$composeForkVersion"
            )
        }
    }
}
```

Libraries in the same application may still request the original JetBrains Compose coordinates.
Android dependencies may additionally request the underlying `androidx.compose.*-android`
artifacts. Substitute only the modules whose fork-specific behavior you want:

```kotlin
val forkSubstitutions = mapOf(
    "org.jetbrains.compose.foundation:foundation" to
        "dev.brahmkshatriya.compose.foundation:foundation:$composeForkVersion",
    "org.jetbrains.compose.material3:material3" to
        "dev.brahmkshatriya.compose.material3:material3:$composeForkVersion",
    "androidx.compose.foundation:foundation-android" to
        "dev.brahmkshatriya.compose.foundation:foundation-android:$composeForkVersion",
    "androidx.compose.material3:material3-android" to
        "dev.brahmkshatriya.compose.material3:material3-android:$composeForkVersion",
)

configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        forkSubstitutions.forEach { (original, fork) ->
            substitute(module(original)).using(module(fork))
        }
    }
}
```

The `org.jetbrains.compose.*` rules keep transitive multiplatform dependencies aligned with the
declared fork. The `androidx.compose.*-android` rules ensure Android uses the fork AARs instead of
pulling the original AndroidX implementations back into the graph. This is consumer configuration,
not plugin behavior, so every substituted coordinate and version remains visible.

This example assumes every target uses a published fork variant: Android, JS, Wasm JS, Linux x64,
Linux arm64, or Windows x64. In a project that also targets JVM desktop or Apple platforms, put the
fork dependencies in a target-specific or custom intermediate source set and scope the rules with
`configurations.matching { ... }.configureEach`; those platforms must retain official Compose.

## Native system requirements

All builds require JDK 21 and the Kotlin/Native toolchain downloaded by Gradle.

Linux applications need SDL3, Fontconfig, D-Bus, and OpenGL/EGL development libraries while
linking, and the corresponding shared libraries at runtime. For example:

```shell
# Arch Linux
sudo pacman -S jdk21-openjdk gcc pkgconf sdl3 fontconfig dbus mesa

# Debian or Ubuntu, where SDL3 packages are available
sudo apt install openjdk-21-jdk g++ pkg-config libsdl3-dev \
    libfontconfig1-dev libdbus-1-dev libegl-dev libgl-dev
```

Windows applications must package `SDL3.dll` beside the executable. The repository's Windows demo
shows a complete distribution setup, including the required runtime files.

## Native platform capabilities

| Capability | Linux x64 / arm64 | Windows x64 |
| --- | --- | --- |
| GPU renderer | OpenGL | Direct3D 12 or OpenGL |
| Software fallback | Yes | Yes |
| Multiple windows and dialogs | Yes | Yes |
| Clipboard, URI, keyboard, IME, mouse, and touch | Yes | Yes |
| Text and file drag-and-drop | Yes | Yes |
| Menu bars, tray menus, and notifications | Yes | Yes |
| Transparent and undecorated windows | Yes, compositor dependent | Yes |
| System theme and accent color | Yes | Yes |
| Accessibility | AT-SPI | Native events; complete UI Automation provider pending |
| CPU framebuffer and OpenGL native views | Yes | Yes |

Renderer behavior can be configured with environment variables:

```shell
SKIKO_RENDER_API=OPENGL
SKIKO_FRAME_BUFFERING=TRIPLE
SKIKO_VSYNC_ENABLED=true
SKIKO_GPU_PRIORITY=discrete
SKIKO_FPS_ENABLED=true
```

Linux supports `OPENGL`, `SOFTWARE_FAST`, and `SOFTWARE_COMPAT`. Windows supports `DIRECT3D`,
`OPENGL`, `SOFTWARE_FAST`, and `SOFTWARE_COMPAT`. See the
[Skiko Native documentation](https://github.com/brahmkshatriya/skiko-native) for all settings.

## Version compatibility

| Component | Version |
| --- | --- |
| Compose Native plugin and fork artifacts | `1.12.10-alpha01` |
| JetBrains Compose plugin | `1.12.0-rc01` |
| Kotlin and Compose compiler plugin | `2.3.20` |
| Native Skiko fork | `0.151.3` |
| Official Skiko used by non-native targets | `0.150.1` |

These are the versions used by the example and current fork publication. Dependency versions are
visible in the build script and are never inferred by the plugin.

## Examples and limitations

The `demo` and `windows-native-demo` modules demonstrate native windows, Material controls,
resources, graphics, menus, trays, notifications, drag-and-drop, native views, web content, and
video playback.

Current limitations:

- Windows arm64 is not supported.
- JVM desktop and Apple fork artifacts are not published in this release.
- Windows accessibility does not yet expose a complete UI Automation provider.
- Transparent windows depend on compositor support.
- Linux arm64 cross-linking from x64 requires an arm64 sysroot containing the application's native
  dependencies.
- SVG files are not currently supported by the native Compose resource reader.

For publication and release-maintainer instructions, see [PUBLISHING.md](PUBLISHING.md).

## License

Compose sources retain their upstream AndroidX and JetBrains licenses. Packaged third-party runtime
components retain their respective licenses.
