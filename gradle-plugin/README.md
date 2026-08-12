# Compose Native Gradle plugin

`dev.brahmkshatriya.compose` works alongside the official Compose Multiplatform Gradle plugin. It
adds only the `desktopNative` Kotlin target group and shared source-set hierarchy. It does not
register a project extension or add, replace, or select dependencies.

- Apply `org.jetbrains.compose` normally so it owns Compose resources and application integration.
- Declare fork artifacts and their versions directly in the source sets that should use them.
- Declare the native Skiko fork explicitly when required.
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

The plugin has no project-level configuration extension. Consumers that need Compose dependency
substitution configure Gradle's standard `resolutionStrategy.dependencySubstitution` API directly;
see the root README.
