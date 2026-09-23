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

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "iOS publication requires macOS with Xcode installed." >&2
    exit 1
fi
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
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        /usr/libexec/java_home -v 21 2>/dev/null && return
    fi
    echo "JDK 21 was not found. Set JAVA_HOME." >&2
    return 1
}

export JAVA_HOME="$(find_jdk)"
export ANDROIDX_JDK21="${ANDROIDX_JDK21:-$JAVA_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"

xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version >/dev/null
xcrun --sdk iphonesimulator --show-sdk-version >/dev/null

echo "Publishing forked Compose iOS targets as $group_prefix:*:$version"
"$compose_root/gradlew" \
    -p "$compose_root" \
    --no-configuration-cache \
    --no-configure-on-demand \
    "-Dmaven.repo.local=$maven_repository" \
    -Pcompose.platforms=IosArm64,IosSimulatorArm64 \
    "-Pjetbrains.publication.groupPrefix=$group_prefix" \
    "-Pjetbrains.publication.version.COMPOSE=$version" \
    "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$version" \
    "-Pjetbrains.publication.version.NAVIGATION_3=$version" \
    "-Pjetbrains.publication.version.NAVIGATION_EVENT=$version" \
    "-Pjetbrains.publication.version.LIFECYCLE=$version" \
    "-Pjetbrains.publication.version.SAVEDSTATE=$version" \
    :mpp:publishCompleteComposeForkToMavenLocal

"$compose_root/scripts/write-linux-native-root-metadata.py" \
    --repository "$maven_repository" \
    --version "$version" \
    --group-prefix "$group_prefix" \
    --upstream-compose-version "$(read_compose_property 'compose.native.upstream.version.COMPOSE')" \
    --upstream-material3-version "$(read_compose_property 'compose.native.upstream.version.COMPOSE_MATERIAL3')" \
    --upstream-lifecycle-version "$(read_compose_property 'compose.native.upstream.version.LIFECYCLE')" \
    --upstream-navigation3-version "$(read_compose_property 'compose.native.upstream.version.NAVIGATION_3')" \
    --upstream-navigationevent-version "$(read_compose_property 'compose.native.upstream.version.NAVIGATION_EVENT')" \
    --upstream-savedstate-version "$(read_compose_property 'compose.native.upstream.version.SAVEDSTATE')" \
    --native-skiko-group "$(read_compose_property 'compose.native.skiko.group')" \
    --native-skiko-version "$(read_compose_property 'compose.native.skiko.version')" \
    --all-platforms

"$compose_root/gradlew" \
    -p "$compose_root/gradle-plugin" \
    --no-configuration-cache \
    "-Dmaven.repo.local=$maven_repository" \
    publishToMavenLocal

echo "iOS Compose targets are available in $maven_repository"
