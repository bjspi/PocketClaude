#!/usr/bin/env bash
# Sync install/clone snippets to this checkout's GitHub origin.
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"
REMOTE_URL="${1:-$(git -C "$ROOT_DIR" config --get remote.origin.url || true)}"

if [[ -z "$REMOTE_URL" ]]; then
    echo "No remote URL provided and remote.origin.url is not set." >&2
    exit 1
fi

case "$REMOTE_URL" in
    git@github.com:*)
        REPO_PATH="${REMOTE_URL#git@github.com:}"
        REPO_PATH="${REPO_PATH%.git}"
        ;;
    https://github.com/*)
        REPO_PATH="${REMOTE_URL#https://github.com/}"
        REPO_PATH="${REPO_PATH%.git}"
        ;;
    http://github.com/*)
        REPO_PATH="${REMOTE_URL#http://github.com/}"
        REPO_PATH="${REPO_PATH%.git}"
        ;;
    *)
        echo "Unsupported remote URL: $REMOTE_URL" >&2
        echo "Expected a GitHub URL such as https://github.com/owner/PocketClaude.git" >&2
        exit 1
        ;;
esac

REPO_PATH="${REPO_PATH%/}"
REPO_URL="https://github.com/${REPO_PATH}.git"
RAW_INSTALL_URL="https://raw.githubusercontent.com/${REPO_PATH}/main/server/deploy/install-linux.sh"

if [[ "$REPO_PATH" != */PocketClaude ]]; then
    echo "Refusing to sync repo URL for unexpected repo path: $REPO_PATH" >&2
    exit 1
fi

PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [[ -z "$PYTHON_BIN" ]]; then
    echo "python3/python is required to sync repo URLs." >&2
    exit 1
fi

"$PYTHON_BIN" - "$ROOT_DIR" "$REPO_URL" "$RAW_INSTALL_URL" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
repo_url = sys.argv[2]
raw_install_url = sys.argv[3]

files = [
    root / "README.md",
    root / "server" / "README.md",
    root / "server" / "deploy" / "README.md",
    root / "server" / "deploy" / "install-linux.sh",
]
i18n_dir = root / "docs" / "i18n"
if i18n_dir.exists():
    files.extend(sorted(i18n_dir.glob("README*.md")))

raw_pattern = re.compile(
    r"https://raw\.githubusercontent\.com/[^/]+/PocketClaude/main/server/deploy/install-linux\.sh"
)
clone_pattern = re.compile(r"https://github\.com/[^/]+/PocketClaude\.git")
repo_url_pattern = re.compile(
    r'REPO_URL="\$\{REPO_URL:-https://github\.com/[^/]+/PocketClaude\.git\}"'
)

for path in files:
    if not path.exists():
        continue
    original = path.read_text(encoding="utf-8")
    updated = raw_pattern.sub(raw_install_url, original)
    updated = clone_pattern.sub(repo_url, updated)
    updated = repo_url_pattern.sub(f'REPO_URL="${{REPO_URL:-{repo_url}}}"', updated)
    if updated != original:
        path.write_text(updated, encoding="utf-8", newline="")
        print(f"Updated {path.relative_to(root)} -> {repo_url}")
PY
