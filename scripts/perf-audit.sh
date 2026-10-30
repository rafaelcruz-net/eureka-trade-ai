#!/usr/bin/env bash
# U2 audit-trail performance check (Step 17 of the U2 code-generation plan; NFR3.1, NFR3.3, NFR4.2). LOCAL ONLY,
# never in CI. Needs Docker Desktop, JDK 21, Git Bash on Windows, about 30 GB of free disk and the daily environment
# stopped (ports 5432, 8080, 8081 and 3000 free). Runs in its own Compose project (memetrade-perf) with its own
# volume, removed at the end.
#
# What it does:
#   1. starts postgres -> db-bootstrap -> db-migrate (no api yet);
#   2. seeds ~37 million synthetic events over the last 12 months, two loads of 100 thousand events 13 and 14
#      months old and 100 thousand outbox rows delivered 8 days ago;
#   3. query (NFR3.1): the trail query of JdbcAuditEventReader in three scenarios (31-day window, 31-day window
#      filtered by action, deep page by cursor), each with the AUDIT_QUERIED outbox write in the same transaction,
#      through the APPLICATION login; p95 <= 2 s and EXPLAIN without a separate Sort node. There is no administrator
#      token before U8, so this measures the database work of the route, not the HTTP p95;
#   4. starts the api: the startup recovery runs the purge and the outbox cleanup (NFR3.3): both COMPLETED within
#      30 minutes, nothing older than the cutoff left, nothing inside the 12 months deleted;
#   5. delivery (NFR4.2): writes synthetic outbox rows at a steady rate for PERF_DELIVERY_WINDOW_S (default 1 hour)
#      and reads audit_delivery_seconds: >= 99% delivered within 5 s;
#   6. writes the report under docs/perf/ and exits non-zero when any target is missed.
#
# Synthetic data only: no real wallet, mint or personal data. Secrets are ephemeral and never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROJECT=memetrade-perf
COMPOSE=(docker compose -p "$PROJECT" -f docker-compose.yml -f infra/docker-compose.test.yml)
SEED_EVENTS="${PERF_SEED_EVENTS:-37000000}"
SEED_CHUNK="${PERF_SEED_CHUNK:-1000000}"
OLD_LOAD="${PERF_OLD_LOAD:-100000}"
OUTBOX_OLD="${PERF_OUTBOX_OLD:-100000}"
QUERY_RUNS="${PERF_QUERY_RUNS:-40}"
QUERY_P95_MS="${PERF_QUERY_P95_MS:-2000}"
DELIVERY_WINDOW_S="${PERF_DELIVERY_WINDOW_S:-3600}"
DELIVERY_RATE="${PERF_DELIVERY_RATE:-2}"
DELIVERY_TARGET_PCT="${PERF_DELIVERY_TARGET_PCT:-99}"
JOB_LIMIT_S="${PERF_JOB_LIMIT_S:-1800}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-300}"
RUN_DATE="$(date -u +%Y-%m-%d)"
REPORT_DIR="docs/perf"
REPORT_FILE="${REPORT_DIR}/u2-audit-trail-${RUN_DATE}.md"
KEY_DIR="$(mktemp -d)"
KEY_FILE="$KEY_DIR/smoke-wallet.json"

log() { printf '[perf-audit] %s\n' "$*" >&2; }
FAILURES=()
fail_target() { FAILURES+=("$1"); log "TARGET MISSED: $1"; }

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "${#FAILURES[@]}" -eq 0 ]; then
    log "aborted (exit $status); last api logs:"
    "${COMPOSE[@]}" logs --no-color --tail 60 api >&2 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$KEY_DIR"
  log "perf environment and volume removed"
  exit "$status"
}
trap cleanup EXIT

# 1. Ports must be free: the daily environment uses the same ones.
for port in 5432 8080 8081 3000; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    log "port $port is in use; stop the daily environment (docker compose down) first"
    exit 1
  fi
done

# Ephemeral secrets, as in scripts/verify.sh; a local .env is ignored on purpose (own throw-away database).
random_secret() { head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | head -c 40; }
export POSTGRES_USER=memetrade_admin POSTGRES_DB=memetrade
export POSTGRES_PASSWORD="$(random_secret)"
export DB_USER=memetrade_app DB_PASSWORD="$(random_secret)"
export DB_OWNER_USER=memetrade_owner DB_OWNER_PASSWORD="$(random_secret)"
export DB_RETENTION_USER=memetrade_retention DB_RETENTION_PASSWORD="$(random_secret)"
export ACCESS_TOKEN_SIGNING_KEY="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
export MSYS2_ENV_CONV_EXCL="ACCESS_TOKEN_SIGNING_KEY${MSYS2_ENV_CONV_EXCL:+;$MSYS2_ENV_CONV_EXCL}"

# The test overlay requires the smoke wallet address (public address only, generated in test code).
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="$KEY_FILE" >/dev/null
SMOKE_BETA_WALLET_ADDRESS="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$KEY_FILE")"
export SMOKE_BETA_WALLET_ADDRESS
[ -n "$SMOKE_BETA_WALLET_ADDRESS" ] || { log "wallet generation failed"; exit 1; }

# psql inside the postgres container: as superuser (seed) or as the application login (measurements).
psql_super() { "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }
psql_app() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$DB_PASSWORD" postgres \
    psql -v ON_ERROR_STOP=1 -qAt -h 127.0.0.1 -U "$DB_USER" -d "$POSTGRES_DB" "$@"
}

log "starting postgres, db-bootstrap and db-migrate (project $PROJECT)"
"${COMPOSE[@]}" up -d --build postgres db-bootstrap db-migrate
deadline=$((SECONDS + HEALTH_TIMEOUT_S))
until [ "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$("${COMPOSE[@]}" ps -aq db-migrate)" 2>/dev/null)" = "exited 0" ]; do
  [ "$SECONDS" -lt "$deadline" ] || { log "timeout waiting for db-migrate"; exit 1; }
  sleep 3
done

# 2. Seed. Instants are spread inside the retention window (cutoff = start of the UTC day minus 12 months) with a
# one-day margin; the old loads sit at 13 and 14 months.
log "seeding $SEED_EVENTS events in chunks of $SEED_CHUNK (this takes a while)"
seed_start=$SECONDS
seeded=0
while [ "$seeded" -lt "$SEED_EVENTS" ]; do
  n=$(( SEED_EVENTS - seeded < SEED_CHUNK ? SEED_EVENTS - seeded : SEED_CHUNK ))
  psql_super <<SQL
INSERT INTO audit_event (event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id,
                         occurred_at, recorded_at, details)
SELECT gen_random_uuid(),
       CASE WHEN g % 10 = 0 THEN NULL ELSE ('00000000-0000-4000-8000-' || lpad(((g % 500) + 1)::text, 12, '0'))::uuid END,
       'perf-actor-' || (g % 500),
       (ARRAY['PERSON','SYSTEM','ANONYMOUS','ADMIN'])[1 + g % 4],
       (ARRAY['LOGIN_SUCCEEDED','REQUEST_FORBIDDEN','SESSION_REFRESHED','LOGOUT','AUDIT_QUERIED'])[1 + g % 5],
       NULL,
       'perf-' || g,
       t, t,
       jsonb_build_object('synthetic', 'true')
FROM generate_series($((seeded + 1)), $((seeded + n))) AS g,
     LATERAL (SELECT now() - interval '1 day' - random() * interval '363 days' + g * interval '0' AS t) AS ts;
SQL
  seeded=$((seeded + n))
  log "  $seeded / $SEED_EVENTS"
done
for months_old in 13 14; do
  psql_super <<SQL
INSERT INTO audit_event (event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id,
                         occurred_at, recorded_at, details)
SELECT gen_random_uuid(), NULL, 'perf-old', 'SYSTEM', 'LOGIN_SUCCEEDED', NULL, 'perf-old-' || g,
       now() - interval '$months_old months' + (g % 86400) * interval '1 second',
       now() - interval '$months_old months',
       jsonb_build_object('synthetic', 'true')
FROM generate_series(1, $OLD_LOAD) AS g;
SQL
done
psql_super <<SQL
INSERT INTO audit_outbox (event_id, payload, created_at, delivered_at)
SELECT gen_random_uuid(), jsonb_build_object('synthetic', 'true'), now() - interval '8 days', now() - interval '8 days'
FROM generate_series(1, $OUTBOX_OLD);
VACUUM ANALYZE audit_event;
VACUUM ANALYZE audit_outbox;
SQL
seed_s=$((SECONDS - seed_start))
inside_before="$(psql_super -c "SELECT count(*) FROM audit_event WHERE occurred_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '12 months') AT TIME ZONE 'UTC';")"
log "seed done in ${seed_s}s"

# 3. Query (NFR3.1). Same statements as JdbcAuditEventReader, plus the AUDIT_QUERIED outbox write, in one
# read-write transaction through the application login. Random 31-day windows inside the seeded year.
query_sql() {
  local scenario=$1 from=$2 to=$3
  case "$scenario" in
    window)
      echo "SELECT event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id, occurred_at, details
            FROM audit_event WHERE occurred_at >= '$from' AND occurred_at < '$to'
            ORDER BY occurred_at DESC, event_id DESC LIMIT 101" ;;
    action)
      echo "SELECT event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id, occurred_at, details
            FROM audit_event WHERE action = 'REQUEST_FORBIDDEN' AND occurred_at >= '$from' AND occurred_at < '$to'
            ORDER BY occurred_at DESC, event_id DESC LIMIT 101" ;;
    cursor)
      # Deep page: the cursor sits in the middle of the window, as after many pages.
      echo "SELECT event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id, occurred_at, details
            FROM audit_event WHERE occurred_at >= '$from' AND occurred_at < '$to'
              AND (occurred_at, event_id) < ('$from'::timestamptz + interval '15 days', 'ffffffff-ffff-4fff-bfff-ffffffffffff'::uuid)
            ORDER BY occurred_at DESC, event_id DESC LIMIT 101" ;;
  esac
}
outbox_sql() {
  echo "INSERT INTO audit_outbox (event_id, payload, created_at, delivered_at)
        SELECT id, jsonb_build_object('eventId', id::text, 'tenantId', NULL, 'actor', 'perf-admin',
                'actorType', 'ADMIN', 'action', 'AUDIT_QUERIED', 'targetRef', NULL, 'correlationId', 'perf-query',
                'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),
                'details', jsonb_build_object('from', '$1', 'to', '$2')), now(), NULL
        FROM (SELECT gen_random_uuid() AS id) AS ids"
}
now_ms() { date +%s%3N; }
declare -A P95 PLAN_OK
for scenario in window action cursor; do
  times=()
  for _ in $(seq 1 "$QUERY_RUNS"); do
    offset_days=$(( (RANDOM % 320) + 5 ))
    from="$(date -u -d "-$((offset_days + 31)) days" +%Y-%m-%dT%H:%M:%SZ)"
    to="$(date -u -d "-$offset_days days" +%Y-%m-%dT%H:%M:%SZ)"
    t0=$(now_ms)
    psql_app -c "BEGIN; $(query_sql "$scenario" "$from" "$to"); $(outbox_sql "$from" "$to"); COMMIT;" >/dev/null
    times+=($(( $(now_ms) - t0 )))
  done
  P95[$scenario]="$(printf '%s\n' "${times[@]}" | sort -n | awk -v n="${#times[@]}" '{a[NR]=$1} END {i=int(n*0.95); if (i<n*0.95) i++; print a[i]}')"
  plan="$(psql_app -c "EXPLAIN $(query_sql "$scenario" "$from" "$to")")"
  if printf '%s' "$plan" | grep -q -E '(^|[^a-zA-Z])Sort( |$)'; then PLAN_OK[$scenario]=no; else PLAN_OK[$scenario]=yes; fi
  log "query $scenario: p95 ${P95[$scenario]} ms, plan without separate sort: ${PLAN_OK[$scenario]}"
  [ "${P95[$scenario]}" -le "$QUERY_P95_MS" ] || fail_target "query $scenario p95 ${P95[$scenario]} ms > ${QUERY_P95_MS} ms"
  [ "${PLAN_OK[$scenario]}" = yes ] || fail_target "query $scenario plan has a separate Sort node"
done

# 4. Purge and cleanup by the startup recovery (no audit_job_run row yet).
log "starting the api; the startup recovery runs the purge and the outbox cleanup"
"${COMPOSE[@]}" up -d api
jobs_start=$SECONDS
job_state() { psql_super -c "SELECT last_result || ' ' || (last_success_at IS NOT NULL) FROM audit_job_run WHERE job_name = '$1';"; }
deadline=$((SECONDS + JOB_LIMIT_S + HEALTH_TIMEOUT_S))
for job in AUDIT_RETENTION_PURGE AUDIT_OUTBOX_CLEANUP; do
  state=""
  until state="$(job_state "$job")" && [ -n "$state" ]; do
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep 5
  done
  log "$job: ${state:-no run}"
  [ "${state:-}" = "COMPLETED true" ] || fail_target "$job result '${state:-none}' (expected COMPLETED)"
done
jobs_s=$((SECONDS - jobs_start))
old_left="$(psql_super -c "SELECT count(*) FROM audit_event WHERE occurred_at < (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '12 months') AT TIME ZONE 'UTC';")"
inside_after="$(psql_super -c "SELECT count(*) FROM audit_event WHERE occurred_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '12 months') AT TIME ZONE 'UTC';")"
outbox_old_left="$(psql_super -c "SELECT count(*) FROM audit_outbox WHERE delivered_at < now() - interval '7 days';")"
[ "$old_left" = 0 ] || fail_target "$old_left events older than the cutoff remain"
[ "$inside_after" -ge "$inside_before" ] || fail_target "events inside the 12 months were deleted ($inside_before -> $inside_after)"
[ "$outbox_old_left" = 0 ] || fail_target "$outbox_old_left delivered outbox rows older than 7 days remain"
job_durations="$(curl -fsS http://127.0.0.1:8081/actuator/prometheus | grep -E '^audit_job_duration_seconds_(sum|count)' || true)"

# 5. Delivery (NFR4.2): steady synthetic writes, one commit per second, for the whole window.
metric() { curl -fsS http://127.0.0.1:8081/actuator/prometheus | grep -E "$1" | awk '{s+=$NF} END {printf "%d", s}'; }
le5_before="$(metric '^audit_delivery_seconds_bucket\{.*le="5\.0"')"
cnt_before="$(metric '^audit_delivery_seconds_count')"
log "delivery window: ${DELIVERY_WINDOW_S}s at ${DELIVERY_RATE} events/s"
psql_app <<SQL
DO \$\$
BEGIN
  FOR s IN 1..$DELIVERY_WINDOW_S LOOP
    INSERT INTO audit_outbox (event_id, payload, created_at, delivered_at)
    SELECT id, jsonb_build_object('eventId', id::text, 'tenantId', NULL, 'actor', 'perf-relay', 'actorType', 'SYSTEM',
                                  'action', 'PERF_DELIVERY', 'targetRef', NULL, 'correlationId', 'perf-delivery-' || s,
                                  'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                                  'details', '{}'::jsonb), now(), NULL
    FROM (SELECT gen_random_uuid() AS id FROM generate_series(1, $DELIVERY_RATE)) AS ids;
    COMMIT;
    PERFORM pg_sleep(1);
  END LOOP;
END
\$\$;
SQL
sleep 30
le5_after="$(metric '^audit_delivery_seconds_bucket\{.*le="5\.0"')"
cnt_after="$(metric '^audit_delivery_seconds_count')"
delivered=$((cnt_after - cnt_before))
within5=$((le5_after - le5_before))
expected=$((DELIVERY_WINDOW_S * DELIVERY_RATE))
delivery_pct="$(awk -v a="$within5" -v b="$delivered" 'BEGIN {if (b == 0) print "0.00"; else printf "%.2f", 100 * a / b}')"
log "delivery: $within5 of $delivered within 5 s (${delivery_pct}%), $expected written"
[ "$delivered" -ge "$expected" ] || fail_target "only $delivered of $expected synthetic events were delivered"
awk -v p="$delivery_pct" -v t="$DELIVERY_TARGET_PCT" 'BEGIN {exit !(p >= t)}' \
  || fail_target "delivery within 5 s ${delivery_pct}% < ${DELIVERY_TARGET_PCT}%"

# 6. Report.
mkdir -p "$REPORT_DIR"
{
  echo "# Desempenho da trilha de auditoria (U2) — ${RUN_DATE}"
  echo
  echo "Gerado por \`scripts/perf-audit.sh\` no ambiente local (projeto Compose \`${PROJECT}\`, dados sintéticos)."
  echo
  echo "| Medida | Meta | Resultado |"
  echo "|---|---|---|"
  echo "| Carga | ${SEED_EVENTS} eventos em 12 meses + 2 × ${OLD_LOAD} de 13 e 14 meses + ${OUTBOX_OLD} entregas de 8 dias | semeada em ${seed_s} s |"
  for scenario in window action cursor; do
    echo "| Consulta \`${scenario}\` (${QUERY_RUNS} execuções, com \`AUDIT_QUERIED\`) | p95 ≤ ${QUERY_P95_MS} ms, sem ordenação à parte | p95 ${P95[$scenario]} ms; plano sem Sort: ${PLAN_OK[$scenario]} |"
  done
  echo "| Expurgo e limpeza ao subir | \`COMPLETED\` em até ${JOB_LIMIT_S} s | ${jobs_s} s; restantes antes do corte: ${old_left}; saída antiga restante: ${outbox_old_left} |"
  echo "| Eventos dentro dos 12 meses | nenhum apagado | antes ${inside_before}, depois ${inside_after} |"
  echo "| Entrega (${DELIVERY_WINDOW_S} s a ${DELIVERY_RATE}/s) | ≥ ${DELIVERY_TARGET_PCT}% em até 5 s | ${within5} de ${delivered} (${delivery_pct}%) |"
  echo
  echo "A consulta mede o trabalho de banco da rota (SQL de \`JdbcAuditEventReader\` e gravação de \`AUDIT_QUERIED\` na mesma transação, pelo login de aplicação), porque não há token de administrador antes da U8."
  echo
  echo "Duração das tarefas (\`audit_job_duration_seconds\`):"
  echo
  echo '```'
  echo "${job_durations:-indisponível}"
  echo '```'
  echo
  if [ "${#FAILURES[@]}" -eq 0 ]; then
    echo "**Resultado: todas as metas atendidas.**"
  else
    echo "**Resultado: metas não atendidas:**"
    printf -- '- %s\n' "${FAILURES[@]}"
  fi
} > "$REPORT_FILE"
log "report written to $REPORT_FILE"

if [ "${#FAILURES[@]}" -ne 0 ]; then
  log "${#FAILURES[@]} target(s) missed"
  exit 1
fi
log "all targets met"
