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
# u2-retention (NFR6.2): ephemeral HMAC key of the audit wallet references, required by the api and the worker.
export MEMETRADE_AUDIT_WALLET_HMAC_KEY="${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(openssl rand -hex 32)}"

sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test-compile exec:java \
  -Dexec.classpathScope=test -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool -Dexec.args="$KEY_FILE" >/dev/null
SMOKE_BETA_WALLET_ADDRESS="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$KEY_FILE")"
export SMOKE_BETA_WALLET_ADDRESS

# U4 (cicd-pipeline.md): LOAD_SCENARIO=u1 (default; 200 tokens per source, 5 minutes, U1 limit of 300 ms kept),
# u4 (2000 tokens per source, 10 minutes, list with filters and detail) or growth (10000 tokens, 10 minutes, no
# failure threshold, result only recorded; local only, before G1). The whole run uses ONE person, so the per-person
# limit of the scanner is raised for the load only.
# U3 (cicd-pipeline.md): LOAD_SCENARIO=u3 seeds one invitation per simulated user (SMOKE_INVITE_CODES, test profile
# only), runs 20 simultaneous verifications with an invitation (p95 <= 500 ms, NFR3.1) and then 5 minutes of
# invitation lookups and Account screens (p95 <= 300 ms, NFR3.2). The lookup limit is raised for this run only.
LOAD_SCENARIO="${LOAD_SCENARIO:-u1}"
case "$LOAD_SCENARIO" in
  u1) export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-300}" ;;
  u4) export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-2000}" DURATION_S="${DURATION_S:-600}" ;;
  u3)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-300}"
    export INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE="${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:-1000}"
    # 20 throw-away codes (32 random bytes in base64url), never written to a file; the seeder stores only digests.
    SMOKE_INVITE_CODES="$(node -e "const c=require('node:crypto');console.log(Array.from({length:Number(process.env.USERS||20)},()=>c.randomBytes(32).toString('base64url')).join(','))")"
    export SMOKE_INVITE_CODES
    ;;
  growth) export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-10000}" DURATION_S="${DURATION_S:-600}" ;;
  # U7 (cicd-pipeline.md): u7 seeds 20 people with 10000 fills each through PaperExecution, runs 60 bots of the load
  # cycle driver every 30 s and 20 users on the C5 routes for 10 minutes (p95 execute <= 250 ms, NFR3.1; p95 C5 routes
  # <= 1 s, NFR3.2, NFR3.3; no Seq Scan on ledger_entry or fill, NFR2.2). u7-jobs runs 60 bots with 5 positions each
  # for 30 minutes and fails when any marking or pending close round takes more than 10 s (NFR3.5). Both run the load
  # cycle driver of the loadtest profile (com.memetrade.ledger.loadtest): the people are the smoke account plus one
  # account per invitation of SMOKE_INVITE_CODES, created and signed in by load-test.mjs with wallets generated in
  # memory; the driver binds their session principals to the bots (docs/api/u7-ledger-e-pnl.md).
  # u7 also closes 10 of the 60 bots during the run (p95 closePositions <= 250 ms, NFR3.1; review repair R-04) and
  # times "check now" of every person (<= 5 s, NFR3.6); u7-jobs keeps all 60 bots with their 5 positions.
  u7)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-600}"
    export LOADTEST_LEDGER_PEOPLE="${LOADTEST_LEDGER_PEOPLE:-20}" LOADTEST_FILLS_PER_PERSON="${LOADTEST_FILLS_PER_PERSON:-10000}"
    export LOADTEST_CYCLE_BOTS="${LOADTEST_CYCLE_BOTS:-60}" LOADTEST_TOKENS_PER_BOT="${LOADTEST_TOKENS_PER_BOT:-5}"
    export LOADTEST_CLOSE_BOTS="${LOADTEST_CLOSE_BOTS:-10}"
    ;;
  u7-jobs)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-1800}"
    export LOADTEST_LEDGER_PEOPLE="${LOADTEST_LEDGER_PEOPLE:-20}" LOADTEST_FILLS_PER_PERSON="${LOADTEST_FILLS_PER_PERSON:-0}"
    export LOADTEST_CYCLE_BOTS="${LOADTEST_CYCLE_BOTS:-60}" LOADTEST_TOKENS_PER_BOT="${LOADTEST_TOKENS_PER_BOT:-5}"
    export LOADTEST_CLOSE_BOTS=0
    ;;
  # U6 (cicd-pipeline.md): u6 reuses the 60 PAPER accounts of the ledger load (FUNDING only, no seeded fills) of 20
  # people (counted by LOADTEST_LEDGER_PEOPLE, no variable of its own), seeds 10000 rejections on one bot of each person,
  # 80000 on one bot and 200000 approved evaluations 32 to 40 days old, then runs one evaluation per bot every 30 s
  # (half refused) and 20 users reading C21 for 10 minutes; the purge fires at LOADTEST_U6_PURGE_AT (300 s). p95 of
  # evaluate <= 250 ms in each window (NFR2.1), the second window at most 10% above the first, p95 of C21 <= 1 s
  # (NFR3.1), every purge batch <= 100 ms (NFR2.5) and no Seq Scan on order_evaluation or order_rejection.
  u6)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-600}"
    export LOADTEST_LEDGER_PEOPLE="${LOADTEST_LEDGER_PEOPLE:-20}" LOADTEST_FILLS_PER_PERSON=0
    export LOADTEST_CYCLE_BOTS=60 LOADTEST_TOKENS_PER_BOT="${LOADTEST_TOKENS_PER_BOT:-5}" LOADTEST_CLOSE_BOTS=0
    export LOADTEST_U6_ENABLED=true
    ;;
  # U5 (cicd-pipeline.md): u5 runs api and worker with test,loadtest (the worker adds its own profile in the overlay).
  # load-test.mjs signs 20 people in and makes 3 ACTIVE bots each through the real C4 routes; the bot seed of the api
  # then writes 20000 decisions on one bot of each person and the worker cycles run with the stopwatch of the loadtest
  # profile, over the frequent-signal scenario of the simulated source, for 30 minutes (every slice that fits whole in
  # the run must be complete: scripts/load/u5-slices.mjs). The ledger load of
  # U7 stays at 0 (the cycles are those of the worker), so the people are counted by LOADTEST_U5_PEOPLE.
  u5)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-1800}"
    export LOADTEST_U5_ENABLED=true LOADTEST_U5_PEOPLE="${LOADTEST_U5_PEOPLE:-20}"
    export LOADTEST_U5_DECISIONS_PER_BOT="${LOADTEST_U5_DECISIONS_PER_BOT:-20000}"
    export MARKET_SIMULATED_SCENARIO=FREQUENT_SIGNALS
    ;;
  # u1-legal-documents (performance-design.md "Medição"): legal runs 20 users for 5 minutes, half on the pages /termos
  # and /privacidade of the guest app and half on /api/v1/legal/** (p95 page <= 2 s, p95 api <= 300 ms, no 5xx). The
  # per-IP limit of the legal documents is raised by application-loadtest.yml (profile loadtest), never by a variable.
  legal) export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-300}" ;;
  # u3-reacceptance (infrastructure-specification.md "Dados de teste para a trava"): reacceptance creates 20 outdated
  # guests (acceptance of v1 only; the test overlay mounts the catalog "reviewed", current v2) through DATABASE_URL,
  # the same SQL path as the e2e spec, and each one is locked, accepts and passes (p95 acceptance <= 500 ms, p95 lock
  # check <= 5 ms, from /actuator/prometheus). DATABASE_URL uses the ephemeral credentials generated above.
  reacceptance)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-60}"
    export DATABASE_URL="postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@127.0.0.1:5432/${POSTGRES_DB:-memetrade}"
    ;;
  # u4-data-requests (infrastructure-specification.md "Dados de teste"): data-requests creates 20 guests through their
  # own invitations (as u3), seeds 1000 synthetic requests through the loadtest profile (LOADTEST_DATA_REQUESTS_SEED,
  # 0 in every other scenario) and signs an administrator in with a throw-away wallet generated below; 5 minutes of
  # creations and lists by the guests and of list, detail and preview by the administrator (p95 <= 500 ms guest,
  # <= 1 s administrator, NFR3.1 to NFR3.3).
  data-requests)
    export MARKET_SIMULATED_TOKENS_PER_SOURCE="${MARKET_SIMULATED_TOKENS_PER_SOURCE:-200}" DURATION_S="${DURATION_S:-300}"
    export USERS="${USERS:-20}" LOADTEST_DATA_REQUESTS_SEED="${LOADTEST_DATA_REQUESTS_SEED:-1000}"
    # 20 throw-away codes (32 random bytes in base64url), never written to a file; the seeder stores only digests.
    SMOKE_INVITE_CODES="$(node -e "const c=require('node:crypto');console.log(Array.from({length:Number(process.env.USERS||20)},()=>c.randomBytes(32).toString('base64url')).join(','))")"
    export SMOKE_INVITE_CODES
    ;;
  *) echo "LOAD_SCENARIO must be u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance or data-requests" >&2; exit 1 ;;
esac
# Only data-requests seeds the synthetic requests and needs an administrator: its throw-away wallet has the format of
# scripts/verify.sh (test-scope tool), the key file lives in the temporary directory of this run and is deleted with it.
if [ "$LOAD_SCENARIO" = "data-requests" ]; then
  ADMIN_KEY_FILE="$KEY_DIR/smoke-admin-wallet.json"
  sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
    -Dexec.classpathScope=test -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool -Dexec.args="$ADMIN_KEY_FILE" >/dev/null
  ADMIN_WALLET_ADDRESSES="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$ADMIN_KEY_FILE")"
  [ -n "$ADMIN_WALLET_ADDRESSES" ] || { echo "administrator wallet generation failed" >&2; exit 1; }
  export ADMIN_WALLET_ADDRESSES SMOKE_ADMIN_WALLET_FILE="$ADMIN_KEY_FILE"
else
  export LOADTEST_DATA_REQUESTS_SEED=0
fi
case "$LOAD_SCENARIO" in
  u5)
    # As in u7: one throw-away invitation per person besides the smoke account, never written to a file.
    SMOKE_INVITE_CODES="$(node -e "const c=require('node:crypto');console.log(Array.from({length:Math.max(0,Number(process.env.LOADTEST_U5_PEOPLE)-1)},()=>c.randomBytes(32).toString('base64url')).join(','))")"
    export SMOKE_INVITE_CODES USERS="${USERS:-$LOADTEST_U5_PEOPLE}"
    export LOADTEST_LEDGER_PEOPLE=0 LOADTEST_FILLS_PER_PERSON=0 LOADTEST_CYCLE_BOTS=0 LOADTEST_CLOSE_BOTS=0
    ;;
  u7 | u7-jobs | u6)
    # One throw-away invitation per person besides the smoke account, never written to a file (as in u3).
    SMOKE_INVITE_CODES="$(node -e "const c=require('node:crypto');console.log(Array.from({length:Math.max(0,Number(process.env.LOADTEST_LEDGER_PEOPLE)-1)},()=>c.randomBytes(32).toString('base64url')).join(','))")"
    export SMOKE_INVITE_CODES USERS="${USERS:-$LOADTEST_LEDGER_PEOPLE}"
    ;;
  # The scenarios of U1 to U4 measure without the ledger load.
  *) export LOADTEST_LEDGER_PEOPLE=0 LOADTEST_FILLS_PER_PERSON=0 LOADTEST_CYCLE_BOTS=0 LOADTEST_CLOSE_BOTS=0 ;;
esac
# Only u6 runs the load risk driver of U6.
[ "$LOAD_SCENARIO" = "u6" ] || export LOADTEST_U6_ENABLED=false
# Only u5 runs the bot seed and the cycle stopwatch, over the frequent-signal scenario.
if [ "$LOAD_SCENARIO" != "u5" ]; then
  export LOADTEST_U5_ENABLED=false MARKET_SIMULATED_SCENARIO=STANDARD
fi
export SCANNER_RATE_LIMIT_PER_MINUTE="${SCANNER_RATE_LIMIT_PER_MINUTE:-10000}" LOAD_SCENARIO

"${COMPOSE[@]}" up -d --build --wait --wait-timeout 300
API_CONTAINER="$("${COMPOSE[@]}" ps -q api)"
POSTGRES_CONTAINER="$("${COMPOSE[@]}" ps -q postgres)"
# U5: the worker container (docker stats, JSON logs of the stopwatch) and its actuator, published on loopback 8082
# by the test overlay only.
WORKER_CONTAINER="$("${COMPOSE[@]}" ps -q worker)"
SMOKE_WALLET_FILE="$KEY_FILE" API_BASE_URL="http://127.0.0.1:8080" \
  API_CONTAINER="$API_CONTAINER" POSTGRES_CONTAINER="$POSTGRES_CONTAINER" \
  WORKER_CONTAINER="$WORKER_CONTAINER" WORKER_MANAGEMENT_URL="http://127.0.0.1:8082" \
  DB_SUPERUSER="$POSTGRES_USER" DB_NAME="${POSTGRES_DB:-memetrade}" COMPOSE_LOGS_SERVICE=api \
  node scripts/load/load-test.mjs
