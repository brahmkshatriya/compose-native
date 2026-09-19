# Compose Native publication contract

The fork is published under `dev.brahmkshatriya` without replacing JetBrains Compose coordinates.
Consumers explicitly choose the fork coordinates and versions in their dependency declarations.

## Version set

| Role | Version |
| --- | --- |
| Fork artifacts | `1.13.0-alpha01` |
| JetBrains Compose upstream | `1.13.0-alpha01` |
| Maven Central Material 3 upstream | `1.13.0-alpha01` |
| Official Skiko (desktop/web/iOS) | `0.152.0-alpha04` |
| Native Skiko fork | `0.151.5` |
| Kotlin | `2.3.20` |

The machine-readable values live in `gradle.properties`. Material 3 is pinned separately because
it has an independent release train. Keep the Gradle plugin's native Skiko fallback synchronized
with `compose.native.skiko.version`.

## Linux x64 coordinates

Native Skiko is published as `dev.brahmkshatriya.skiko:skiko:0.151.5`. JVM desktop, Apple, JS, and
Wasm continue to use the official JetBrains Skiko artifacts. Native platform module metadata is
rewritten during publication so it records that fork coordinate instead of the shared source set's
official Skiko compile coordinate. The Compose target closure is defined once by
`JetBrainsPublication.nativeComponents` and contains:

- lifecycle: `lifecycle-common`, `lifecycle-runtime`, `lifecycle-runtime-compose`,
  `lifecycle-viewmodel`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-savedstate`
- saved state and navigation: `savedstate`, `savedstate-compose`, `navigationevent-compose`
- runtime and UI: `runtime`, `runtime-saveable`, `ui`, `ui-backhandler`, `ui-geometry`,
  `ui-graphics`, `ui-skiko`, `ui-text`, `ui-unit`, `ui-util`, and `desktop-native`
- higher layers: `animation`, `animation-core`, `foundation`, `foundation-layout`,
  `animation-graphics`, `material`, `material-ripple`, `material3`, `components-resources`

Each root Gradle module metadata file advertises only the targets actually published under the
fork namespace. `desktop-native` also receives a standard `desktopNativeMain` metadata fragment,
assembled from the linkdata in its published Linux KLIB, marked as commonized for every supported
desktop target, and bundled with the SDL and native-desktop cinterop metadata required by that
KLIB. This is necessary because native Skiko does not publish a standalone
intermediate-source-set artifact, so Kotlin cannot compile that fragment directly. Android Studio
consumes the published fragment through KGP's normal metadata and cinterop resolvers; no
consumer-side KLIB rewriting or synthetic dependency coordinate is involved.

## Local publication

Run:

```bash
./scripts/publish-linux-native-to-maven-local.sh
```

The script resolves Skiko `0.151.5` from Maven Central, publishes the complete Compose target
closure through `:mpp:publishComposeNativeToMavenLocal`, publishes the directly compilable KMP
roots, and then creates native-only aggregate metadata (including the `desktopNativeMain`
fragment) in Maven Local. It also publishes the `dev.brahmkshatriya.compose` Gradle plugin and its
plugin marker.

To publish the forked Compose Android, JS, and Wasm variants, run:

```bash
./scripts/publish-android-web-to-maven-local.sh
```

This publishes the 18 cross-platform Compose modules through
`:mpp:publishComposeForkPlatformsToMavenLocal`, regenerates their aggregate KMP roots, and
republishes the plugin. Android has no Skiko dependency; JS and Wasm deliberately resolve the
official Skiko `0.152.0-alpha04` artifacts. Native-only support modules such as `desktop-native` and
`components-resources` are not published for these targets.

To publish the iOS device and Apple Silicon simulator variants on macOS, run:

```bash
./scripts/publish-ios-to-maven-local.sh
```

This publishes `iosArm64` and `iosSimulatorArm64` for the full fork dependency closure plus
`ui-uikit`, publishes the corresponding KMP roots, and republishes the consumer plugin. iOS uses
the official `org.jetbrains.skiko:skiko:0.152.0-alpha04`; the Linux/Windows Skiko fork is not
substituted into Apple configurations.

## Consumer plugin

Add Maven Central to plugin resolution in `settings.gradle.kts`:

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
    id("dev.brahmkshatriya.compose") version "1.13.0-alpha01"
}
```

The official plugin provides resource tasks and application integration. Declare the desired fork
artifacts and versions explicitly:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha01"
            )
            implementation(
                "dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha01"
            )
        }
        desktopNativeMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.desktop:desktop-native:1.13.0-alpha01"
            )
        }
    }
}
```

The plugin has no project-level configuration extension and never adds Compose dependencies.
Explicit fork dependencies drive scoped substitution: declarations in `desktopNativeMain` overlay
Linux and Windows, while declarations in `commonMain` select the full fork on every published
platform. When the plugin is applied to a published library, Linux x64, Linux ARM64, and MinGW x64
Gradle module metadata is also repaired after generation: stale `org.jetbrains.skiko:skiko` entries
are rewritten to the native Skiko fork version declared under `desktopNativeMain`, or to the plugin's
compatible fallback when no explicit version is present. This is a producer-side metadata fix, not
an unconditional Skiko substitution in downstream applications. AndroidX Compose substitutions are
restricted to Android and to modules actually published by the fork; JetBrains AndroidX support
substitutions are restricted to desktop native.

The selector recognizes `linux_x64`, `linux_arm64`, and `mingw_x64`. Their matching Skiko artifacts
are available from Maven Central; the matching Compose artifacts must also be published before use.
For the shared `desktopNativeMain` metadata configuration, the plugin sets the native-target attribute
to `linux_x64` as a representative variant so Gradle can resolve native-only Compose roots that do
not publish a target-independent metadata variant. Concrete Linux ARM64 and MinGW compilations still
resolve their own target variants.

## Maven Central publication

The tag workflow `.github/workflows/publish-compose-native-central.yml` builds and merges:

- Android, JS, and Wasm fork artifacts
- iOS arm64 device and iOS arm64 simulator fork artifacts
- Linux x64, Linux arm64, and Windows x64 native artifacts
- aggregate KMP root metadata
- the Gradle plugin implementation and plugin marker

It signs and uploads the complete set as one automatically released Central Portal deployment.
The workflow finishes after Central accepts the upload; validation and automatic publication then
continue asynchronously in Central Portal. The platform build jobs do not receive publication
secrets; only the final merge job can sign and upload the bundle.

Configure these GitHub Actions repository secrets:

- `GRADLE_PROPERTIES`: the publication properties normally stored in
  `~/.gradle/gradle.properties`, including `mavenCentralUsername`, `mavenCentralPassword`,
  `signing.keyId`, and `signing.password`. A stored `signing.secretKeyRingFile` value is replaced
  with the CI key path.
- `GPG_SECRET_KEY_RING_BASE64`: the base64-encoded secret key ring file.

Create and push a version tag that exactly matches both
`jetbrains.publication.version.COMPOSE` and the Gradle plugin version:

```bash
git tag 1.13.0-alpha01
git push origin 1.13.0-alpha01
```

The deployment includes both the implementation artifact
`dev.brahmkshatriya.compose:compose-gradle-plugin:1.13.0-alpha01` and the marker
`dev.brahmkshatriya.compose:dev.brahmkshatriya.compose.gradle.plugin:1.13.0-alpha01`.
Native Skiko `0.151.5` must already be available from Maven Central. Do not reuse a published tag
version: Central releases are immutable.
