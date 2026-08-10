# Compose Native Windows Demo

Build and package the release executable from the repository root:

```shell
./gradlew :windows-native-demo:packageWindowsRelease
```

The task writes `compose-windows-demo.exe`, generated Compose resources, `SDL3.dll`, `icudtl.dat`,
`WebView2Loader.dll`, `libmpv-2.dll`, the MinGW C++ runtime DLLs, and the SDL license to
`out/compose-multiplatform-core/windows-native-demo/build/windows-package/`. Copy that directory
to a Windows x64 machine and run the executable there.

This target compiles the same catalogue Kotlin sources and generated resources as `:demo`; only the
launcher and native adapters are Windows-specific. The launcher selects OpenGL by default because
the shared Native Views page embeds OpenGL surfaces, while an explicit `SKIKO_RENDER_API` setting is
still respected. The browser page uses WebView2 and the video page uses the bundled libmpv runtime.
