#!/usr/bin/env bash

task_script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_app_root="$(cd "$task_script_root/.." && pwd)"
task_compose_root="$(cd "$task_app_root/.." && pwd)"
task_root="$task_app_root"
task_out_root="$task_app_root/build/androidx-out"
task_project_cache="$task_app_root/build/gradle-project-cache"

task_projects=':compose:material3:material3,:compose:material:material,:compose:material:material-ripple,:compose:foundation:foundation,:compose:foundation:foundation-layout,:compose:animation:animation,:compose:animation:animation-core,:compose:ui:ui,:compose:ui:ui-sdl2,:compose:ui:ui-skiko,:compose:ui:ui-backhandler,:compose:ui:ui-geometry,:compose:ui:ui-graphics,:compose:ui:ui-text,:compose:ui:ui-unit,:compose:ui:ui-util,:compose:runtime:runtime,:compose:runtime:runtime-saveable,:compose:runtime:runtime-retain,:compose:runtime:runtime-annotation,:compose:runtime:runtime-test-utils,:navigationevent:navigationevent,:navigationevent:navigationevent-compose,:appcompat:appcompat,:compose:animation:animation-core:animation-core-samples,:compose:animation:animation:animation-samples,:compose:foundation:foundation-layout:foundation-layout-samples,:compose:foundation:foundation-lint,:compose:foundation:foundation:foundation-samples,:compose:material3:material3:material3-samples,:compose:test-utils,:compose:ui:ui-android-stubs,:compose:ui:ui-graphics:ui-graphics-samples,:compose:ui:ui-lint,:compose:ui:ui-test,:compose:ui:ui-test-junit4,:compose:ui:ui-text-lint,:compose:ui:ui-text:ui-text-samples,:compose:ui:ui-uikit,:compose:ui:ui-unit:ui-unit-samples,:compose:ui:ui:ui-samples,:compose:lint:internal-lint-checks,:constraintlayout:constraintlayout-compose,:internal-testutils-espresso,:internal-testutils-fonts,:internal-testutils-runtime,:internal-testutils-xctest,:kruth:kruth,:test:screenshot:screenshot'

find_task_jdk() {
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

    echo "JDK 21 was not found. Set JAVA_HOME to a JDK 21 installation." >&2
    return 1
}
