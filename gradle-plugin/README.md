# Compose Native Gradle plugin

`dev.brahmkshatriya.compose` works alongside the official Compose Multiplatform Gradle plugin. It
adds the `desktopNative` Kotlin target group and shared source-set hierarchy. It does not add
dependencies or choose dependency versions.

- Apply `org.jetbrains.compose` normally so it owns Compose resources and application integration.
- Declare fork artifacts and their versions directly in the source sets that should use them.
- Declare the native Skiko fork explicitly when required.
- Explicit fork dependencies in `desktopNativeMain` replace their matching official Compose or
  Skiko modules only in Linux and Windows configurations.
- Explicit fork Compose dependencies in `commonMain` replace matching transitive JetBrains Compose
  modules in all configurations and matching AndroidX `-android` modules on Android. The plugin
  also carries those substitutions into a separate Android application project that directly
  depends on the multiplatform project. Android configurations target the fork's published
  `-android` artifact directly instead of relying on root-module variant selection.
- The plugin creates `desktopNativeMain` and connects it to configured Linux and Windows native
  targets so native desktop dependencies can be declared once.
- Add all three desktop-native targets without repeating target blocks:

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

Dependency substitution remains driven only by explicitly versioned fork dependencies; the
declared dependency remains the source of both scope and version. The `composeNativeApplication`
extension configures application packaging only. See the root README for native-overlay and full-fork
examples.

## Native application conventions

When a desktop Native executable is present, the plugin also treats the conventional JVM desktop
layout as shared desktop-application input:

- `src/main/kotlin` is added to `desktopNativeMain`.
- `src/main/composeResources` is registered as a `desktopNativeMain` Compose resource directory.
- Compose resources are copied next to debug and release Native executables.
- Linux x64/arm64 executables get architecture-specific AppDir and AppImage tasks.
- Windows x64 executables get a self-contained distribution directory and zip task.

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

Linux AppDir tasks strip the packaged executable by default, bundle SDL 3, copy Compose resources,
and generate `AppRun` plus the desktop entry. Windows packaging downloads the matching SDL 3
runtime, includes the Kotlin/Native MinGW runtime DLLs and Skiko ICU data, and copies Compose
resources beside the executable. Additional platform runtimes can be supplied with
`linuxX64RuntimeFiles`, `linuxArm64RuntimeFiles`, or `windowsX64RuntimeFiles`.
`stripLinuxExecutable` and `bundleSdl` can be changed in `composeNativeApplication` when needed.
