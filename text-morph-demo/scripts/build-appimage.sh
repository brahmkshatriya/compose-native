#!/usr/bin/env bash
set -euo pipefail

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd "$script_root/.." && pwd)"
compose_root="$(cd "$app_root/.." && pwd)"

source "$compose_root/demo/scripts/common.sh"

java_home="$(find_task_jdk)"
worker_count="$(nproc)"
out_root="$app_root/build/androidx-out"
project_cache="$app_root/build/gradle-project-cache"

case "$(uname -m)" in
    x86_64|amd64)
        kotlin_target="LinuxX64"
        target_dir="linuxX64"
        appimage_arch="x86_64"
        ;;
    aarch64|arm64)
        kotlin_target="LinuxArm64"
        target_dir="linuxArm64"
        appimage_arch="aarch64"
        ;;
    *)
        echo "Unsupported Linux architecture: $(uname -m)" >&2
        exit 1
        ;;
esac
native_executable="$out_root/compose-multiplatform-core/text-morph-demo/build/bin/$target_dir/releaseExecutable/text-morph-demo.kexe"
app_dir="$app_root/build/appimage/TextMorphDemo.AppDir"
output="$app_root/build/bin/text-morph-demo-$appimage_arch.AppImage"

if [[ "${KTNATIVE_SKIP_BUILD:-0}" != "1" ]]; then
    (
        cd "$compose_root"
        JAVA_HOME="$java_home" \
        ANDROIDX_JDK21="$java_home" \
        OUT_DIR="$out_root" \
        PROJECT_PREFIX=":text-morph-demo,$task_projects" \
        ./gradlew \
            ":text-morph-demo:linkReleaseExecutable$kotlin_target" \
            -Pkotlin.native.binary.smallBinary=true \
            -Pandroidx.enabled.kmp.target.platforms=-desktop,-mac,-windows,-android_native,-js,-wasm,+linux \
            --no-configuration-cache \
            --no-configure-on-demand \
            --project-cache-dir="$project_cache" \
            --parallel \
            --max-workers="$worker_count"
    )
fi

if [[ ! -x "$native_executable" ]]; then
    echo "Release executable was not found at $native_executable." >&2
    exit 1
fi
if ! command -v strip >/dev/null 2>&1; then
    echo "strip is required to produce the distribution binary." >&2
    exit 1
fi
if ! command -v appimagetool >/dev/null 2>&1; then
    echo "appimagetool is required to package the AppImage." >&2
    exit 1
fi

rm -rf "$app_dir"
install -Dm755 "$native_executable" "$app_dir/usr/bin/text-morph-demo"
strip --strip-unneeded "$app_dir/usr/bin/text-morph-demo"
install -Dm755 "$app_root/appimage/AppRun" "$app_dir/AppRun"
install -Dm644 "$app_root/appimage/text-morph-demo.desktop" "$app_dir/text-morph-demo.desktop"
install -Dm644 "$app_root/appimage/text-morph-demo.svg" "$app_dir/text-morph-demo.svg"
ln -s text-morph-demo.svg "$app_dir/.DirIcon"

mkdir -p "$(dirname "$output")"
ARCH="$appimage_arch" appimagetool \
    --comp zstd \
    --mksquashfs-opt=-Xcompression-level \
    --mksquashfs-opt=22 \
    "$app_dir" \
    "$output"
echo "Built $output"
