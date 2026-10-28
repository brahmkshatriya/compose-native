# Plugin maintenance guide

The entry point, `ComposeNativePlugin.kt`, assembles independent conventions. It deliberately keeps
registration order explicit: Kotlin compatibility and source sets are installed first, application
and metadata conventions next, and dependency/publication rules last. Keep dependency rules lazy and
preserve their `withPlugin`, `afterEvaluate`, and consumer-project evaluation boundaries.

The public Kotlin package remains `dev.brahmkshatriya.compose`. The `desktopNative` DSL,
`composeNativeApplication` properties, plugin ID, task names, task types and default values are
consumer contracts. Splitting an implementation into another file must not rename those classes.

## Where changes belong

| Responsibility | Implementation | Main regression coverage |
| --- | --- | --- |
| Target names, Konan identifiers, architectures and platform membership | `DesktopNativeTargetModel.kt` | `DesktopNativeRunTaskTest`, `DesktopNativeApplicationFunctionalTest` |
| Target DSL, executable creation and run aliases | `DesktopNativeTargets.kt`, `DesktopNativeExecutables.kt` | `DesktopNativeApplicationFunctionalTest` |
| Shared source-set ownership and hierarchy | `DesktopNativeHierarchy.kt` | `DesktopNativeApplicationFunctionalTest`, `ComposeNativeConfigurationsTest` |
| Kotlin model/version compatibility | `KotlinGradleAccess.kt`, `KotlinGradleCompatibility.kt` | Application functional tests and Echo consumer checks |
| Application defaults, resource staging and target-specific linking | `ComposeNativeApplication.kt`, `DesktopNativeResources.kt`, `DesktopNativeLibraries.kt` | `DesktopNativeApplicationFunctionalTest`, `DesktopNativeLibrariesTest` |
| Linux AppDir/AppImage packaging | `LinuxApplication.kt` | `DesktopNativeLibrariesTest`, Linux consumer packaging |
| macOS bundles, dylib relocation, signing and DMG packaging | `MacosApplication.kt` | Application task registration tests; macOS packaging runner |
| Windows distribution, SDL/C++ runtime and ICU staging | `WindowsApplication.kt`, `WindowsRuntime.kt` | `DesktopNativeApplicationFunctionalTest`, `WindowsInstallerPackagingTest` |
| Windows installer tools and source generation | `WindowsInstallers.kt`, `WindowsInstallerSources.kt` | `WindowsInstallerPackagingTest` |
| Dependency coordinate policy and configuration classification | `ComposeNativeDependencyCoordinates.kt`, `ComposeNativeConfigurations.kt`, `ComposeNativeRedirects.kt` | Corresponding unit tests |
| Gradle dependency rules and propagation to consuming projects | `ComposeNativeDependencySubstitution.kt`, `ComposeNativePlatformRouting.kt` | `ComposeNativeDependencyFunctionalTest`, `ComposeNativePlatformRoutingTest` |
| Skiko capabilities, desktop Native version alignment and publication metadata | `ComposeNativeSkiko.kt` | `ComposeNativeSkikoTest`, `ComposeNativeSkikoFunctionalTest`, `ComposeNativePublicationFunctionalTest` |
| IDE resolver registration and resolution configurations | `ComposeNativeIdeDependencyResolution.kt` | `ComposeNativeIdeFunctionalTest` |
| IDE KLIB extraction, caching and source attachment | `ComposeNativeIdeMetadata.kt` | `ComposeNativeIdeMetadataTest` |
| Compiler common-metadata repair | `ComposeNativeMetadataCompilation.kt` | Native compile functional tests, `ComposeNativeConfigurationsTest` |
| Upstream version mapping | `ComposeNativeUpstreamVersions.kt` and the generated properties resource | Coordinate/routing unit tests |

`NativePackagingTools.kt` contains the small file/process helpers shared by packaging platforms.
Platform-only installer, archive, Mach-O, and desktop-entry details stay beside their consumers.
Avoid a general utility collection or a second target registry.

## Boundaries to preserve

- Coordinate/classification functions are pure policy. Gradle configuration callbacks apply that
  policy; platform packaging code does not decide dependency substitutions.
- `DesktopNativeTargetModel.kt` owns the five-target registry. Derive target groupings from it rather
  than copying architecture/name tables into another convention.
- Reflective target and binary access belongs in `KotlinGradleAccess.kt`. It isolates KGP JVM
  signature differences without forcing the consumer's Kotlin plugin version. Task compiler-option
  access remains beside common-metadata repair, where its optional capabilities are handled.
- Each shared source directory has one source-set owner. Import shared IDE KLIB fragments with
  their original identities and manifests. Do not fake extra platform publications or turn missing
  platform artifacts into successful resolutions.
- Keep Gradle providers and file collections attached to their tasks. Avoid resolving dependency
  graphs, downloading runtimes, or executing host tools during plugin application.
- Linux target library paths must not enter Windows/macOS binaries or a different architecture.
  Windows runtime staging and macOS dylib rewriting have different requirements and should remain
  separate implementations.

## Verification

Run the plugin's complete tests and Gradle task validation:

```sh
./gradlew -p gradle-plugin test validatePlugins
```

TestKit injects KGP implementations onto the fixture plugin classpath. The
`functionalTestKotlinVersion` property sets both that implementation and the version requested by
fixtures, preventing a test requesting Kotlin 2.4 from silently running Kotlin 2.3. The default is
2.4.21, matching the current consumer and published KLIBs. The plugin still compiles against the
2.3.20 KGP API. Exercise the older application's model/resource conventions separately:

```sh
./gradlew -p gradle-plugin -PfunctionalTestKotlinVersion=2.3.20 test \
  --tests '*DesktopNativeApplicationFunctionalTest'
```

After changes to the DSL, task types, linking, or resource staging, publish to Maven Local and
exercise a real consumer. Linux can verify Linux links and AppDir contents; it cannot validate a
macOS DMG or Windows installer. Those require their platform tools/runners. Compare public JVM
signatures when moving public task or extension classes between files.
