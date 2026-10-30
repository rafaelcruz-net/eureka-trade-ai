#!/usr/bin/env bash
# Manual load test (workflow load-test.yml). Requires SPRING_PROFILES_ACTIVE=test,loadtest in the environment: the
# API refuses loadtest without test, and only loadtest raises the login limit. Brings the environment up with the
# test overlay, runs scripts/load/load-test.mjs (20 users, one action every 2 s, 5 minutes) and tears it down.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "${SPRING_PROFILES_ACTIVE:-}" in
  *test*loadtest* | *loadtest*test*) ;;
  *) echo "SPRING_PROFILES_ACTIVE must be test,loadtest" >&2; exit 1 ;;
esac

COMPOSE=(docker compose -f docker-compose.yml -f infra/docker-compose.test.yml)
KEY_DIR="$(mktemp -d)"
KEY_FILE="$KEY_DIR/smoke-wallet.json"
trap '"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$KEY_DIR"' EXIT

random_secret() { head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | head -c 40; }
export POSTGRES_USER="${POSTGRES_USER:-memetrade_admin}" POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$(random_secret)}"
export DB_USER="${DB_USER:-memetrade_app}" DB_PASSWORD="${DB_PASSWORD:-$(random_secret)}"
export DB_OWNER_USER="${DB_OWNER_USER:-memetrade_owner}" DB_OWNER_PASSWORD="${DB_OWNER_PASSWORD:-$(random_secret)}"
export DB_RETENTION_USER="${DB_RETENTION_USER:-memetrade_retention}"
export DB_RETENTION_PASSWORD="${DB_RETENTION_PASSWORD:-$(random_secret)}"
export ACCESS_TOKEN_SIGNING_KEY="${ACCESS_TOKEN_SIGNING_KEY:-$(head -c 48 /dev/urandom | base64 | tr -d '\n')}"

sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test-compile exec:java \
  -Dexec.classpathScope=test -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool -Dexec.args="$KEY_FILE" >/dev/null
SMOKE_BETA_WALLET_ADDRESS="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$KEY_FILE")"
export SMOKE_BETA_WALLET_ADDRESS

"${COMPOSE[@]}" up -d --build --wait --wait-timeout 300
SMOKE_WALLET_FILE="$KEY_FILE" API_BASE_URL="http://127.0.0.1:8080" node scripts/load/load-test.mjs
