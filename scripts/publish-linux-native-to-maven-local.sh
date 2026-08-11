#!/usr/bin/env bash
set -euo pipefail

compose_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="9999.0.0-SNAPSHOT"
maven_repository="${MAVEN_LOCAL_REPOSITORY:-$HOME/.m2/repository}"
skiko_root="${SKIKO_ROOT:-$compose_root/../skiko-native}"

case "${KTNATIVE_LINUX_ARCH:-$(uname -m)}" in
    x64|x86_64|amd64)
        linux_arch="x64"
        kotlin_target="LinuxX64"
        platform_module_suffix="linuxx64"
        ;;
    arm64|aarch64)
        linux_arch="arm64"
        kotlin_target="LinuxArm64"
        platform_module_suffix="linuxarm64"
        ;;
    *)
        echo "Unsupported Linux architecture: ${KTNATIVE_LINUX_ARCH:-$(uname -m)}" >&2
        echo "Use KTNATIVE_LINUX_ARCH=x64 or KTNATIVE_LINUX_ARCH=arm64." >&2
        exit 1
        ;;
esac

prepare_linux_arm64_cross_tools() {
    [[ "$linux_arch" == "arm64" ]] || return
    case "$(uname -m)" in
        aarch64|arm64) return ;;
    esac

    local toolchain="${SKIKO_ARM64_TOOLCHAIN:-/opt/arm-gnu-toolchain}"
    local compiler="$toolchain/bin/aarch64-none-linux-gnu-g++"
    local archiver="$toolchain/bin/aarch64-none-linux-gnu-ar"
    local sysroot="$toolchain/aarch64-none-linux-gnu/libc"
    if [[ ! -x "$compiler" || ! -x "$archiver" || ! -d "$sysroot" ]]; then
        echo "Skiko Linux arm64 cross builds require the Arm GNU 10.3 toolchain." >&2
        echo "Expected it at $toolchain (override with SKIKO_ARM64_TOOLCHAIN)." >&2
        echo "Build on an arm64 host instead, or install the toolchain used by skiko/docker/linux-amd64." >&2
        exit 1
    fi
    arm64_cross_tool_aliases="$(mktemp -d)"
    trap 'rm -rf "${arm64_cross_tool_aliases:-}"' EXIT
    ln -s "$compiler" "$arm64_cross_tool_aliases/aarch64-linux-gnu-g++"
    ln -s "$toolchain/bin/aarch64-none-linux-gnu-gcc" \
        "$arm64_cross_tool_aliases/aarch64-linux-gnu-gcc"
    ln -s "$archiver" "$arm64_cross_tool_aliases/aarch64-linux-gnu-ar"
    export PATH="$arm64_cross_tool_aliases:$PATH"
}

prepare_linux_arm64_cross_tools

find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi
    if command -v javac >/dev/null 2>&1; then
        dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
        return
    fi
    local candidate
    for candidate in "$HOME"/.jdks/*; do
        if [[ -x "$candidate/bin/java" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "JDK 21 was not found. Set JAVA_HOME." >&2
    return 1
}

export JAVA_HOME="$(find_jdk)"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Android/sdk"; do
        if [[ -d "$candidate" ]]; then
            export ANDROID_HOME="$candidate"
            break
        fi
    done
fi
if [[ -n "${ANDROID_HOME:-}" ]]; then
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
fi

if [[ -x "$skiko_root/gradlew" ]]; then
    echo "Publishing Linux $linux_arch Skiko from $skiko_root"
    "$skiko_root/gradlew" \
        -p "$skiko_root" \
        --no-configuration-cache \
        -Pskiko.native.enabled=true \
        -Pskiko.native.linux.enabled=true \
        :skiko:publish${kotlin_target}PublicationToMavenLocal
else
    skiko_klib="$maven_repository/org/jetbrains/skiko/skiko-$platform_module_suffix/0.0.1-linux-native-SNAPSHOT/skiko-$platform_module_suffix-0.0.1-linux-native-SNAPSHOT.klib"
    if [[ ! -f "$skiko_klib" ]]; then
        echo "Skiko checkout not found at $skiko_root and $skiko_klib is missing." >&2
        echo "Set SKIKO_ROOT to the Linux-native Skiko checkout." >&2
        exit 1
    fi
    echo "Using existing local Skiko publication at $skiko_klib"
fi

"$compose_root/scripts/write-linux-native-root-metadata.py" \
    --repository "$maven_repository" \
    --version "0.0.1-linux-native-SNAPSHOT"

publish_tasks=(
    :compose:runtime:runtime:publish${kotlin_target}PublicationToMavenLocal
    :compose:runtime:runtime-saveable:publish${kotlin_target}PublicationToMavenLocal
    :navigationevent:navigationevent-compose:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-util:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-unit:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-geometry:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-graphics:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-text:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-backhandler:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-skiko:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui:publish${kotlin_target}PublicationToMavenLocal
    :compose:animation:animation-core:publish${kotlin_target}PublicationToMavenLocal
    :compose:animation:animation:publish${kotlin_target}PublicationToMavenLocal
    :compose:foundation:foundation-layout:publish${kotlin_target}PublicationToMavenLocal
    :compose:foundation:foundation:publish${kotlin_target}PublicationToMavenLocal
    :compose:material:material-ripple:publish${kotlin_target}PublicationToMavenLocal
    :compose:material3:material3:publish${kotlin_target}PublicationToMavenLocal
    :compose:ui:ui-sdl3:publish${kotlin_target}PublicationToMavenLocal
    :compose:components:components-resources:publish${kotlin_target}PublicationToMavenLocal
)

echo "Publishing Compose Linux $linux_arch KLIBs as $version"
"$compose_root/gradlew" \
    -p "$compose_root" \
    --no-configuration-cache \
    "${publish_tasks[@]}"

"$compose_root/scripts/write-linux-native-root-metadata.py" \
    --repository "$maven_repository" \
    --version "$version"

echo "Linux-native Compose is available in $maven_repository"
