#!/usr/bin/env bash
# Runs once, when the postgres container initialises an empty pgdata volume, as the superuser.
# Since U2 it creates NO login and transfers NO ownership: logins, schema ownership and default privileges belong
# only to the db-bootstrap service (infra/postgres/bootstrap/10-roles.sql), which runs on every `docker compose up`,
# on new volumes and on volumes created by U1 alike. Here the database only stops accepting connections from PUBLIC.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v app_db="$POSTGRES_DB" <<'EOSQL'
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
EOSQL
