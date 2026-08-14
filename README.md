# Compose Native

Compose Native adds Linux and Windows Kotlin/Native targets to Compose Multiplatform. It produces
native executables with no JVM requirement while allowing the same project to keep using official
JetBrains Compose on Android, JVM, Apple, JS, and Wasm.

The Gradle plugin works alongside `org.jetbrains.compose`: the official plugin continues to provide
Compose resources and application tasks, while Compose Native adds the desktop-native target DSL
and supports a dependency overlay for those targets. It never adds a Compose dependency or chooses
a version; every official and fork version remains explicit in the consumer's dependency block.

> Compose Native is experimental. Pin its version and test upgrades before shipping them.

## What you get

- Compose Runtime, UI, Animation, Foundation, Material, and Material 3 on Kotlin/Native desktop
- Linux x64, Linux arm64, and Windows x64 executables
- SDL3 windows, dialogs, menus, trays, notifications, clipboard, drag-and-drop, and IME input
- Skia GPU rendering with software fallback and renderer recovery
- Compose resources, packaged fonts, native views, and native Compose UI test hosts
- A `desktopNative` target group and shared `desktopNativeMain` source set

## Fork-specific Compose changes

These changes are included whenever you declare the corresponding fork artifacts. When those
artifacts are declared in `commonMain`, the plugin also keeps matching transitive JetBrains Compose
and AndroidX Android requests on the fork.

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

The native Compose artifacts depend on `dev.brahmkshatriya.skiko:skiko:0.151.4` in their published
metadata, so Gradle selects the matching Linux or Windows artifact automatically. JS and Wasm fork
metadata use official JetBrains Skiko `0.150.1`; Android Compose does not use Skiko.

## Installation

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha06"
}
```

### Fork only on desktop native

```kotlin
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
            implementation("dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha06")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha06")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.12.10-alpha06")
            implementation("dev.brahmkshatriya.skiko:skiko:0.151.4")
        }
    }
}
```

The dependencies in `commonMain` remain official on Android, JVM, Apple, JS, and Wasm. For Linux
and Windows configurations, the plugin sees the explicitly versioned fork modules in
`desktopNativeMain` and replaces the matching official module coordinates with those fork
coordinates. The same scoped rule selects the explicitly declared Skiko fork. No version comes
from plugin configuration.

Add `dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha06` to
`desktopNativeMain` as well when the project needs the native application/window APIs.

### Fork changes on every target

Put the fork coordinates in `commonMain` instead. Their Gradle metadata selects the matching fork
variant for every published target, and the plugin aligns matching transitive Compose requests:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.compose.ui:ui:1.12.10-alpha06")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha06")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.12.10-alpha06")
        }
    }
}
```

This applies the fork-specific UI, Foundation, and Material 3 changes to Android, JS, Wasm, Linux,
and Windows. Only targets with published fork variants can use this form in the current release;
JVM desktop and Apple must stay on official Compose until those fork variants are published.

### Transitive Compose dependencies

No manual substitution block is needed for full-fork modules. For every explicitly versioned fork
module in `commonMain`, the plugin substitutes the matching transitive
`org.jetbrains.compose.<family>:<module>` request throughout that project. On Android it also
substitutes `androidx.compose.<family>:<module>-android` with the fork's Android artifact.

For example, declaring
`dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha06` makes both
`org.jetbrains.compose.foundation:foundation` and
`androidx.compose.foundation:foundation-android` resolve to Foundation `1.12.10-alpha06` from the
fork. The same applies independently to each Runtime, UI, Animation, Foundation, Material, or
Material 3 module explicitly declared in `commonMain`.

AndroidX Compose coordinates are substituted only in Android configurations and only when the
corresponding fork module is published. JetBrains AndroidX support modules use fork artifacts on
Linux and Windows, while Android, JS, and Wasm retain their official platform artifacts.

The plugin does not infer a fork version or enable unlisted modules. The declaration remains the
single visible source of the selected version. Substitution affects configurations in the project
where the plugin is applied; apply the plugin and declare the required fork modules in each
independently resolving subproject that should use the full fork.

JVM desktop and Apple variants are not published in this release, so projects containing those
targets cannot put these fork coordinates in `commonMain` yet.

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
| Compose Native plugin and fork artifacts | `1.12.10-alpha06` |
| JetBrains Compose plugin | `1.12.0-rc01` |
| Kotlin and Compose compiler plugin | `2.3.20` |
| Native Skiko fork | `0.151.4` |
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
