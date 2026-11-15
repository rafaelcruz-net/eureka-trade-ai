#!/usr/bin/env bash
# Verification command of the AI-DLC Construction checkpoints.
#
# The first attempt for a commit runs the full local verification (scripts/verify.sh). The development machine is
# short on memory, and Claude Code may kill a long local run before it ends; such a run leaves its attempt marker
# behind. The next attempt for the same commit then takes the GitHub Actions "ci" run of that commit as the proof
# (its smoke job runs the same scripts/verify.sh), and only when:
#   - the working tree has no change outside the AI-DLC records (aidlc/ and the stray apps/web/aidlc/);
#   - the commit is on a remote branch;
#   - the "ci" run of the commit ended with success (it is awaited when still running).
# A local run that ends, green or red, removes its marker: a real failure is never replaced by the CI result.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

sha="$(git rev-parse HEAD)"
marker="$(git rev-parse --git-dir)/aidlc-verify-attempt-${sha}"

if [[ ! -f "$marker" ]]; then
  echo "[verify-checkpoint] local verification of ${sha} (first attempt)"
  : > "$marker"
  status=0
  bash scripts/verify.sh || status=$?
  rm -f "$marker"
  exit "$status"
fi

echo "[verify-checkpoint] the local attempt of ${sha} did not finish; using the GitHub Actions run of the commit"

changed="$(git status --porcelain --untracked-files=all | grep -v -E '^.. (aidlc/|apps/web/aidlc/)' || true)"
if [[ -n "$changed" ]]; then
  echo "[verify-checkpoint] the working tree differs from ${sha} outside the AI-DLC records; commit and push first:" >&2
  echo "$changed" >&2
  exit 1
fi

if [[ -z "$(git branch -r --contains "$sha")" ]]; then
  echo "[verify-checkpoint] ${sha} is not on a remote branch; push it first" >&2
  exit 1
fi

run_id="$(gh run list --workflow ci --commit "$sha" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
if [[ -z "$run_id" ]]; then
  echo "[verify-checkpoint] no \"ci\" run of ${sha} on GitHub" >&2
  exit 1
fi

echo "[verify-checkpoint] waiting for the \"ci\" run ${run_id}"
if ! gh run watch "$run_id" --exit-status --interval 30 > /dev/null; then
  echo "[verify-checkpoint] the \"ci\" run ${run_id} of ${sha} did not succeed" >&2
  gh run view "$run_id" --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"' >&2 || true
  exit 1
fi

gh run view "$run_id" --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
smoke="$(gh run view "$run_id" --json jobs --jq '.jobs[] | select(.name == "smoke") | .conclusion')"
if [[ "$smoke" != "success" ]]; then
  echo "[verify-checkpoint] the smoke job (scripts/verify.sh) of run ${run_id} is \"${smoke}\"" >&2
  exit 1
fi

rm -f "$marker"
echo "[verify-checkpoint] ${sha} verified by the GitHub Actions run ${run_id}"