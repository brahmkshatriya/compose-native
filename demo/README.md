# Native Compose on Wayland

This repository is a Kotlin/Native Linux x64 Compose fork and demonstration application. The single `:demo` module contains the component catalogue, platform-accent hello page, Compose resources page, WPE WebKit browser, native libmpv player, GPU interop examples, and desktop-window demonstrations. The application is a native ELF executable; a JVM is used only by Gradle and the Kotlin compiler.

## What works

- Real Compose compiler/runtime and Material 3 components
- Responsive Material navigation using direct keyed page content for low-overhead switching,
  with sidebar, bottom-bar, and back handling
- Compose Desktop-style native window API with `application`, `awaitApplication`, `Window`,
  `DialogWindow`, state objects, and single-window/coroutine entry points
- Undecorated windows with native draggable regions and native resize-edge hit testing
- Native ELF executable with no JVM, Skia, or Skiko runtime dependency
- Anti-aliased vector graphics through Skia and rich text shaping through SkParagraph
- Material button pointer input and animated state changes
- Native keyboard input with key-up/down, shortcuts, navigation keys, and committed Unicode text
- SDL clipboard and URI services, IME composition, native pointer cursors, wheel and five-button
  mouse input, focus/modifier/window information, multitouch, and external file/text drops
- Resizable, responsive layout (content reflows instead of scaling a fixed design)
- Wayland HiDPI/fractional-scale support
  - logical window size and physical framebuffer size are tracked separately
  - Compose receives the compositor-derived `Density`
  - pointer coordinates are converted to physical scene coordinates
  - moving the window to a display with another scale recreates the framebuffer at that scale
- Compose PNG, JPEG, and WebP decoding into Skia images
- Compose `BlurEffect` and `OffsetEffect`, including chained effects and blur tile modes
- Native linear, radial, and sweep gradients; composited shaders; tint, lighting, and 4x5 matrix
  color filters; and indexed triangle, strip, and fan `drawVertices` rendering
- All Compose boolean path operations: difference, intersection, union, xor, and reverse difference
- Perspective graphics layers with X/Y/Z rotation, scale, pivot, camera distance, alpha, and blending
- Rich SkParagraph text spans: color/gradient brush, alpha, size, weight, style, generic family, OpenType
  features, locale, letter spacing, baseline shift, horizontal scaling, background, decoration, and
  per-span shadows, plus inline placeholders and bidi-aware layout queries
- System fonts and byte-backed `LoadedFont` families resolved through Fontconfig and Skia
- Lifecycle-managed CPU-framebuffer and OpenGL-FBO interop through `InteropView`,
  `InteropRenderTarget`, `OpenGlInteropRenderTarget`, and `NativeView`, with normal Compose sizing,
  clipping, overlays, and input
- Interactive WPE WebKit browser with JavaScript, Media Source Extensions, WebGL, media controls,
  pointer, wheel, keyboard, focus, fractional-DPI, and responsive resize integration
- Core-profile-safe WebView presentation: WPE DMA-BUF/shared-memory frames are imported as
  opaque `GL_RGB8` textures and copied into Compose-owned FBOs with `glBlitFramebuffer`
- Native libmpv HLS playback rendered directly into an OpenGL framebuffer

## Requirements

- Linux x64 in a Wayland or X11 session
- Git, a C++17 compiler, and pkg-config
- JDK 21 for build tooling
- SDL 3.2 or newer, Fontconfig, D-Bus, WPE WebKit, EGL/OpenGL, and libmpv development files

The release link uses `--as-needed` to discard Kotlin/Native's unused default `-lcrypt`, so
`libcrypt.so.1`/`libcrypt-legacy` is not a runtime requirement.

Arch Linux:

```bash
sudo pacman -S git jdk21-openjdk gcc pkgconf sdl3 fontconfig dbus wpewebkit mesa mpv
```

Debian/Ubuntu:

```bash
sudo apt install git openjdk-21-jdk g++ pkg-config libsdl3-dev libfontconfig1-dev libdbus-1-dev libwpewebkit-2.0-dev libwpe-1.0-dev libegl-dev libgl-dev libmpv-dev
```

## Demo module

All standalone examples are consolidated in `:demo`. The catalogue provides pages for:

- Material controls, text input, lists, navigation, overlays, graphics, and animations
- The platform-accent hello example
- Compose strings, vector drawable, and byte-backed font resources
- WPE WebKit browsing and native libmpv HLS playback
- CPU/OpenGL native views, windows, dialogs, trays, notifications, and accessibility

The former `native-demo`, `hello-demo`, and `resource-demo` modules no longer exist.

## Build and run

From this directory:

```bash
./gradlew build
./gradlew run
```

The wrapper selects the Linux target, keeps Gradle intermediates inside `build/`, and installs the executable and resources together.

The build downloads Kotlin/Native tooling and external dependencies as needed. Later builds are
incremental, Gradle modules build in parallel, and Kotlin/Native uses all available backend threads.

The final executable and consolidated resource bundle are written to:

```text
build/bin/compose-wayland
build/bin/compose-resources/
```

AndroidX/Gradle intermediate outputs are kept under `build/androidx-out` and
`build/gradle-project-cache`, so app-generated files remain inside this module.

You can also call `./scripts/bootstrap.sh`, `./scripts/build.sh`, or `./scripts/run.sh` directly. Press Escape or close the window to quit.

Run the non-interactive backend regression checks with:

```bash
KTNATIVE_INPUT_SELF_TEST=1 ./build/bin/compose-wayland
KTNATIVE_WINDOW_SELF_TEST=1 ./build/bin/compose-wayland
KTNATIVE_DESKTOP_SELF_TEST=1 ./build/bin/compose-wayland
```

Run the live AT-SPI integration check from an active desktop session with an accessibility bus:

```bash
./scripts/test-atspi.py
```

The test uses `gdbus`, `dbus-monitor`, and Python 3 without Python GI bindings. It discovers the
application and semantics nodes dynamically, exercises actions and values, verifies incremental
events and registry cleanup, and writes diagnostics under the ignored `build/atspi-test` directory.

The Linux Compose UI test host registers roots from every application window and dialog, supports
custom idling resources, and captures semantics-node screenshots from the owning composed SDL
framebuffer, including GPU native views.

Set `KTNATIVE_WEBVIEW_URL` to choose the initial browser page. Set `KTNATIVE_WEBVIEW_DEBUG=1` to print load transitions, browser console messages, imported frame dimensions, permission decisions, and input diagnostics. The adapter requires the SDL host's EGL-backed OpenGL 3.3 core-profile context; it does not use legacy `glBegin`/`glEnd` rendering.

Compose animations are uncapped by the host and normally follow the display/VSync cadence. Set
`KTNATIVE_MAX_FPS` to an explicit value from 1 through 240 only when an application needs an
additional software frame-rate limit; for example, `KTNATIVE_MAX_FPS=30 ./build/bin/compose-wayland`.

## Native window API

The Linux host uses the same declarative shape as Compose Desktop:

```kotlin
fun main() = application {
    val state = rememberWindowState(size = DpSize(720.dp, 460.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "My native app",
        resizable = true,
    ) {
        MaterialTheme {
            App()
        }
    }
}
```

Multiple `Window` calls are supported. Each window owns an SDL3 window and SkiaLayer renderer while
sharing the application recomposer. Titles, visibility, resizability,
always-on-top, enabled input, size, position, minimization, maximization, and fullscreen placement
update declaratively. `DialogWindow`, key preview/bubble callbacks, `awaitApplication`, and
`launchApplication` are also available. Removing a window from composition disposes its native
resources. Resizing or moving between displays replaces only the framebuffer and updates the scene
density, preserving remembered UI state and active animations.

Desktop notifications use the freedesktop D-Bus protocol and therefore work with both the X11 and
Wayland SDL backends without a GUI-toolkit dependency:

```kotlin
if (isNotificationSupported) {
    sendNotification(
        Notification("Download complete", "The native binary is ready", Notification.Type.Info),
        applicationName = "My app",
    )
}
```

The notification pipeline is extensible. `NotificationBackend` can be implemented by an
application or another library and passed to `sendNotification`. The built-in backend supports
server capability discovery, actions, typed custom D-Bus hints, icons, timeout, progress hints,
in-place updates, explicit close, and action/close events through a `NotificationHandle`:

```kotlin
val notification = sendNotification(
    NotificationRequest(
        title = "Downloading",
        actions = listOf(NotificationAction("cancel", "Cancel")),
        hints = mapOf("transient" to NotificationHint.BooleanValue(true)),
        progress = 0.1f,
    )
)

notification.update(
    NotificationRequest(title = "Downloading", progress = 0.6f)
)
notification.close()
```

Long-running transfers use the separate `ProgressJobBackend` API. On Plasma the default backend
publishes total/processed bytes, percentage, speed, elapsed time, and cancel/suspend/resume events
through `org.kde.JobViewServer`; Plasma uses those samples to draw its native transfer-speed graph.
Other desktops receive an updating freedesktop notification fallback:

```kotlin
val copy = startProgressJob(
    ProgressJobRequest(
        title = "Copying files",
        totalBytes = totalBytes,
        cancellable = true,
    )
)

copy.update(
    ProgressJobUpdate(
        processedBytes = copiedBytes,
        bytesPerSecond = currentSpeed,
        elapsedMillis = elapsedMillis,
    )
)
copy.complete()
```

The host includes Linux platform adapters beyond SDL itself: AT-SPI accessibility over D-Bus,
StatusNotifierItem trays with dbusmenu context menus, Compose-rendered window menu bars, per-pixel
transparent undecorated top-level windows, and outgoing text/file drags through native Wayland
and Xdnd data-source protocols. The AT-SPI bridge exports Accessible, Application, Component,
Action, Text, EditableText, Image, Selection, Table, TableCell, Value, and Cache interfaces from
Compose semantics. Incoming external file/text drops continue to use SDL events. The `focusable`
flag suppresses Compose keyboard/IME focus, although SDL cannot prevent every window manager from
focusing a native decoration.

For client-side decoration libraries, pass `undecorated = true` and use the Desktop-shaped
`WindowScope.WindowDraggableArea` primitive. Keep interactive controls outside the draggable area
so they continue receiving Compose pointer events. SDL hit testing preserves native resizing from
every edge and corner.

## Compose Linux source fork

Compose Runtime publishes Linux x64 KLIBs, but Compose UI, Foundation, and Material 3 do not currently publish a complete Linux target. The prepared source fork enables `linuxX64` through the required module graph, supplies the missing Linux platform actuals, and connects Compose's non-Android graphics/text entry points to Skia. It is based on commit `c1f04f0b9b7acda3849d76fe0d271f7255ad827c`.

The Skia/SkParagraph backend covers the advanced APIs exercised by this milestone: encoded images,
boolean paths, sweep and composite shaders, color filters, vertex meshes, blur/offset render effects,
perspective graphics-layer transforms, byte-backed fonts, rich spans, bidi queries, and inline
placeholders. Skia performs compositing, raster effects, and path rendering; Fontconfig resolves
system fonts, while SkParagraph handles shaping, line layout, span attributes, and glyph masks for
brushes and shadows.

The platform host is packaged as the Compose-owned `:compose:ui:ui-sdl2` module. Its KLIB embeds
the small C++ graphics support archive and exposes the native application, window/dialog state,
input, clipboard, URI, `WindowDraggableArea`, and native CPU/GPU interop APIs. GPU native views
render into host-owned OpenGL FBOs. The module deliberately provides only generic native-view
interop; the app-owned WPE adapter imports each available WPE frame into an opaque RGB texture and
uses a core-profile framebuffer blit to populate the Compose interop surface.

GPU interop views accept an antialiased Compose `Path` mask and follow the current Compose
transform, including `rotationX`/`rotationY` perspective, through a subdivided texture mesh. The
mask clears the Skia frame at the `NativeView`'s draw position, so later Compose siblings remain
above GPU-resident native content without host readback.

Root-level GPU native views participate in the compositor's ordered rendering stream. Native views
inside isolated Compose layers, `saveLayer`, alpha/effect/shadow groups, or perspective transforms
use a retained snapshot in the active Compose layer. The snapshot is refreshed only after the native
renderer produces a new frame and is reused for scrolling, geometry changes, and ancestor-layer
property updates, preserving Compose z-order and layer semantics.

The executable stays a small native launcher; SDL3, Fontconfig, D-Bus, WPE WebKit, libmpv,
OpenGL/EGL, and their required system libraries remain dynamically linked. Cairo and Pango may
still be loaded transitively by the system mpv/FFmpeg stack, but Compose does not link or use them.

## Layout

- `src/linuxMain/kotlin/Main.kt` — WPE WebKit adapter and native application windows
- `src/linuxMain/kotlin/Catalogue.kt` — component catalogue and interactive demos
- `src/linuxMain/kotlin/DemoPages.kt` — consolidated hello and resource pages
- `src/commonMain/composeResources/` — strings, vector drawable, and byte-backed font
- `src/nativeInterop/cinterop/app_webview.cpp` — WPE frame import, input forwarding, and core-profile FBO blit
- `src/nativeInterop/cinterop/app_mpv.cpp` — native libmpv OpenGL renderer
- `build.gradle.kts` — app module configuration
- `scripts/` — checkout verification, build, and run commands
- `build/bin/compose-wayland` — final executable produced by the wrapper
- `build/bin/compose-resources/` — executable-relative Compose resource bundle
- `../compose/ui/ui-sdl2/` — prepared source checkout containing the Compose-owned Linux window
  host, Skia backend, cinterops, and embedded native support archive
- `../navigation3/navigation3-ui/` — optional Navigation 3 UI port with a Linux native target
