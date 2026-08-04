# Native Compose on Wayland

This is a Kotlin/Native Linux x64 proof that renders real Compose Material 3 UI in a native Wayland window. The demo embeds WPE WebKit through the Linux `NativeView` API and opens YouTube in a responsive, interactive native web view. It also includes a native libmpv HLS player. Compose draws through a Cairo graphics and Pango text backend, while an SDL OpenGL compositor presents external EGL/OpenGL content and the transparent Cairo UI layer.

There is no JVM in the application at runtime. A JDK is required only to run Gradle and the Kotlin compiler.

## What works

- Real Compose compiler/runtime and Material 3 components
- Navigation 3 runtime and `NavDisplay`, including lifecycle-aware entries, animated navigation,
  back handling, saved state, and deep-link matching
- Compose Desktop-style native window API with `application`, `awaitApplication`, `Window`,
  `DialogWindow`, state objects, and single-window/coroutine entry points
- Undecorated windows with native draggable regions and native resize-edge hit testing
- Native ELF executable with no JVM, Skia, or Skiko runtime dependency
- Anti-aliased vector graphics through Cairo and real system-font shaping through Pango/HarfBuzz
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
- Compose PNG, JPEG, and WebP decoding into Cairo image surfaces
- Compose `BlurEffect` and `OffsetEffect`, including chained effects and blur tile modes
- Native linear, radial, and sweep gradients; composited shaders; tint, lighting, and 4x5 matrix
  color filters; and indexed triangle, strip, and fan `drawVertices` rendering
- All Compose boolean path operations: difference, intersection, union, xor, and reverse difference
- Perspective graphics layers with X/Y/Z rotation, scale, pivot, camera distance, alpha, and blending
- Rich Pango text spans: color/gradient brush, alpha, size, weight, style, generic family, OpenType
  features, locale, letter spacing, baseline shift, horizontal scaling, background, decoration, and
  per-span shadows, plus inline placeholders and bidi-aware layout queries
- System fonts and byte-backed `LoadedFont` families registered privately with Fontconfig/Pango
- Lifecycle-managed CPU-framebuffer and OpenGL-FBO interop through `InteropView`,
  `InteropRenderTarget`, `OpenGlInteropRenderTarget`, and `NativeView`, with normal Compose sizing,
  clipping, overlays, and input
- Zero-readback WPE WebKit EGL-image rendering with pointer, wheel, keyboard, focus, fractional-DPI,
  and responsive resize integration
- Interactive WPE WebKit browser with JavaScript, Media Source Extensions, WebGL, and media controls
- Native libmpv HLS playback rendered directly into an OpenGL framebuffer

## Requirements

- Linux x64 in a Wayland or X11 session
- Git, a C++17 compiler, and pkg-config
- JDK 21 for build tooling
- SDL2, Cairo, Pango, Fontconfig, libjpeg, libwebp, D-Bus, WPE WebKit, EGL/OpenGL, and libmpv development files

The release link uses `--as-needed` to discard Kotlin/Native's unused default `-lcrypt`, so
`libcrypt.so.1`/`libcrypt-legacy` is not a runtime requirement.

Arch Linux:

```bash
sudo pacman -S git jdk21-openjdk gcc pkgconf sdl2-compat cairo pango fontconfig libjpeg-turbo libwebp dbus wpewebkit mesa mpv
```

Debian/Ubuntu:

```bash
sudo apt install git openjdk-21-jdk g++ pkg-config libsdl2-dev libcairo2-dev libpango1.0-dev libfontconfig1-dev libjpeg-dev libwebp-dev libdbus-1-dev libwpewebkit-2.0-dev libwpe-1.0-dev libegl-dev libgl-dev libmpv-dev
```

## Build and run

Run these commands from `.compose-core/native-demo`:

```bash
./gradlew build
./gradlew run
```

The app module and the prepared Compose Linux source checkout both live under `.compose-core`.
The build downloads Kotlin/Native tooling and Compose dependencies as needed. Later builds are
incremental, Gradle modules build in parallel, and Kotlin/Native uses all available backend threads.

The final executable is written to:

```text
build/bin/compose-wayland
```

AndroidX/Gradle intermediate outputs are kept under `build/androidx-out` and
`build/gradle-project-cache`, so app-generated files remain inside this module.

You can also call `./scripts/bootstrap.sh`, `./scripts/build.sh`, or `./scripts/run.sh` directly. Press Escape or close the window to quit.

Run the non-interactive backend regression checks with:

```bash
KTNATIVE_BACKEND_SELF_TEST=1 ./build/bin/compose-wayland
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

Set `KTNATIVE_WEBVIEW_URL` to open another initial page. Set `KTNATIVE_WEBVIEW_DEBUG=1` to print
load transitions, browser console messages, frame dimensions, and input diagnostics.

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

Multiple `Window` calls are supported. Each window owns an SDL/Wayland surface and Cairo
framebuffer while sharing the application recomposer. Titles, visibility, resizability,
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

SDL2 does not expose Wayland protocols for system menu bars, status-notifier trays,
screen-reader AT-SPI, transparent top-level surfaces, or drag-source data offers. Those remain
separate Linux platform adapters; the core host currently supplies external drop targets but not
native drag initiation. The `focusable` flag suppresses Compose keyboard/IME focus,
although SDL2 cannot prevent the compositor from focusing the native decoration itself.

For client-side decoration libraries, pass `undecorated = true` and use the Desktop-shaped
`WindowScope.WindowDraggableArea` primitive. Keep interactive controls outside the draggable area
so they continue receiving Compose pointer events. SDL hit testing preserves native resizing from
every edge and corner.

## Compose Linux source fork

Compose Runtime publishes Linux x64 KLIBs, but Compose UI, Foundation, and Material 3 do not currently publish a complete Linux target. The prepared source fork enables `linuxX64` through the required module graph, supplies the missing Linux platform actuals, and separates Compose's non-Android graphics/text entry points from Skia. It is based on commit `c1f04f0b9b7acda3849d76fe0d271f7255ad827c`.

The Cairo/Pango backend now covers the advanced APIs exercised by this milestone: encoded images,
boolean paths, sweep and composite shaders, color filters, vertex meshes, blur/offset render effects,
perspective graphics-layer transforms, byte-backed fonts, rich spans, bidi queries, and inline
placeholders. Clipper2 performs path clipping after adaptively flattening cubic curves; Cairo
performs compositing and raster effects; Fontconfig/Pango/HarfBuzz performs font registration,
selection, shaping, line layout, span attributes, and glyph masks for brushes and shadows.

The platform host is packaged as the Compose-owned `:compose:ui:ui-sdl2` module. Its KLIB embeds
the small C++ graphics support archive and exposes the native application, window/dialog state,
input, clipboard, URI, `WindowDraggableArea`, and native CPU/GPU interop APIs. GPU native views
render directly into host-owned OpenGL FBOs. The window compositor projects those textures through
the current Compose transform and blends the transparent Cairo UI texture over them without
reading external frames back to the CPU. The module deliberately provides only generic native-view
interop; the WPE WebKit adapter remains app-owned and can be replaced without changing Compose.

GPU interop views accept an antialiased Compose `Path` mask and follow the current Compose
transform, including `rotationX`/`rotationY` perspective, through a subdivided texture mesh. The
mask clears the Cairo frame at the `NativeView`'s draw position, so later Compose siblings remain
above GPU-resident native content without host readback.

External textures are currently composited below the final Cairo UI layer. This gives ordinary
Compose overlays (including controls) correct ordering, but does not yet interleave separate GPU
textures between arbitrary individual Cairo draw commands.

The executable stays a small native launcher; SDL2, Cairo/Pango, Fontconfig, D-Bus, WPE WebKit,
libmpv, OpenGL/EGL, JPEG, WebP, and their required system libraries remain dynamically linked.

## Layout

- `src/linuxMain/kotlin/Main.kt` — WPE WebKit adapter and native application windows
- `src/linuxMain/kotlin/Catalogue.kt` — Navigation 3 component catalogue and interactive demos
- `src/nativeInterop/cinterop/` — app-owned WPE headless/EGL bridge
- `build.gradle.kts` — app module configuration
- `scripts/` — checkout verification, build, and run commands
- `build/bin/compose-wayland` — final executable produced by the wrapper
- `../compose/ui/ui-sdl2/` — prepared source checkout containing the Compose-owned Wayland window
  host, Cairo/Pango backend, cinterops, and embedded native support archive
- `../navigation3/navigation3-ui/` — Navigation 3 UI port with a Linux native target
