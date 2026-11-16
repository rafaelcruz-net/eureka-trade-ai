#!/usr/bin/env bash
# One-time recording of the swap fixtures of the REAL Compose (261113-trading-real-bot U4, infrastructure-specification.md
# "Gravação na mesma janela"). Run ONCE, by a developer, WITH network, OUTSIDE the tests; never in the CI and never by
# scripts/verify.sh. It reads public data only: the Jupiter quote and swap instructions for the deterministic bot wallet
# of the test user, and the mainnet accounts those instructions name. It signs nothing, sends nothing and needs no key.
#
# Usage (from the repository root):
#   bash infra/solana-fixtures/record.sh --mint <token mint> --bot-wallet <bot wallet address> [--rpc <mainnet RPC URL>]
#
#   --mint        a token traded in ONE direct hop on a CPMM pool against wrapped SOL (onlyDirectRoutes)
#   --bot-wallet  the address of the bot wallet the REAL Compose creates with KMS_SIM_SEED (read it once with
#                 GET /api/v1/real/bot-wallet after a first run of the bot wallet spec)
#   --rpc         a mainnet RPC (default https://api.mainnet-beta.solana.com); only reads accounts
#   --dexes       the Jupiter pool labels allowed for the hop (default "Raydium CP": a constant-product pool)
#
# Needs: curl, jq and the solana CLI (solana account, solana program dump). Writes, under infra/solana-fixtures/:
#   jupiter/mappings/*.json and jupiter/__files/*.json  the recorded answers served by the jupiter-stub (WireMock)
#   programs/<address>.so                               every executable account the instructions name
#   accounts/<address>.json                             every other account the instructions name (same slot window)
#   recorded.env                                        RECORDED_MINT, RECORDED_BUY_LAMPORTS, RECORDED_SELL_UNITS,
#                                                       RECORDED_BOT_WALLET and RECORDED_SLOT, read by the e2e spec
# Then: check that both legs execute in a simulation against these accounts (step 4 below), review the diff and commit
# the files. The cloned accounts use the real mint, which team.md allows only for the swaps on the local validator.
set -euo pipefail
# Git Bash (MSYS) rewrites any argument that looks like a POSIX path before a native Windows program sees it: the
# "/quote" given to jq.exe became "C:/Program Files/Git/quote" in the first recording. Off for this whole script.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

JUPITER="https://lite-api.jup.ag/swap/v1"
WSOL="So11111111111111111111111111111111111111112"
USDC="EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
BUY_LAMPORTS=50000000
SLIPPAGE_BPS=200
MAX_ACCOUNTS=40
RPC="https://api.mainnet-beta.solana.com"
MINT=""
BOT_WALLET=""
# The design asks for a CONSTANT-PRODUCT pool: its swap depends only on the recorded reserves. Oracle-priced
# ("proprietary") pools such as Scorch, SolFi or HumidiFi read a price their market maker pushes every slot and refuse
# it when stale, which a local validator started later can never satisfy. Jupiter labels, comma-separated.
DEXES="Raydium CP"

while [ $# -gt 0 ]; do
  case "$1" in
    --mint) MINT="$2"; shift 2 ;;
    --dexes) DEXES="$2"; shift 2 ;;
    --bot-wallet) BOT_WALLET="$2"; shift 2 ;;
    --rpc) RPC="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$MINT" ] && [ -n "$BOT_WALLET" ] || { echo "--mint and --bot-wallet are required" >&2; exit 2; }
for tool in curl jq solana; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }
done
# jq.exe on Windows ends its lines with CRLF; a carriage return glued to an address makes the solana CLI read it as an
# unknown signer source, so every jq output loses it here.
jq() { command jq "$@" | tr -d '\r'; }

HERE="$(cd "$(dirname "$0")" && pwd)"
# With the path conversion off, a native Windows program (solana.exe) cannot open "/c/Users/...": the paths it
# receives go in the Windows form ("C:/Users/..."). Elsewhere cygpath is absent and the path stays as it is.
NATIVE_HERE="$HERE"
if command -v cygpath >/dev/null; then
  NATIVE_HERE="$(cygpath -m "$HERE")"
fi
STUB="$HERE/jupiter"
mkdir -p "$STUB/mappings" "$STUB/__files" "$HERE/programs" "$HERE/accounts"

# The accounts the validator already has: system, token, token-2022, associated token, compute budget, memo, sysvars.
BUILTIN=(
  11111111111111111111111111111111
  TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
  TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb
  ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL
  ComputeBudget111111111111111111111111111111
  MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr
  SysvarRent111111111111111111111111111111111
  SysvarC1ock11111111111111111111111111111111
  Sysvar1nstructions1111111111111111111111111
)

quote() { # inputMint outputMint amount slippage maxAccounts [dexes]
  local dexes=""
  [ -z "${6:-}" ] || dexes="&dexes=$(printf '%s' "$6" | sed 's/ /%20/g')"
  curl -fsS "$JUPITER/quote?inputMint=$1&outputMint=$2&amount=$3&slippageBps=$4&maxAccounts=$5&restrictIntermediateTokens=true&swapMode=ExactIn&onlyDirectRoutes=true$dexes"
}

instructions() { # quote json
  jq -n --argjson quote "$1" --arg user "$BOT_WALLET" '{
      quoteResponse: $quote, userPublicKey: $user, wrapAndUnwrapSol: true, dynamicComputeUnitLimit: true,
      prioritizationFeeLamports: {priorityLevelWithMaxLamports: {maxLamports: 1000000, priorityLevel: "medium"}}}' \
    | curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @- "$JUPITER/swap-instructions"
}

mapping() { # name method path query-json body-file
  jq -n --arg method "$2" --arg path "$3" --argjson query "$4" --arg file "$5" '{
      request: ({method: $method, urlPath: $path} + (if $query == {} then {} else {queryParameters: $query} end)),
      response: {status: 200, headers: {"Content-Type": "application/json"}, bodyFileName: $file}}' \
    > "$STUB/mappings/$1.json"
}

echo "1/4 Jupiter: quote and instructions of the buy and the sell (one direct hop)"
BUY_QUOTE="$(quote "$WSOL" "$MINT" "$BUY_LAMPORTS" "$SLIPPAGE_BPS" "$MAX_ACCOUNTS" "$DEXES")"
SELL_UNITS="$(jq -r '.outAmount' <<<"$BUY_QUOTE")"
[ "$(jq '.routePlan | length' <<<"$BUY_QUOTE")" = "1" ] || { echo "the buy route is not one hop" >&2; exit 1; }
echo "  pool: $(jq -r '.routePlan[0].swapInfo.label + " " + .routePlan[0].swapInfo.ammKey' <<<"$BUY_QUOTE")"
BUY_SWAP="$(instructions "$BUY_QUOTE")"
SELL_QUOTE="$(quote "$MINT" "$WSOL" "$SELL_UNITS" "$SLIPPAGE_BPS" "$MAX_ACCOUNTS" "$DEXES")"
SELL_SWAP="$(instructions "$SELL_QUOTE")"
PROBE_QUOTE="$(quote "$WSOL" "$USDC" 1000000 50 20)"
# Jupiter always suggests lookup tables; the server ignores them and builds the message with static keys only
# (U4-NFR1.5). What matters is that the static message fits in 1232 bytes: about 30 distinct accounts or fewer.
for leg in BUY_SWAP SELL_SWAP; do
  distinct="$(jq '[.computeBudgetInstructions[]?, .setupInstructions[]?, .swapInstruction, .cleanupInstruction
      | select(. != null) | .programId, (.accounts[]?.pubkey)] | unique | length' <<<"${!leg}")"
  echo "  $leg: $distinct distinct static accounts"
  [ "$distinct" -le 32 ] \
    || echo "warning: $leg names $distinct accounts; the static message may not fit in 1232 bytes; pick another mint or pool" >&2
done

echo "2/4 WireMock: the recorded answers"
printf '%s' "$BUY_QUOTE" >"$STUB/__files/quote-buy.json"
printf '%s' "$SELL_QUOTE" >"$STUB/__files/quote-sell.json"
printf '%s' "$PROBE_QUOTE" >"$STUB/__files/quote-probe.json"
printf '%s' "$BUY_SWAP" >"$STUB/__files/swap-instructions-buy.json"
printf '%s' "$SELL_SWAP" >"$STUB/__files/swap-instructions-sell.json"
mapping quote-buy GET /quote "{\"inputMint\":{\"equalTo\":\"$WSOL\"},\"outputMint\":{\"equalTo\":\"$MINT\"}}" quote-buy.json
mapping quote-sell GET /quote "{\"inputMint\":{\"equalTo\":\"$MINT\"},\"outputMint\":{\"equalTo\":\"$WSOL\"}}" quote-sell.json
mapping quote-probe GET /quote "{\"outputMint\":{\"equalTo\":\"$USDC\"}}" quote-probe.json
jq -n --arg mint "$MINT" '{request: {method: "POST", urlPath: "/swap-instructions",
    bodyPatterns: [{matchesJsonPath: ("$.quoteResponse[?(@.outputMint == \"" + $mint + "\")]")}]},
    response: {status: 200, headers: {"Content-Type": "application/json"}, bodyFileName: "swap-instructions-buy.json"}}' \
  >"$STUB/mappings/swap-instructions-buy.json"
jq -n --arg mint "$MINT" '{request: {method: "POST", urlPath: "/swap-instructions",
    bodyPatterns: [{matchesJsonPath: ("$.quoteResponse[?(@.inputMint == \"" + $mint + "\")]")}]},
    response: {status: 200, headers: {"Content-Type": "application/json"}, bodyFileName: "swap-instructions-sell.json"}}' \
  >"$STUB/mappings/swap-instructions-sell.json"

echo "3/4 Accounts: every account the instructions name, in the same window"
SLOT="$(curl -fsS -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"getSlot","params":[{"commitment":"finalized"}]}' "$RPC" | jq -r '.result')"
mapfile -t ACCOUNTS < <(jq -r '[.computeBudgetInstructions[]?, .otherInstructions[]?, .setupInstructions[]?,
    .swapInstruction, .cleanupInstruction | select(. != null) | .programId, (.accounts[]?.pubkey)] | .[]' \
    <<<"$BUY_SWAP"$'\n'"$SELL_SWAP" | sort -u)
for address in "${ACCOUNTS[@]}" "$MINT"; do
  if [ "$address" = "$BOT_WALLET" ] || printf '%s\n' "${BUILTIN[@]}" | grep -qx "$address"; then
    continue
  fi
  # Only "AccountNotFound" means the swap creates the account; any other error (rate limit, timeout) is retried and,
  # if it persists, stops the recording instead of silently leaving the account out.
  info=""
  for attempt in 1 2 3 4 5; do
    if info="$(solana account "$address" --url "$RPC" --output json 2>"$HERE/.account-error")"; then
      break
    fi
    info=""
    if grep -q 'AccountNotFound' "$HERE/.account-error"; then
      break
    fi
    echo "  retry $address ($attempt/5): $(head -c 160 "$HERE/.account-error")" >&2
    sleep $((attempt * 3))
  done
  if [ -z "$info" ]; then
    if grep -q 'AccountNotFound' "$HERE/.account-error"; then
      echo "  skip $address (does not exist yet: created by the swap, e.g. a token account of the bot wallet)"
      continue
    fi
    echo "could not read $address from $RPC; use another RPC with --rpc and record again" >&2
    rm -f "$HERE/.account-error"
    exit 1
  fi
  if [ "$(jq -r '.account.executable' <<<"$info")" = "true" ]; then
    solana program dump "$address" "$NATIVE_HERE/programs/$address.so" --url "$RPC" >/dev/null
    echo "  program $address"
    # e_flags of the ELF (byte 48) is the SBPF version. The validator of the REAL Compose (agave v2.2) runs up to v2;
    # a v3 program loads but every call fails with "Program is not deployed" (the first recording hit this).
    sbpf="$(od -An -tu1 -j48 -N1 "$HERE/programs/$address.so" | tr -d ' \r\n')"
    [ "${sbpf:-0}" -lt 3 ] \
      || echo "warning: $address is SBPF v$sbpf, which the local validator cannot run; pick another mint or pool" >&2
  else
    printf '%s' "$info" >"$HERE/accounts/$address.json"
    echo "  account $address"
  fi
done

rm -f "$HERE/.account-error"

# The places of the mint: byte 44 of the SPL mint account just recorded (the api publishes the mint with them).
DECIMALS="$(jq -r '.account.data[0]' "$NATIVE_HERE/accounts/$MINT.json" | base64 -d | od -An -tu1 -j44 -N1 | tr -d ' \r\n')"
[ -n "$DECIMALS" ] || { echo "could not read the decimals of $MINT" >&2; exit 1; }

cat >"$HERE/recorded.env" <<EOF
RECORDED_MINT=$MINT
RECORDED_DECIMALS=$DECIMALS
RECORDED_BUY_LAMPORTS=$BUY_LAMPORTS
RECORDED_SELL_UNITS=$SELL_UNITS
RECORDED_BOT_WALLET=$BOT_WALLET
RECORDED_SLOT=$SLOT
EOF

echo "4/4 Check: start the REAL Compose (the validator loads these files) and run the swap spec:"
echo "    RECORDED_FIXTURES=infra/solana-fixtures/recorded.env bash scripts/verify.sh"
echo "  Both legs must reach CONFIRMED; if a leg fails, record again with another mint or pool."
