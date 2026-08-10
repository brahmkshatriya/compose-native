#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ ! -d "$task_compose_root/.git" ]]; then
    echo "The prepared Compose Linux checkout is missing at $task_compose_root." >&2
    echo "Patch bootstrapping has been removed; provide the source checkout directly." >&2
    exit 1
fi

if [[ ! -f "$task_compose_root/compose/animation/animation-core/src/linuxMain/kotlin/androidx/compose/animation/core/Expect.linux.kt" ]]; then
    echo "The Compose checkout does not contain the Linux port." >&2
    echo "Use the prepared .compose-core source checkout; patch bootstrapping has been removed." >&2
    exit 1
fi

if [[ ! -f "$task_app_root/build.gradle.kts" ]]; then
    echo "The native Wayland app module is missing at $task_app_root." >&2
    exit 1
fi
if [[ ! -f "$task_app_root/src/desktopNativeMain/kotlin/Main.kt" ]]; then
    echo "The native Wayland app sources are missing at $task_app_root/src." >&2
    exit 1
fi

echo "Compose Linux source is ready."
