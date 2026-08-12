# Compose Native Gradle plugin

`dev.brahmkshatriya.compose` works alongside the official Compose Multiplatform Gradle plugin. It
adds the `desktopNative` Kotlin target group and shared source-set hierarchy. It does not register a
project extension, add dependencies, or choose dependency versions.

- Apply `org.jetbrains.compose` normally so it owns Compose resources and application integration.
- Declare fork artifacts and their versions directly in the source sets that should use them.
- Declare the native Skiko fork explicitly when required.
- Explicit fork dependencies in `desktopNativeMain` replace their matching official Compose or
  Skiko modules only in Linux and Windows configurations.
- Explicit fork Compose dependencies in `commonMain` replace matching transitive JetBrains Compose
  modules in all configurations and matching AndroidX `-android` modules on Android.
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

The plugin has no project-level configuration extension. Its substitution rules only follow
explicitly versioned fork dependencies; the declared dependency remains the source of both scope
and version. See the root README for native-overlay and full-fork examples.
