#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
"$task_app_root/scripts/bootstrap.sh"

task_java_home="$(find_task_jdk)"
task_worker_count="$(nproc)"
task_wpe_prefix="${KTNATIVE_WPE_PREFIX:-/usr}"

case "$(uname -m)" in
    x86_64|amd64)
        task_kotlin_target="LinuxX64"
        task_target_dir="linuxX64"
        task_source_set="linuxX64Main"
        ;;
    aarch64|arm64)
        task_kotlin_target="LinuxArm64"
        task_target_dir="linuxArm64"
        task_source_set="linuxArm64Main"
        ;;
    *)
        echo "Unsupported Linux architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

if [[ ! -f "$task_wpe_prefix/include/wpe-webkit-2.0/wpe/webkit.h" ]]; then
    echo "WPE WebKit development files were not found under $task_wpe_prefix." >&2
    echo "Install WPE WebKit 2.44 or newer, or set KTNATIVE_WPE_PREFIX." >&2
    exit 1
fi
if [[ ! -f "$task_wpe_prefix/include/wpe-webkit-2.0/wpe-platform/wpe/headless/wpe-headless.h" ]]; then
    echo "The WPE headless platform headers were not found under $task_wpe_prefix." >&2
    echo "Install WPE WebKit 2.44 or newer, or set KTNATIVE_WPE_PREFIX." >&2
    exit 1
fi
if ! pkg-config --exists mpv; then
    echo "libmpv development files were not found." >&2
    echo "Install libmpv (including headers and pkg-config metadata)." >&2
    exit 1
fi

if [[ "${KTNATIVE_RELEASE:-0}" == "1" ]]; then
    task_link_target="linkReleaseExecutable$task_kotlin_target"
    task_executable_kind=releaseExecutable
    echo "Building release Kotlin/Native Wayland executable"
else
    task_link_target="linkDebugExecutable$task_kotlin_target"
    task_executable_kind=debugExecutable
    echo "Building debug Kotlin/Native Wayland executable"
fi
(
    cd "$task_compose_root"
    JAVA_HOME="$task_java_home" \
    ANDROIDX_JDK21="$task_java_home" \
    KTNATIVE_WPE_PREFIX="$task_wpe_prefix" \
    OUT_DIR="$task_out_root" \
    PROJECT_PREFIX="$task_projects" \
    ./gradlew \
        ":demo:assemble${task_kotlin_target}MainResources" \
        ":demo:$task_link_target" \
        -Pandroidx.enabled.kmp.target.platforms=-desktop,-mac,-windows,-android_native,-js,-wasm,+linux \
        --no-configuration-cache \
        --no-configure-on-demand \
        --project-cache-dir="$task_project_cache" \
        --parallel \
        --max-workers="$task_worker_count"
)

task_executable="$task_out_root/compose-multiplatform-core/demo/build/bin/$task_target_dir/$task_executable_kind/compose-wayland.kexe"
task_resources="$task_out_root/compose-multiplatform-core/demo/build/generated/compose/resourceGenerator/assembledResources/$task_source_set"
install -Dm755 "$task_executable" "$task_root/build/bin/compose-wayland"
rm -rf "$task_root/build/bin/compose-resources"
mkdir -p "$task_root/build/bin/compose-resources"
cp -a "$task_resources/." "$task_root/build/bin/compose-resources/"
if [[ "${KTNATIVE_STRIP:-0}" == "1" ]]; then
    if command -v strip >/dev/null 2>&1; then
        strip --strip-unneeded "$task_root/build/bin/compose-wayland"
    else
        echo "KTNATIVE_STRIP=1 was requested, but strip is unavailable." >&2
        exit 1
    fi
fi
echo "Built $task_root/build/bin/compose-wayland with resources in $task_root/build/bin/compose-resources"
