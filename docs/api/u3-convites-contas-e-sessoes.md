# U3 — Convites, contas e sessões

Nota de operação da Unit `u3-identity-access` (histórias US7.1, US1.1, US1.3 e US1.5). O desenho está em [functional-spec.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/functional-design/functional-spec.md) e os contratos C1, C3, C8, C9 e C17 em [contract-summary.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md). As especificações geradas do código ficam em [`openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml) e [`openapi-admin.yaml`](../../apps/api/openapi/openapi-admin.yaml).

## O que a U3 entrega

| Rota | Quem usa | O que faz |
|---|---|---|
| `GET /api/v1/invites/{code}` | Convidado, sem sessão | Estado do convite, validade, termos e aviso de risco vigentes. Limite de 10 por minuto por IP. Código mal formado e desconhecido recebem o mesmo `404` |
| `POST /api/v1/auth/verify` com `inviteCode` e `acceptedTermsVersion` | Convidado | Cria a conta numa única transação: pessoa, carteira, convite usado, aceite dos termos, `ACCOUNT_CREATED` e sessão |
| `GET /api/v1/account` | Convidado com sessão | Carteira abreviada, provedor, fim absoluto da sessão, termos aceitos e `realMode: UNAVAILABLE` |
| `GET /api/v1/admin/invites` | Administrador | Lista paginada, sem o código e sem quem emitiu |
| `POST /api/v1/admin/invites` | Administrador | Emite o convite. O código completo vem só na resposta `201` |
| `POST /api/v1/admin/invites/{inviteId}/revoke` | Administrador | Revoga um convite pendente |

As telas do convidado são `/convite/[codigo]` (entrada com convite) e `/conta` (tela Conta). As telas do administrador ficam para a U8, que usa a interface `IdentityAdministration` e as rotas acima.

## Procedimento do administrador: código perdido

O banco guarda só o resumo SHA-256 do código e os 4 últimos caracteres. O registro de idempotência guarda a resposta sem o código. Por isso o código nunca pode ser recuperado:

1. Repetir a emissão com a mesma `Idempotency-Key` devolve o mesmo convite **sem** o código. Isso acontece, por exemplo, quando a resposta `201` se perdeu na rede.
2. Na lista de convites, reconheça o convite pelo final do código (`codeSuffix`), pelo tenant e pela validade.
3. Revogue esse convite (`POST /api/v1/admin/invites/{inviteId}/revoke`).
4. Emita outro convite com uma chave de idempotência nova e entregue o link `/convite/<código>` fora do sistema.

A tela da U8 deve escrever esse procedimento junto do botão de emissão.

## Termos e aviso de risco

- Os textos ficam em `apps/api/src/main/resources/terms/<versão>/` (`terms.txt`, `risk-notice.txt` e `version.properties`), em UTF-8 com fim de linha LF. Exatamente uma versão tem `current=true`. Sem isso, a API não sobe e o log diz o motivo.
- A versão `v1` é **provisória, pendente de revisão jurídica**. Antes do primeiro convite real, o texto revisado entra como uma **versão nova**: uma pasta `v2` com `current=true`, e a `v1` passa a `current=false`. A versão `v1` nunca é editada, porque as contas guardam a versão que aceitaram e a tela Conta mostra o texto daquela versão.
- Quem tentar criar a conta com a versão antiga depois da troca recebe `422 TERMS_NOT_ACCEPTED`. A tela recarrega os termos novos e pede os aceites de novo.
- Os textos passam pelo catálogo de termos proibidos da U4 (`apps/web/src/content/prohibited-terms.json`), no backend (`TermsProhibitedTermsTest`) e no frontend (`prohibited-terms.test.ts`).

## Checklist de liberação dos convites reais

Antes de emitir o primeiro convite para uma pessoa real, além do G1:

- [ ] Texto final dos termos e do aviso de risco revisado pelo jurídico e publicado como versão nova (seção acima).
- [ ] Regra de retenção de convites, aceites e carteiras aprovada no inventário de dados (**SP-3**). A U3 não apaga nada automaticamente (NFR7.2).
- [ ] Proxy ou balanceador à frente do app web, se existir, sem registrar o caminho completo de `/convite/*`: registra o modelo `/convite/[codigo]` ou omite o caminho (NFR6.4).
- [ ] Limitador da consulta do convite em armazenamento compartilhado antes de rodar mais de uma instância da `api` (NFR2.3).
- [ ] Perfis `test` e `loadtest` ausentes do ambiente com convidados.

## Mudança preparada para o `scripts/verify.sh`

O agente não edita o `scripts/verify.sh`. O responsável do projeto aplica esta mudança para o smoke da U3 entrar no comando de verificação. O NFR3.4 e a parte de log do NFR6.4 dependem dessa ação, e o ponto de controle da U3 só é aprovado depois de `bash scripts/verify.sh` passar com o smoke da U3.

**Situação em 2026-11-08:** a mudança já foi aplicada à mão pelo responsável do projeto no `scripts/verify.sh`. Os trechos abaixo foram atualizados para bater com o script como ele ficou. A linha do Playwright inclui também `e2e/u9-dashboard` (smoke do painel da U9), e a varredura de logs procura o código do convite junto dos outros valores sensíveis, nos logs de `api`, `worker` e `admin`.

1. Depois da geração das carteiras, gere o código do convite com a ferramenta de teste e exporte-o:

   ```bash
   log "generating the smoke invitation code (test-scope tool)"
   SMOKE_INVITE_CODE="$(sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml exec:java \
     -Dexec.classpathScope=test \
     -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool \
     -Dexec.args="--invite-code" | tr -d '\r\n')"
   export SMOKE_INVITE_CODE
   [ "${#SMOKE_INVITE_CODE}" -eq 43 ] || { log "invitation code generation failed"; exit 1; }
   ```

   O `| tr -d '\r\n'` tira o fim de linha da saída: no Git Bash do Windows, o `println` da ferramenta deixa um `\r` no valor capturado, e o código ficaria com 44 caracteres e falharia na conferência de tamanho. O `test-compile` da geração da carteira já roda antes, então este passo não compila de novo. O overlay `infra/docker-compose.test.yml` já passa `SMOKE_INVITE_CODE` para a `api`, e o `SmokeInviteSeeder` (perfil `test`) cria o convite na partida.

2. Na linha do Playwright, acrescente `e2e/u9-dashboard` e `e2e/u3-identity-access` e passe o código ao teste:

   ```bash
   SMOKE_WALLET_FILE="$KEY_FILE" \
     SMOKE_ADMIN_WALLET_FILE="$ADMIN_KEY_FILE" \
     SMOKE_FEEDBACK_MARKER="$SMOKE_FEEDBACK_MARKER" \
     ADMIN_BASE_URL="http://localhost:3001" \
     WEB_BASE_URL="http://localhost:3000" \
     API_BASE_URL="http://localhost:8080" \
     MANAGEMENT_BASE_URL="http://localhost:8081" \
     SMOKE_INVITE_CODE="$SMOKE_INVITE_CODE" \
     DATABASE_URL="postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@127.0.0.1:5432/${POSTGRES_DB:-memetrade}" \
     "${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl e2e/u5-bots-decision e2e/u8-admin-feedback e2e/u9-dashboard e2e/u3-identity-access
   ```
3. Na varredura dos logs de `api`, `worker` e `admin`, acrescente o código do convite à lista de valores procurados:

   ```bash
   for needle in "$SMOKE_BETA_WALLET_ADDRESS" "$ADMIN_WALLET_ADDRESSES" "$SMOKE_FEEDBACK_MARKER" "$SMOKE_INVITE_CODE"; do
   ```

   Qualquer ocorrência encerra o script com saída diferente de zero.

## Rollback

A migração `0007` só acrescenta: tabelas `invite` e `terms_acceptance`, índices, a restrição renomeada `uq_wallet_address` e o tenant `Beta`. Voltar as imagens da `api` e do `web` para o commit anterior à U3 funciona sobre o banco migrado. O `MigrationsIT` roda as consultas de entrada e sessão da U1 sobre o esquema novo. O teste manual do ponto de controle sobe as imagens anteriores sobre o volume migrado e roda `bash scripts/verify.sh` até sair 0.
