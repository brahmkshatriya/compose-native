# Text Morph Demo

A standalone Kotlin/Native Compose application that morphs **Hi** into **Hello Compose**.

Build the optimized release executable from the repository root:

```bash
./gradlew :text-morph-demo:linkReleaseExecutableLinuxX64
```

The release executable is generated as `text-morph-demo.kexe`. Run `strip --strip-unneeded` on a
copy intended for distribution.

Build a stripped, Zstandard-compressed AppImage with:

```bash
./text-morph-demo/scripts/build-appimage.sh
```

This thin AppImage keeps SDL and the Linux graphics stack as host dependencies. Set
`KTNATIVE_SKIP_BUILD=1` to repackage an existing release executable without relinking it.
