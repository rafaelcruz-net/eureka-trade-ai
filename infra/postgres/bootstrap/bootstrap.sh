#!/bin/sh
# Entry point of the db-bootstrap service (same postgres image as the database). Waits for the DEFINITIVE server to
# accept TCP connections (the temporary server that runs the init scripts of a new volume listens only on the
# socket), then runs 10-roles.sql as the superuser. Idempotent; a non-zero exit stops `docker compose up`.
# The integration-test base runs this same script inside the Testcontainers PostgreSQL.
set -eu

: "${POSTGRES_HOST:=postgres}"
: "${POSTGRES_PORT:=5432}"
: "${POSTGRES_USER:?POSTGRES_USER must be set}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"
: "${DB_OWNER_USER:?DB_OWNER_USER must be set}"
: "${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD must be set}"
: "${DB_USER:?DB_USER must be set}"
: "${DB_PASSWORD:?DB_PASSWORD must be set}"
: "${DB_RETENTION_USER:?DB_RETENTION_USER must be set}"
: "${DB_RETENTION_PASSWORD:?DB_RETENTION_PASSWORD must be set}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WAIT_SECONDS="${BOOTSTRAP_WAIT_SECONDS:-60}"
export PGPASSWORD="$POSTGRES_PASSWORD"

waited=0
until pg_isready -q -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB"; do
  if [ "$waited" -ge "$WAIT_SECONDS" ]; then
    echo "db-bootstrap: PostgreSQL did not accept TCP connections within ${WAIT_SECONDS}s" >&2
    exit 1
  fi
  sleep 2
  waited=$((waited + 2))
done

psql -X -v ON_ERROR_STOP=1 -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f "$SCRIPT_DIR/10-roles.sql"
