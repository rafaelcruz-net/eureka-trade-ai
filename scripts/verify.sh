#!/usr/bin/env bash
# Walking-skeleton verification (F7, NFR13.1). Generates a throw-away smoke wallet (key in a temporary file OUTSIDE
# the repository), starts docker-compose.yml + infra/docker-compose.test.yml, waits for the three services to be
# healthy and for the first collection of the simulated source (<= 60 s), runs the Playwright smoke, scans the API
# logs for the full wallet address, tears everything down and deletes the key. Any failure or timeout exits non-zero.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.yml -f infra/docker-compose.test.yml)
KEY_DIR="$(mktemp -d)"
KEY_FILE="$KEY_DIR/smoke-wallet.json"
ADMIN_KEY_FILE="$KEY_DIR/smoke-admin-wallet.json"
SMOKE_FEEDBACK_MARKER="smoke-u8-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-240}"
COLLECTION_TIMEOUT_S="${COLLECTION_TIMEOUT_S:-60}"
AUDIT_JOBS_TIMEOUT_S="${AUDIT_JOBS_TIMEOUT_S:-60}"

# Always the pnpm version pinned in apps/web/package.json ("packageManager"), even when run from the root.
PNPM_VERSION="$(sed -n 's/.*"packageManager": *"pnpm@\([^"]*\)".*/\1/p' apps/web/package.json)"
if command -v pnpm >/dev/null 2>&1 && [ "$(pnpm --version)" = "$PNPM_VERSION" ]; then
  PNPM=(pnpm)
else
  export COREPACK_ENABLE_DOWNLOAD_PROMPT=0
  PNPM=(corepack "pnpm@${PNPM_VERSION}")
fi

log() { printf '[verify] %s\n' "$*" >&2; }

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    log "failure (exit $status); last api, worker and admin logs:"
    "${COMPOSE[@]}" logs --no-color --tail 80 api worker admin >&2 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$KEY_DIR"
  log "environment removed and smoke key deleted"
  exit "$status"
}
trap cleanup EXIT

# Ephemeral secrets when no .env is present (CI): random, never printed, never written to the repository.
random_secret() { head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | head -c 40; }
if [ ! -f .env ]; then
  export POSTGRES_USER="${POSTGRES_USER:-memetrade_admin}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$(random_secret)}"
  export POSTGRES_DB="${POSTGRES_DB:-memetrade}"
  export DB_USER="${DB_USER:-memetrade_app}"
  export DB_PASSWORD="${DB_PASSWORD:-$(random_secret)}"
  export DB_OWNER_USER="${DB_OWNER_USER:-memetrade_owner}"
  export DB_OWNER_PASSWORD="${DB_OWNER_PASSWORD:-$(random_secret)}"
  export DB_RETENTION_USER="${DB_RETENTION_USER:-memetrade_retention}"
  export DB_RETENTION_PASSWORD="${DB_RETENTION_PASSWORD:-$(random_secret)}"
  # A base64 key can start with "/", and Git Bash on Windows rewrites such values as paths when it passes them to
  # docker.exe; MSYS2_ENV_CONV_EXCL keeps this variable unconverted (ignored on Linux).
  export ACCESS_TOKEN_SIGNING_KEY="${ACCESS_TOKEN_SIGNING_KEY:-$(head -c 48 /dev/urandom | base64 | tr -d '\n')}"
  export MSYS2_ENV_CONV_EXCL="ACCESS_TOKEN_SIGNING_KEY${MSYS2_ENV_CONV_EXCL:+;$MSYS2_ENV_CONV_EXCL}"
else
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi
# u2-retention (NFR6.2): ephemeral HMAC key of the audit wallet references, required by the api and the worker,
# with or without a .env (a value already in the environment or in .env wins).
export MEMETRADE_AUDIT_WALLET_HMAC_KEY="${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(openssl rand -hex 32)}"

log "generating the smoke wallet (test-scope tool)"
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="$KEY_FILE" >/dev/null
SMOKE_BETA_WALLET_ADDRESS="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$KEY_FILE")"
export SMOKE_BETA_WALLET_ADDRESS
[ -n "$SMOKE_BETA_WALLET_ADDRESS" ] || { log "wallet generation failed"; exit 1; }
log "generating the administrator smoke wallet (test-scope tool)"
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="$ADMIN_KEY_FILE" >/dev/null
ADMIN_WALLET_ADDRESSES="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$ADMIN_KEY_FILE")"
[ -n "$ADMIN_WALLET_ADDRESSES" ] || { log "administrator wallet generation failed"; exit 1; }
export ADMIN_WALLET_ADDRESSES

log "generating the smoke invitation code (test-scope tool)"
SMOKE_INVITE_CODE="$(sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="--invite-code" | tr -d '\r\n')"
export SMOKE_INVITE_CODE
[ "${#SMOKE_INVITE_CODE}" -eq 43 ] || { log "invitation code generation failed"; exit 1; }
log "generating the anonymization smoke invitation code (test-scope tool)"
SMOKE_ANONYMIZATION_INVITE_CODE="$(sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="--invite-code" | tr -d '\r\n')"
export SMOKE_ANONYMIZATION_INVITE_CODE
[ "${#SMOKE_ANONYMIZATION_INVITE_CODE}" -eq 43 ] || { log "anonymization invitation code generation failed"; exit 1; }


log "starting postgres, api and web with the test profile"
"${COMPOSE[@]}" up -d --build

service_health() {
  local id
  id="$("${COMPOSE[@]}" ps -q "$1")"
  [ -n "$id" ] || { echo missing; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id"
}

log "waiting for the four services to be healthy (<= ${HEALTH_TIMEOUT_S}s)"
deadline=$((SECONDS + HEALTH_TIMEOUT_S))
until [ "$(service_health postgres)" = healthy ] && [ "$(service_health api)" = healthy ] \
  && [ "$(service_health web)" = healthy ] && [ "$(service_health admin)" = healthy ]; do
  [ "$SECONDS" -lt "$deadline" ] || { log "timeout waiting for healthy services"; exit 1; }
  sleep 3
done

log "waiting for the first collection of the simulated source (<= ${COLLECTION_TIMEOUT_S}s)"
deadline=$((SECONDS + COLLECTION_TIMEOUT_S))
until health="$(curl -fsS http://127.0.0.1:8081/actuator/health 2>/dev/null)" \
  && printf '%s' "$health" | grep -q '"lastUpdatedAt"' \
  && printf '%s' "$health" | grep -q '^{"status":"UP"'; do
  [ "$SECONDS" -lt "$deadline" ] || { log "timeout: source not collected or health degraded"; exit 1; }
  sleep 2
done

log "waiting for the first successful audit retention purge and outbox cleanup (<= ${AUDIT_JOBS_TIMEOUT_S}s)"
deadline=$((SECONDS + AUDIT_JOBS_TIMEOUT_S))
until health="$(curl -fsS http://127.0.0.1:8081/actuator/health 2>/dev/null)" \
  && printf '%s' "$health" | grep -q '"auditRetention":{"status":"UP"' \
  && printf '%s' "$health" | grep -q '"auditOutboxCleanup":{"status":"UP"'; do
  [ "$SECONDS" -lt "$deadline" ] || { log "timeout: audit jobs did not complete or are degraded"; exit 1; }
  sleep 2
done

log "running the Playwright smoke"
SMOKE_WALLET_FILE="$KEY_FILE" \
  SMOKE_ADMIN_WALLET_FILE="$ADMIN_KEY_FILE" \
  SMOKE_FEEDBACK_MARKER="$SMOKE_FEEDBACK_MARKER" \
  ADMIN_BASE_URL="http://localhost:3001" \
  WEB_BASE_URL="http://localhost:3000" \
  API_BASE_URL="http://localhost:8080" \
  MANAGEMENT_BASE_URL="http://localhost:8081" \
  SMOKE_INVITE_CODE="$SMOKE_INVITE_CODE" \
  SMOKE_ANONYMIZATION_INVITE_CODE="$SMOKE_ANONYMIZATION_INVITE_CODE" \
  DATABASE_URL="postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@127.0.0.1:5432/${POSTGRES_DB:-memetrade}" \
  "${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl e2e/u5-bots-decision e2e/u8-admin-feedback e2e/u9-dashboard e2e/u3-identity-access e2e/legal-documents \
    e2e/reacceptance e2e/data-requests e2e/anonymization

log "scanning the api, worker and admin logs for full wallet addresses and the feedback text (NFR7.3)"
SERVICE_LOGS="$("${COMPOSE[@]}" logs --no-color api worker admin)"
for needle in "$SMOKE_BETA_WALLET_ADDRESS" "$ADMIN_WALLET_ADDRESSES" "$SMOKE_FEEDBACK_MARKER" "$SMOKE_INVITE_CODE" \
  "$SMOKE_ANONYMIZATION_INVITE_CODE"; do
  if printf '%s' "$SERVICE_LOGS" | grep -qF "$needle"; then
    log "a full wallet address or the feedback text appeared in the logs"
    exit 1
  fi
done

log "walking skeleton verified"
