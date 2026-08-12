# Compose Native publication contract

The fork is published under `dev.brahmkshatriya` without replacing JetBrains Compose coordinates.
Consumers explicitly choose the fork coordinates and versions in their dependency declarations.

## Version set

| Role | Version |
| --- | --- |
| Fork artifacts | `1.12.10-alpha02` |
| JetBrains Compose upstream | `1.12.0-rc01` |
| Maven Central Material 3 upstream | `1.12.0-alpha03` |
| Official Skiko (desktop/web) | `0.150.1` |
| Native Skiko fork | `0.151.3` |
| Kotlin | `2.3.20` |

The machine-readable values live in `gradle.properties`. Material 3 is pinned separately because
it has an independent release train.

## Linux x64 coordinates

Native Skiko is published as `dev.brahmkshatriya.skiko:skiko:0.151.3`. JVM desktop, Apple, JS, and
Wasm continue to use the official JetBrains Skiko artifacts. The Compose target closure is defined
once by `JetBrainsPublication.nativeComponents` and contains:

- lifecycle: `lifecycle-common`, `lifecycle-runtime`, `lifecycle-runtime-compose`,
  `lifecycle-viewmodel`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-savedstate`
- saved state and navigation: `savedstate`, `savedstate-compose`, `navigationevent-compose`
- runtime and UI: `runtime`, `runtime-saveable`, `ui`, `ui-backhandler`, `ui-geometry`,
  `ui-graphics`, `ui-skiko`, `ui-text`, `ui-unit`, `ui-util`, and `desktop-native`
- higher layers: `animation`, `animation-core`, `foundation`, `foundation-layout`,
  `animation-graphics`, `material`, `material-ripple`, `material3`, `components-resources`

Each root Gradle module metadata file advertises only the targets actually published under the
fork namespace.

## Local publication

Run:

```bash
./scripts/publish-linux-native-to-maven-local.sh
```

The script resolves Skiko `0.151.3` from Maven Central, publishes the complete Compose target
closure through `:mpp:publishComposeNativeToMavenLocal`, and then creates native-only KMP root
metadata in Maven Local. It also publishes the `dev.brahmkshatriya.compose` Gradle plugin and its
plugin marker.

To publish the forked Compose Android, JS, and Wasm variants, run:

```bash
./scripts/publish-android-web-to-maven-local.sh
```

This publishes the 18 cross-platform Compose modules through
`:mpp:publishComposeForkPlatformsToMavenLocal`, regenerates their aggregate KMP roots, and
republishes the plugin. Android has no Skiko dependency; JS and Wasm deliberately resolve the
official Skiko `0.150.1` artifacts. Native-only support modules such as `desktop-native` and
`components-resources` are not published for these targets.

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
    id("org.jetbrains.compose") version "1.12.0-rc01"
    id("dev.brahmkshatriya.compose") version "1.12.10-alpha02"
}
```

The official plugin provides resource tasks and application integration. Declare the desired fork
artifacts and versions explicitly:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.foundation:foundation:1.12.10-alpha02"
            )
            implementation(
                "dev.brahmkshatriya.compose.material3:material3:1.12.10-alpha02"
            )
        }
        desktopNativeMain.dependencies {
            implementation(
                "dev.brahmkshatriya.compose.desktop:desktop-native:1.12.10-alpha02"
            )
        }
    }
}
```

The plugin has no project-level configuration extension and performs no dependency substitution. It
never adds Compose dependencies or chooses Compose or Skiko versions. Consumers that need to replace
transitive JetBrains Compose or AndroidX modules configure Gradle's standard dependency-substitution
API directly, as documented in the root README.

The selector recognizes `linux_x64`, `linux_arm64`, and `mingw_x64`. Their matching Skiko artifacts
are available from Maven Central; the matching Compose artifacts must also be published before use.

## Maven Central publication

The tag workflow `.github/workflows/publish-compose-native-central.yml` builds and merges:

- Android, JS, and Wasm fork artifacts
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
git tag 1.12.10-alpha02
git push origin 1.12.10-alpha02
```

The deployment includes both the implementation artifact
`dev.brahmkshatriya.compose:compose-gradle-plugin:1.12.10-alpha02` and the marker
`dev.brahmkshatriya.compose:dev.brahmkshatriya.compose.gradle.plugin:1.12.10-alpha02`.
Native Skiko `0.151.3` must already be available from Maven Central. Do not reuse a published tag
version: Central releases are immutable.
