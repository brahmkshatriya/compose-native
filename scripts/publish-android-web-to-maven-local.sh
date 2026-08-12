#!/usr/bin/env bash
set -euo pipefail

compose_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

read_compose_property() {
    local property_name="$1"
    sed -n "s/^${property_name}=//p" "$compose_root/gradle.properties" | tail -n 1
}

version="${1:-${COMPOSE_NATIVE_VERSION:-$(read_compose_property 'jetbrains.publication.version.COMPOSE')}}"
group_prefix="${COMPOSE_NATIVE_GROUP_PREFIX:-$(read_compose_property 'jetbrains.publication.groupPrefix')}"
maven_repository="${MAVEN_LOCAL_REPOSITORY:-$HOME/.m2/repository}"

if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid Maven version: $version" >&2
    exit 1
fi
if [[ ! "$group_prefix" =~ ^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$ ]]; then
    echo "Invalid Maven group prefix: $group_prefix" >&2
    exit 1
fi

find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME"
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

echo "Publishing forked Compose Android, JS, and Wasm targets as $group_prefix:*:$version"
"$compose_root/gradlew" \
    -p "$compose_root" \
    --no-configuration-cache \
    "-Dmaven.repo.local=$maven_repository" \
    -Pcompose.platforms=Android,Js,WasmJs \
    "-Pjetbrains.publication.groupPrefix=$group_prefix" \
    "-Pjetbrains.publication.version.COMPOSE=$version" \
    "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$version" \
    :mpp:publishComposeForkPlatformsToMavenLocal

"$compose_root/scripts/write-linux-native-root-metadata.py" \
    --repository "$maven_repository" \
    --version "$version" \
    --group-prefix "$group_prefix" \
    --all-platforms

"$compose_root/gradlew" \
    -p "$compose_root/gradle-plugin" \
    --no-configuration-cache \
    "-Dmaven.repo.local=$maven_repository" \
    publishToMavenLocal

echo "Android and web Compose targets are available in $maven_repository"
