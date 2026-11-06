# U8 — Administração e feedback (C6, C8, C16, C17)

Documento técnico da Unit `u8-admin-feedback`: a entrada do administrador, o estado do sistema, as telas de convite, o kill switch global e o feedback dos convidados. Tudo continua em PAPER: o kill switch só pausa e retoma bots pela interface da U5 (C16) e nenhum caminho constrói, assina ou envia transação. As especificações REST geradas do código ficam em [`apps/api/openapi/openapi-admin.yaml`](../../apps/api/openapi/openapi-admin.yaml) (rotas administrativas) e [`apps/api/openapi/openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml) (`POST /api/v1/feedback`); os contratos atualizados estão no [`contract-summary.md`](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md) (C6, C8, C16, C17 e C18).

## Módulos e fronteiras

| Módulo | Pacotes | Tabelas | Quem usa de fora |
|---|---|---|---|
| `administration` | `api`, `domain`, `infra`, `web` | `kill_switch_state` (uma linha), `kill_switch_change` (histórico) | Ninguém: só as rotas `/api/v1/admin/kill-switch`, `/system-status` e `/tenants` |
| `feedback` | `api`, `domain`, `infra`, `web` | `feedback_entry` | Ninguém: `POST /api/v1/feedback` e `GET /api/v1/admin/feedback` |
| `identity` (acréscimos) | `domain/login`, `web` | `user_session` (papel `ADMIN` aceito) | `IdentityAdministration.tenantNames` e `maskedAdministratorWallet` |

A `administration` lê as contagens só por interfaces públicas: `BotAdministration` (U5), `RejectionCounts` (U6), `AuditBacklog` (U2), `IdentityAdministration` (U3) e a saúde das fontes (U4). Nenhum módulo lê tabela de outro (`U8ArchitectureTest`, 9 regras).

## Rotas

| Método | Rota | Quem | Observação |
|---|---|---|---|
| POST | `/api/v1/admin/auth/challenge` | Público | Desafio com domínio do app do administrador; carteira fora da lista recebe resposta genérica |
| POST | `/api/v1/admin/auth/verify` | Público | Confere a assinatura (Ed25519 nativo); abre sessão `ADMIN` de no máximo 8 h; cookie `mt_admin_refresh` com `Path=/api/v1/admin/auth/refresh` |
| POST | `/api/v1/admin/auth/refresh` | Cookie do administrador | Recusa sessão de convidado (`NOT_ALLOWED`) |
| POST | `/api/v1/admin/auth/logout` | `ADMIN` | Revoga a sessão e apaga o cookie |
| GET | `/api/v1/admin/system-status` | `ADMIN` | Só contagens; cada parte com tempo-limite de 500 ms vira `unknown` (`TIMEOUT`/`UNAVAILABLE`), nunca zero |
| GET | `/api/v1/admin/tenants` | `ADMIN` | Lista de tenants para a emissão de convite |
| GET, PUT | `/api/v1/admin/kill-switch` | `ADMIN` | PUT com `Idempotency-Key`, `state` `ON`/`OFF` e motivo de 1 a 500 caracteres |
| GET | `/api/v1/admin/feedback` | `ADMIN` | Paginação por cursor (`limit` de 1 a 100, padrão 20); texto devolvido como texto puro |
| POST | `/api/v1/feedback` | `BETA_USER` | `Idempotency-Key`; tenant e pessoa só do token, corpo fechado (campo extra = 400); 10 por hora por pessoa, 429 com `Retry-After` |

Toda rota está no `route-registry.yml` com o teste negativo que prova a recusa de token de convidado (403 `FORBIDDEN_ROLE` e um único `REQUEST_FORBIDDEN` auditado).

## Entrada do administrador

- **Lista de carteiras:** `ADMIN_WALLET_ADDRESSES` (endereços públicos separados por vírgula). Nenhuma chave privada passa pelo servidor.
- **Escopo do desafio:** o desafio do administrador carrega o domínio de `ADMIN_APP_ORIGIN` (por padrão `localhost:3001`). O verificador do convidado recusa desafio de outro domínio (`UNKNOWN_CHALLENGE`), e o do administrador recusa o do convidado. A assinatura de entrada nunca autoriza operação.
- **Limites:** 5 tentativas por IP e 5 por endereço (`AdminLoginLimiter`), separados dos limites do convidado.
- **CORS:** o `AdminCorsPolicy` vale só para `ADMIN_APP_ORIGIN` (GET, POST, PUT, OPTIONS); credenciais só em `verify`, `refresh` e `logout`.
- **Auditoria:** `ADMIN_LOGIN`, `ADMIN_LOGOUT`, `ADMIN_SESSION_REVOKED` e `LOGIN_REJECTED` com `entry=ADMIN`; o endereço aparece sempre mascarado (4 + 4).

## Kill switch

1. **Gravação (`KillSwitchService`):** numa transação, com `SELECT ... FOR UPDATE` na linha única, valida o motivo, confere a chave de idempotência (resumo SHA-256 do estado e do motivo), grava o estado com `version + 1`, acrescenta a linha em `kill_switch_change` e o evento `KILL_SWITCH_ON`/`KILL_SWITCH_OFF`. Pedir o estado atual não gera histórico.
2. **Aplicação (`KillSwitchApplier`):** único caller de `pauseAll`/`resumeAll` da U5. Pega um lock consultivo de sessão do PostgreSQL (`pg_try_advisory_lock`, pool próprio de uma conexão, espera de até 10 s), aplica até 3 vezes enquanto houver falha e confere a versão antes de gravar o resultado. O token de cerca é o `reconcile_marked_at` gravado com `clock_timestamp()` do banco: uma marcação feita durante a aplicação vence e fica para a reconciliação.
3. **Reconciliação (`KillSwitchReconciler`):** a cada 30 s, com ShedLock `adminKillSwitchReconcile`, aplica sempre o estado gravado, conta divergências e alerta `KILL_SWITCH_RECONCILE_STALE` (> 60 s sem rodada), `KILL_SWITCH_PENDING_STALE` (> 2 min com bots pendentes) e `KILL_SWITCH_DIVERGENCE`. Também roda uma vez no `ApplicationReadyEvent`.
4. **Observação:** métricas `admin.killswitch.state`, `pending_bots`, `reconcile_needed`, `reconcile_divergences_total`, `apply_seconds` e `lift_seconds{outcome}`; indicador de saúde `adminKillSwitch`.

Medido localmente: aplicar o kill switch em 60 bots levou p95 254 ms e máximo 365 ms em 20 execuções (`KillSwitchPerformanceIT`); o pior caso de queda e retomada cabe em 45 s (`KillSwitchCrashIT`).

## Feedback

- **Validação:** tela de uma lista fechada (`FeedbackScreen`) e texto de 1 a 2.000 caracteres; o que é recusado não é gravado.
- **Limite:** um lock consultivo de transação por pessoa (`pg_advisory_xact_lock(0x55384642, hashtext(user_id))`) serializa a contagem da última hora; a 11ª mensagem recebe 429 com `Retry-After`.
- **Privacidade:** o texto nunca vai para log nem para evento; os logs têm só tela, tamanho e resultado.
- **Imutável:** a aplicação só insere e lê. `UPDATE` é recusado por gatilho; `DELETE` só para o grupo `feedback_retention`.
- **Retenção:** `FeedbackPurgeJob` apaga diariamente às 03:00 UTC, em lotes de 1.000, o que tiver mais de 180 dias, pelo login de retenção; grava `FEEDBACK_PURGED` com os totais. Indicador `feedbackPurge` fica `DOWN` (`FEEDBACK_PURGE_STALE`) depois de 48 h sem sucesso.

## App do administrador (`apps/admin`)

Next.js separado, porta `127.0.0.1:3001`, com lockfile próprio e tipos gerados só de `openapi-admin.yaml` (`pnpm --dir apps/admin check:api`). CSP com nonce por requisição, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer` e `Cache-Control: no-store`. O build do convidado não leva nenhuma rota nem texto administrativo (`pnpm --dir apps/web check:bundle`, GuestBundleTest).

## Rollback

1. Checkout do commit anterior.
2. `docker compose up -d --build --remove-orphans`, que remove o contêiner `admin` órfão.
3. `bash scripts/verify.sh`, que precisa sair com código 0.

As tabelas da migração `0011` ficam no banco sem uso, e a versão anterior roda sobre elas (`MigrationsBackwardCompatIT`). Sessões `ADMIN` já gravadas ficam inertes até vencer (no máximo 8 h): a versão anterior não emite nem aceita token `ADMIN`.

**Risco aceito:** se o kill switch estiver ligado no momento do rollback, a pausa continua valendo na U5 (`SystemPauseState`). Sem a U8, ninguém consegue desligá-lo pela tela; os bots ficam pausados até a U8 voltar e o administrador desligar o kill switch. É o lado seguro: nenhuma operação nova acontece.

## Mudanças no `verify.sh` (ação do responsável do projeto)

O `scripts/verify.sh` é protegido e não foi alterado nesta geração de código. Até estas mudanças entrarem, o smoke `e2e/u8-admin-feedback` não roda e a varredura de logs do `admin` e do `worker` não existe. As linhas abaixo usam os números do arquivo atual.

**1. Carteira do administrador.** Depois da linha 13 (`KEY_FILE=...`):

```bash
ADMIN_KEY_FILE="$KEY_DIR/smoke-admin-wallet.json"
SMOKE_FEEDBACK_MARKER="smoke-u8-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
```

Depois da linha 72 (`[ -n "$SMOKE_BETA_WALLET_ADDRESS" ] || ...`):

```bash
sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
  -Dexec.args="$ADMIN_KEY_FILE" >/dev/null
ADMIN_WALLET_ADDRESSES="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' "$ADMIN_KEY_FILE")"
[ -n "$ADMIN_WALLET_ADDRESSES" ] || { log "administrator wallet generation failed"; exit 1; }
```

**2. Endereço para a `api`.** Logo em seguida, antes do `docker compose up` da linha 75:

```bash
export ADMIN_WALLET_ADDRESSES
```

O overlay `infra/docker-compose.test.yml` repassa `ADMIN_WALLET_ADDRESSES: ${ADMIN_WALLET_ADDRESSES:-}` à `api` só nessa execução.

**3. Espera.** Trocar as linhas 84 a 87 por:

```bash
log "waiting for the four services to be healthy (<= ${HEALTH_TIMEOUT_S}s)"
deadline=$((SECONDS + HEALTH_TIMEOUT_S))
until [ "$(service_health postgres)" = healthy ] && [ "$(service_health api)" = healthy ] \
  && [ "$(service_health web)" = healthy ] && [ "$(service_health admin)" = healthy ]; do
```

**4. Variáveis do Playwright** e **7. Smoke.** Trocar as linhas 111 a 116 por:

```bash
SMOKE_WALLET_FILE="$KEY_FILE" \
  SMOKE_ADMIN_WALLET_FILE="$ADMIN_KEY_FILE" \
  SMOKE_FEEDBACK_MARKER="$SMOKE_FEEDBACK_MARKER" \
  WEB_BASE_URL="http://localhost:3000" \
  ADMIN_BASE_URL="http://localhost:3001" \
  API_BASE_URL="http://localhost:8080" \
  MANAGEMENT_BASE_URL="http://localhost:8081" \
  DATABASE_URL="postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@127.0.0.1:5432/${POSTGRES_DB:-memetrade}" \
  "${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl e2e/u8-admin-feedback
```

**5. Varredura de logs.** Trocar as linhas 118 a 122 por:

```bash
log "scanning the api, worker and admin logs for full wallet addresses and the feedback text (NFR7.3)"
SERVICE_LOGS="$("${COMPOSE[@]}" logs --no-color api worker admin)"
for needle in "$SMOKE_BETA_WALLET_ADDRESS" "$ADMIN_WALLET_ADDRESSES" "$SMOKE_FEEDBACK_MARKER"; do
  if printf '%s' "$SERVICE_LOGS" | grep -qF "$needle"; then
    log "a full wallet address or the feedback text appeared in the logs"
    exit 1
  fi
done
```

A mensagem de log não repete o valor encontrado. O marcador só entra no texto do comentário; o motivo do kill switch usado pelo smoke não o contém.

**6. Limpeza.** Na função `cleanup`, trocar a linha 33 por:

```bash
    "${COMPOSE[@]}" logs --no-color --tail 80 api worker admin >&2 || true
```

O `rm -rf "$KEY_DIR"` da linha 36 já apaga as duas chaves, porque `ADMIN_KEY_FILE` fica dentro de `$KEY_DIR`; a mensagem da linha 37 continua valendo.

Também vale atualizar o comentário do topo (linhas 2 a 5) para citar o `admin`, a segunda carteira e o marcador.

## Mudanças no `ci.yml` (ação do responsável do projeto)

O hook do framework recusou a edição de `.github/workflows/ci.yml` nesta geração de código (o workflow roda o `verify.sh`). As mudanças do `security.yml` e do `dependabot.yml` entraram e são conferidas pelo `WorkflowsTest#theAdministratorAppIsAuditedAndUpdatedLikeTheGuestApp`. Para o `ci.yml`:

**Job `frontend`:** trocar o último passo por:

```yaml
      - name: Jest with the 80% line floor
        run: pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/features/feedback src/lib src/middleware.test.ts
      # U8 (ADR-009): the guest build carries no administrative route nor the administrative specification.
      - run: pnpm --dir apps/web build
      - name: GuestBundleTest
        run: pnpm --dir apps/web check:bundle
```

**Job novo `admin`** (logo depois do `frontend`):

```yaml
  admin:
    # U8 (cicd-pipeline.md): the administrator app, installed from its own lockfile.
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: pnpm/action-setup@a7487c7e89a18df4991f7f222e4898a00d66ddda # v4.1.0
        with:
          package_json_file: apps/admin/package.json
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0
        with:
          node-version-file: .nvmrc
          cache: pnpm
          cache-dependency-path: apps/admin/pnpm-lock.yaml
      - run: pnpm --dir apps/admin install --frozen-lockfile
      - run: pnpm --dir apps/admin lint
      - run: pnpm --dir apps/admin format:check
      - run: pnpm --dir apps/admin typecheck
      # Includes the security headers (CSP with nonce) and the contrast of the theme (middleware.test.ts, tokens.test.ts).
      - name: Jest with the 80% line floor
        run: pnpm --dir apps/admin exec jest --coverage
      - run: pnpm --dir apps/admin build
```

O job herda `permissions: contents: read` do topo do workflow.

**Job `contract`:** acrescentar no fim:

```yaml
      # U8 (R-06 of the Infrastructure Design): the administrator app has its own lockfile and its own types, generated
      # only from openapi-admin.yaml.
      - run: pnpm --dir apps/admin install --frozen-lockfile
      - name: Generated administrator types equal schema.d.ts
        run: pnpm --dir apps/admin check:api
```

**Job `e2e`:** `needs: [backend, frontend, contract, smoke, admin]`.

No job `smoke`, o `admin` é construído pelo `docker compose up -d --build` do `verify.sh`; nada muda além das mudanças acima no script.
