# Clipper2 source provenance

These files come from the official
[AngusJohnson/Clipper2](https://github.com/AngusJohnson/Clipper2) repository at commit
`f9c5eb6e14a59f6f5d65fbfb3564519a561cf4fd` (Clipper2 2.0.1):

- `CPP/Clipper2Lib/include/clipper2/clipper.core.h`
- `CPP/Clipper2Lib/include/clipper2/clipper.engine.h`
- `CPP/Clipper2Lib/include/clipper2/clipper.version.h`
- `CPP/Clipper2Lib/src/clipper.engine.cpp`
- `LICENSE`

The engine source is locally adapted to avoid importing the unused high-level `clipper.h` header:
the small `Path2ContainsPath1` helper it needs is included directly in `clipper.engine.cpp`. The
algorithm remains under the bundled Boost Software License 1.0.
