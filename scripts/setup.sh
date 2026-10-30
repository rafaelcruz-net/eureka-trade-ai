#!/usr/bin/env bash
# One-time developer setup: enables the versioned git hooks and checks the pinned gitleaks.
# Works in Git Bash on Windows and on Linux.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

git config core.hooksPath .githooks
echo "git hooks enabled from .githooks"

expected="$(tr -d '[:space:]' < .gitleaks-version)"
if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks not found. Install version $expected, for example:" >&2
  echo "  winget install --id Gitleaks.Gitleaks --version $expected   (Windows)" >&2
  echo "  scoop install gitleaks@$expected                            (Windows)" >&2
  exit 1
fi

installed="$(gitleaks version | tr -d '[:space:]v')"
if [ "$installed" != "$expected" ]; then
  echo "gitleaks $installed installed, but $expected is pinned in .gitleaks-version" >&2
  exit 1
fi
echo "gitleaks $installed OK"
