#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
GIT_DIR="$SCRIPT_DIR/.local-git"

exec git --git-dir="$GIT_DIR" --work-tree="$REPO_ROOT" "$@"
