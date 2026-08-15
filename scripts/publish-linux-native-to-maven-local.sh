#!/usr/bin/env bash
set -euo pipefail

compose_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

read_compose_property() {
    local property_name="$1"
    sed -n "s/^${property_name}=//p" "$compose_root/gradle.properties" | tail -n 1
}

contract_version="$(read_compose_property 'jetbrains.publication.version.COMPOSE')"
contract_group_prefix="$(read_compose_property 'jetbrains.publication.groupPrefix')"

version="${1:-${COMPOSE_NATIVE_VERSION:-$contract_version}}"
group_prefix="${COMPOSE_NATIVE_GROUP_PREFIX:-$contract_group_prefix}"
maven_repository="${MAVEN_LOCAL_REPOSITORY:-$HOME/.m2/repository}"

for checked_version in "$version"; do
    if [[ ! "$checked_version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
        echo "Invalid Maven version: $checked_version" >&2
        exit 1
    fi
done
for checked_group in "$group_prefix"; do
    if [[ ! "$checked_group" =~ ^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$ ]]; then
        echo "Invalid Maven group: $checked_group" >&2
        exit 1
    fi
done

case "${KTNATIVE_LINUX_ARCH:-$(uname -m)}" in
    x64|x86_64|amd64)
        linux_arch="x64"
        kotlin_target="LinuxX64"
        ;;
    arm64|aarch64)
        linux_arch="arm64"
        kotlin_target="LinuxArm64"
        ;;
    *)
        echo "Unsupported Linux architecture: ${KTNATIVE_LINUX_ARCH:-$(uname -m)}" >&2
        echo "Use KTNATIVE_LINUX_ARCH=x64 or KTNATIVE_LINUX_ARCH=arm64." >&2
        exit 1
        ;;
esac

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

echo "Publishing Compose Linux $linux_arch KLIBs as $group_prefix:*:$version"
echo "Resolving native Skiko from Maven Central"
"$compose_root/gradlew" \
    -p "$compose_root" \
    --no-configuration-cache \
    "-Dmaven.repo.local=$maven_repository" \
    "-Pcompose.platforms=$kotlin_target" \
    "-Pjetbrains.publication.groupPrefix=$group_prefix" \
    "-Pjetbrains.publication.version.COMPOSE=$version" \
    "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$version" \
    "-Pjetbrains.publication.version.NAVIGATION_3=$version" \
    "-Pjetbrains.publication.version.NAVIGATION_EVENT=$version" \
    "-Pjetbrains.publication.version.LIFECYCLE=$version" \
    "-Pjetbrains.publication.version.SAVEDSTATE=$version" \
    :mpp:publishComposeNativeToMavenLocal \
    :mpp:publishComposeForkRootsToMavenLocal

metadata_args=(
    --repository "$maven_repository"
    --version "$version"
    --group-prefix "$group_prefix"
    --upstream-compose-version "$(read_compose_property 'compose.native.upstream.version.COMPOSE')"
    --upstream-material3-version "$(read_compose_property 'compose.native.upstream.version.COMPOSE_MATERIAL3')"
    --upstream-lifecycle-version "$(read_compose_property 'compose.native.upstream.version.LIFECYCLE')"
    --upstream-navigation3-version "$(read_compose_property 'compose.native.upstream.version.NAVIGATION_3')"
    --upstream-navigationevent-version "$(read_compose_property 'compose.native.upstream.version.NAVIGATION_EVENT')"
    --upstream-savedstate-version "$(read_compose_property 'compose.native.upstream.version.SAVEDSTATE')"
    --native-skiko-group "$(read_compose_property 'compose.native.skiko.group')"
    --native-skiko-version "$(read_compose_property 'compose.native.skiko.version')"
)
"$compose_root/scripts/write-linux-native-root-metadata.py" "${metadata_args[@]}"

echo "Publishing dev.brahmkshatriya.compose Gradle plugin"
"$compose_root/gradlew" \
    -p "$compose_root/gradle-plugin" \
    --no-configuration-cache \
    "-Dmaven.repo.local=$maven_repository" \
    publishToMavenLocal

echo "Linux-native Compose is available in $maven_repository"
