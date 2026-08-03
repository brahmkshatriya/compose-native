#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
"$task_app_root/scripts/build.sh"
exec "$task_root/build/bin/compose-wayland"
