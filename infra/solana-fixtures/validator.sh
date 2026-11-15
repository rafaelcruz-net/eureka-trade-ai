#!/bin/sh
# Start of the local Solana validator of the REAL Compose (261113-trading-real-bot U4, infrastructure-specification.md
# "Validador"). Loads every program and account recorded by infra/solana-fixtures/record.sh, read from the folder
# mounted read-only at /fixtures:
#   programs/<program address>.so    -> --bpf-program <address> <file>
#   accounts/<account address>.json  -> --account <address> <file>
# With no recorded file (before the first recording) the validator starts exactly as before, so the bot wallet spec of
# the U1 keeps running. Reset on every start (team.md "Determinismo": no devnet, mainnet nor real key in the CI).
set -eu

FIXTURES="${FIXTURES:-/fixtures}"
set -- --reset --quiet --rpc-port 8899 --ledger /tmp/ledger

for program in "$FIXTURES"/programs/*.so; do
  [ -f "$program" ] || continue
  address="$(basename "$program" .so)"
  set -- "$@" --bpf-program "$address" "$program"
done

for account in "$FIXTURES"/accounts/*.json; do
  [ -f "$account" ] || continue
  address="$(basename "$account" .json)"
  set -- "$@" --account "$address" "$account"
done

exec solana-test-validator "$@"
