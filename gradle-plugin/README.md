# Compose Native Gradle plugin

`dev.brahmkshatriya.compose` works alongside the official Compose Multiplatform Gradle plugin. It
adds the `desktopNative` Kotlin target group and shared source-set hierarchy. It does not add
Compose dependencies. Explicit fork declarations choose Compose versions; desktop Native Skiko
uses the compatible version for this plugin release (currently `0.156.1`).

- Apply `org.jetbrains.compose` normally so it owns Compose resources and application integration.
- Declare fork artifacts and their versions directly in the source sets that should use them.
- Desktop Native configurations align transitive Skiko dependencies to the compatible fork release,
  including older fork versions. An explicit `dev.brahmkshatriya.skiko:skiko` declaration in
  `desktopNativeMain` overrides that version. JVM, iOS, Android, and Wasm keep upstream Skiko selection.
- When a library is published, the plugin rewrites official or stale fork `skiko` dependencies in
  Linux, Windows, and macOS Gradle module metadata to the selected native fork version.
- Explicit fork dependencies in `desktopNativeMain` replace their matching official Compose or
  Skiko modules only in desktop Native configurations.
- Explicit fork Compose dependencies in `commonMain` replace matching transitive JetBrains Compose
  modules in all configurations and matching AndroidX `-android` modules on Android. The plugin
  also carries those substitutions into separate application projects that directly depend on the
  multiplatform project. Android application configurations target the fork's published `-android`
  artifact directly instead of relying on root-module variant selection.
- Automatic transitive fork selection outside desktop Native is limited to the published
  Foundation and Material3 root modules. Other Compose modules, including UI, use their official
  JetBrains artifacts on Android, iOS, JVM, JS, and Wasm.
- The plugin creates `desktopNativeMain` and connects it to configured Linux, Windows, and macOS
  native targets so native desktop dependencies can be declared once. Its shared metadata resolver uses
  `linux_x64` as the representative native variant when a native-only dependency exposes separate
  Linux x64, Linux ARM64, and MinGW variants, avoiding ambiguous variant selection.
- Platform folders have real shared source sets: `linuxMain` inherits `linuxWindowsMain`, and
  `macosMain` inherits both `desktopNativeMain` and `appleMain`. Declared Apple targets also inherit
  `appleMain`. The corresponding test source sets follow the same hierarchy. Each platform folder
  has one source-set owner rather than being registered separately on every architecture, so IDE
  navigation and `expect`/`actual` resolution see each declaration once.
- `skiaTargetMain` is a real shared source set inherited by `desktopNativeMain`, configured iOS
  targets (`iosArm64Main`, `iosSimulatorArm64Main`, and `iosX64Main`), and `wasmJsMain`. Android
  source sets are excluded. Its `src/skiaTargetMain/kotlin` directory has one IDE owner. Use it for
  shared Skia APIs; APIs defined only by platform actuals, such as `ClipEntry.withPlainText`,
  belong in their platform source sets.
- IDE imports unpack published shared Kotlin fragments and nested CInterop metadata into separate
  KLIB dependencies. This exposes native window APIs and SDL bindings in `desktopNativeMain` while
  retaining each library’s published target information. OS-specific fragments stay in their target
  source sets, and missing platform publications remain visible as unresolved dependencies.
- Add all five desktop-native targets (Linux x64, Linux ARM64, MinGW x64, macOS x64, and macOS
  ARM64) without repeating target blocks:

```kotlin
kotlin {
    desktopNative()
}
```

Applications can configure one entry point for all declared targets:

```kotlin
kotlin {
    desktopNative {
        binaries.executable {
            entryPoint = "com.example.main"
        }
    }
}
```

Library modules use plain `desktopNative()`, which creates no executable binaries.

Compose dependency substitution is driven by explicitly versioned fork dependencies. Skiko alignment
is scoped to desktop Native root and platform modules; it excludes AWT, iOS, and Web artifacts.
Native publication metadata uses the same selected version. The `composeNativeApplication` extension
configures native application linking and packaging. See the root README for native-overlay and
full-fork examples.

## Native application conventions

When a desktop Native executable is present, the plugin also treats the conventional JVM desktop
layout as shared desktop-application input:

- `src/main/kotlin` is added to `desktopNativeMain`.
- `src/main/composeResources` is registered as a `desktopNativeMain` Compose resource directory.
- Compose resources are copied next to debug and release Native executables.
- `runDebugExecutableDesktop` runs the debug executable for the current host OS and architecture.
- Linux x64/arm64 executables get architecture-specific AppDir and AppImage tasks.
- Windows x64 executables get a self-contained distribution directory plus zip, MSI, and installer
  EXE tasks.
- Explicit macOS x64/arm64 executables get architecture-specific `.app` bundle and `.dmg` tasks.

Native application metadata can be customized without defining packaging tasks in the application:

```kotlin
composeNativeApplication {
    applicationName.set("Example")
    packageName.set("com.example.app")
    executableName.set("example")
    description.set("Example application")
    categories.set(listOf("Utility"))
    iconFile.set(rootProject.layout.projectDirectory.file("icon.png"))
}
```

Executable linker defaults follow the **target**, independently of the build host: Linux receives
EGL and architecture-specific library paths, macOS receives SDL and Homebrew search paths, and
Windows uses the plugin's downloaded SDL import library. Applications do not need OS checks or
`linkerOpts` for these dependencies. System development libraries must still be installed.

For custom installations or cross-compilation, configure `linuxX64LibraryDirectories`,
`linuxArm64LibraryDirectories`, `macosX64LibraryDirectories`, or `macosArm64LibraryDirectories`.
Use `.from(...)` to add paths or `.setFrom(...)` to replace the defaults. CI can instead set
`LINUX_X64_NATIVE_LIBRARY_DIRS`, `LINUX_ARM64_NATIVE_LIBRARY_DIRS`,
`MACOS_X64_NATIVE_LIBRARY_DIRS`, or `MACOS_ARM64_NATIVE_LIBRARY_DIRS` (paths separated by the
host's path separator). `SDL3_LIBRARY_DIR` and `LINUX_NATIVE_LIBRARY_DIRS` remain host-target
fallbacks; they never affect a different architecture. Linux defaults include target multiarch
paths; macOS defaults include the corresponding Intel or Apple Silicon Homebrew paths.

Linux packaging finds SDL in those same directories and checks its ELF architecture before
bundling it as `libSDL3.so.0`, including when cross-compiling ARM64 from x64. Explicit runtime
files take precedence. Existing `LINUX_X64_RUNTIME_DIR` / `LINUX_ARM64_RUNTIME_DIR` overrides
are consumed directly by the plugin. Cross-packaging uses the target's GNU `strip` or `llvm-strip`,
never the host-only GNU tool.

Linux AppDir tasks strip the packaged executable by default, bundle SDL 3, copy Compose resources,
and generate `AppRun` plus the desktop entry. Windows packaging downloads the matching SDL 3
runtime, includes the Kotlin/Native MinGW runtime DLLs and Skiko ICU data, and copies Compose
resources beside the executable. `packageWindowsX64ReleaseMsi` uses WiX 4+ (`wix`) or WiX 3
(`candle` + `light`), while `packageWindowsX64ReleaseInstallerExe` uses NSIS (`makensis`). Their
tool paths can be overridden with `windowsWixExecutable` and `windowsNsisExecutable`, or with
`COMPOSE_WINDOWS_WIX` and `COMPOSE_WINDOWS_NSIS`. Both installers consume the same staged
distribution as `packageWindowsX64ReleaseZip`. macOS packaging places the executable in `Contents/MacOS`,
Compose resources in `Contents/Resources`, runtime dylibs in `Contents/Frameworks`, rewrites
Mach-O load paths, and ad-hoc signs the `.app` before creating the DMG. Additional platform
runtimes can be supplied with `linuxX64RuntimeFiles`, `linuxArm64RuntimeFiles`,
`macosX64RuntimeFiles`, `macosArm64RuntimeFiles`, or `windowsX64RuntimeFiles`.
`stripLinuxExecutable` and `bundleSdl` can be changed in `composeNativeApplication` when needed.
Developer ID signing and notarization remain the application/release pipeline's responsibility.

## Maintaining the plugin

See [the maintenance guide](ARCHITECTURE.md) for the responsibility map, compatibility boundaries,
and regression commands. The entry point assembles conventions; dependency policy, IDE metadata,
and each platform's packaging are maintained independently.
