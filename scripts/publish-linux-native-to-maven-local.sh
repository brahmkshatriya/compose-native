#!/usr/bin/env bash
set -euo pipefail

compose_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="9999.0.0-SNAPSHOT"
maven_repository="${MAVEN_LOCAL_REPOSITORY:-$HOME/.m2/repository}"
skiko_root="${SKIKO_ROOT:-$(cd "$compose_root/../.." && pwd)/skiko-linux-native}"

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
    echo "Publishing Linux x64 Skiko from $skiko_root"
    "$skiko_root/gradlew" \
        -p "$skiko_root" \
        --no-configuration-cache \
        -Pskiko.native.enabled=true \
        -Pskiko.native.linux.enabled=true \
        :skiko:publishLinuxX64PublicationToMavenLocal
else
    skiko_klib="$maven_repository/org/jetbrains/skiko/skiko-linuxx64/0.0.1-linux-native-SNAPSHOT/skiko-linuxx64-0.0.1-linux-native-SNAPSHOT.klib"
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
    :compose:runtime:runtime:publishLinuxX64PublicationToMavenLocal
    :compose:runtime:runtime-saveable:publishLinuxX64PublicationToMavenLocal
    :navigationevent:navigationevent-compose:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-util:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-unit:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-geometry:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-graphics:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-text:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-backhandler:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-skiko:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui:publishLinuxX64PublicationToMavenLocal
    :compose:animation:animation-core:publishLinuxX64PublicationToMavenLocal
    :compose:animation:animation:publishLinuxX64PublicationToMavenLocal
    :compose:foundation:foundation-layout:publishLinuxX64PublicationToMavenLocal
    :compose:foundation:foundation:publishLinuxX64PublicationToMavenLocal
    :compose:material:material-ripple:publishLinuxX64PublicationToMavenLocal
    :compose:material3:material3:publishLinuxX64PublicationToMavenLocal
    :compose:ui:ui-sdl2:publishLinuxX64PublicationToMavenLocal
    :compose:components:components-resources:publishLinuxX64PublicationToMavenLocal
)

echo "Publishing Compose Linux x64 KLIBs as $version"
"$compose_root/gradlew" \
    -p "$compose_root" \
    --no-configuration-cache \
    "${publish_tasks[@]}"

"$compose_root/scripts/write-linux-native-root-metadata.py" \
    --repository "$maven_repository" \
    --version "$version"

echo "Linux-native Compose is available in $maven_repository"
