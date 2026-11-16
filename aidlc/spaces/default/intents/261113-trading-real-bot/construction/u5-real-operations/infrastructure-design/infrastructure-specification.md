# Especificação de infraestrutura — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes:
- `construction/u5-real-operations/nfr-design/`: componentes, CI com o SHA da imagem, redação da trilha, restauração;
- `construction/u4-real-execution/infrastructure-design/infrastructure-specification.md` (padrão);
- os arquivos atuais, conferidos na revisão da iteração 1:
  - `docker-compose.yml` e `infra/docker-compose.real.yml`;
  - `scripts/verify.sh` (linhas 74 e 110);
  - `ComposeFilesTest`, `RuntimeSettingsGuard`, `application.yml`, `AuditPropertiesTest`, `U4SettingsTest`;
  - os Dockerfiles;
  - `DepositScanner`;
  - as migrações 0005 e 0016;
  - `apps/web/e2e/real-bot-wallet/local-validator.ts`;
- `infrastructure-design-questions.md` (Q1 a Q4).

## Compute e configuração [Q1]

| Item | Mudança |
|---|---|
| Worker (`docker-compose.yml`) | `SCHEDULER_POOL_SIZE` de 19 para 25; `DB_POOL_MAX_SIZE` de 27 para 33; comentário da conta: 43 − 14 + 33 = 62 de 100; com o TxSigner no Compose do REAL, 72 de 100 |
| API (`docker-compose.yml`) | `SCHEDULER_POOL_SIZE` com mínimo 22 |
| `application.yml` | padrão de `SCHEDULER_POOL_SIZE` de acordo com as faixas novas |
| `RuntimeSettingsGuard` | constantes das faixas: worker com scheduler de 25 a 32 e conexões de 33 a 48; API com mínimo de 22 |
| Testes | `ComposeFilesTest`, `AuditPropertiesTest`, `U4SettingsTest` e o teste de contagem dos métodos `@Scheduled` |
| Dockerfiles da API e do TxSigner | `ARG BUILD_SHA=` e `ENV MEMETRADE_BUILD_SHA=$BUILD_SHA`; sem o argumento, o valor fica vazio e os portões ficam `CI_UNREACHABLE` |
| Worker na nuvem | ver "Rede e segredos" |

## SHA do commit nas imagens (ajuste da revisão)

Hoje a CI não roda `docker build` direto: a API sai de `docker compose up -d --build` (`verify.sh`, linha 110) e o TxSigner de `docker build` no `verify.sh` (linha 74).

| Arquivo | Mudança | Quem aplica |
|---|---|---|
| `docker-compose.yml` | `build.args: { BUILD_SHA: ${BUILD_SHA:-} }` nos serviços que constroem `memetrade-api` | esta Unit |
| `scripts/verify.sh` | `docker build --build-arg BUILD_SHA="${BUILD_SHA:-}" ...` no TxSigner | o responsável (texto pronto) |
| `.github/workflows/ci.yml` | `env: BUILD_SHA: ${{ github.sha }}` nos jobs `smoke` e `e2e` | o responsável (texto pronto) |

No local, sem `BUILD_SHA`, as imagens saem com o valor vazio. Isso não importa, porque fora da mainnet vale o `ConfiguredCiStatus`.

## Rede e segredos

- O worker sai para `api.github.com` em HTTPS (443) pela NAT que já existe. A API não sai.
- **Segredo:** `github-actions-read-token`, no Secrets Manager, com política só para o papel do worker e troca a cada 90 dias pelo runbook.
- **Tarefa do worker no Terraform:** ela ainda não existe; está pendente desde a U2. O segredo é criado agora no módulo de segredos, sem consumidor. Quando a tarefa do worker for criada (Environment Provisioning), ela recebe o segredo, as variáveis e o papel com a leitura dele.
- No local e nos testes não há token: vale o `ConfiguredCiStatus`.
- **Conexões na nuvem:** API até 50, worker até 48, retenções e trava 4, migração 2, num total de 104. O padrão do `db.t4g.micro` é cerca de 112, ainda acima. Se o limite apertar, a troca de classe ou um grupo de parâmetros fica registrada no runbook.

## Banco: migração 0021, só de expansão

**Tabelas novas:** `real_reconciliation_run`, `real_divergence` (com a lista de compensações e `last_run_id`), `real_kill_switch` (uma linha), `real_pending_txsigner_command` e `real_restore_marker`. Todas com a matriz de privilégios no padrão da U3 e da U4.

**`real_release` (ajustes da revisão):**
- **Pré-condição.** Antes de tudo, um `sqlCheck` exige zero duplicatas não revogadas por (usuário, rede). Com duplicata, a migração para e não muda nada; o runbook manda revogar a mais antiga pela rota do administrador e rodar de novo. Hoje não existe liberação fora do ambiente local.
- **Colunas novas:** `state` com `DEFAULT 'QUARANTINE'`, mais `blocked_reason` e `gates_checked_at`.
- **Backfill:** `REVOKED` com `revoked_at`; `ACTIVE` com o efeito já passado; `QUARANTINE` nas demais.
- **Compatibilidade com as imagens antigas** (durante a implantação e no rollback): um gatilho `BEFORE UPDATE` põe `state = 'REVOKED'` sempre que `revoked_at` deixa de ser NULL.
- **Índice único parcial** em (usuário, rede) `WHERE revoked_at IS NULL`. Ele não depende de `state`, então uma imagem antiga nunca fica presa nele.

**Colunas anuláveis:**
- `bot_wallet_account.main_wallet_address` e `public_address`, com o CHECK de Base58 aceitando NULL;
- `consent_challenge.message`;
- as colunas de transação e mensagem de `withdrawal`.

**Redação do saque:**
- `withdrawal` ganha `redacted_at`.
- O CHECK `withdrawal_signed_ck`, da 0016, é trocado: de `SIGNED` em diante, ele exige `signed_transaction` e `message_digest` não nulos **ou** `redacted_at` preenchido.
- O rollback desse changeset devolve o CHECK original, mas só quando não há linha redigida; havendo, para com uma mensagem. Nesse caso, o runbook manda manter o CHECK novo e voltar só as imagens, que funcionam com ele.

**Enums ampliados:**
- `PauseReason` ganha `RESTORE` e `RESTORE_CHECK_PENDING`;
- os CHECKs de `RealRejectionDetail` das migrações 0018 e 0019 aceitam os detalhes novos;
- `decision.mode` aceita `REAL`.

**Trilha:**
- o gatilho `audit_event_reject_change` é trocado pela versão com a exceção de redação;
- a função `redact_real_wallet_audit(address)` é criada como `SECURITY DEFINER`, com `REVOKE EXECUTE ... FROM PUBLIC` e `GRANT EXECUTE` só ao papel da aplicação;
- dois índices parciais novos: `audit_event(bot_wallet_address)` e a expressão do `payload` do outbox.

**Rollback por changeset:** o da trilha devolve o gatilho original da 0005 e apaga a função.

**Testes de privilégios e gatilhos.** Conferem:
- a redação permitida;
- um UPDATE de outra coluna recusado;
- a aplicação sem UPDATE na trilha;
- `PUBLIC` sem `EXECUTE` na função;
- o gatilho de `revoked_at`;
- a pré-condição de duplicatas;
- o CHECK novo do saque.

## Local e ponta a ponta [Q2] (ajustes da revisão)

**Ferramenta de teste.** O `local-validator.ts` ganha, só no código de teste e sem SDK, montagem de transação com várias instruções. Isso segue o padrão que ele já usa no `transfer`, com a chave da carteira principal de teste gerada pelo Ed25519 do Node:
- `createAccount` (System) para a conta do mint, com a chave do mint gerada na hora;
- `InitializeMint2` (Token, instrução 20, 6 casas, autoridade a carteira principal de teste);
- `CreateIdempotent` (Associated Token, instrução 1) para a conta de token da carteira do bot;
- `MintTo` (Token, instrução 7).

A carteira principal de teste paga e assina tudo; o mint também assina a criação da conta dele.

**Spec `apps/web/e2e/real-ops/divergence.spec.ts`:**
1. Monta o mint e manda tokens para a conta de token da carteira do bot.
2. Espera a divergência e a pausa `DIVERGENCE`. Essa transação não move SOL da carteira do bot, então o `DepositScanner` apenas avança o cursor sobre ela. Até lá, a reconciliação pode dar `DEFERRED` (entrada não lida) por no máximo um ciclo; depois dá `DIVERGENT` no mint de teste. O spec espera até 90 s.
3. Pela entrada administrativa, compensa a quantidade medida.
4. Espera a nova reconciliação limpa e a despausa.
5. Liga o kill switch, confere a recusa de uma ordem nova e desliga.

**Configuração do teste.** Vai em `infra/docker-compose.real.yml`, no serviço do worker:
- `MEMETRADE_REAL_OPS_RECONCILIATION_INTERVAL=PT15S`;
- `MEMETRADE_REAL_OPS_CI=GREEN`.

O `ConfiguredCiStatus` é recusado com MAINNET.

**`verify.sh`:** a linha do spec novo e o `--build-arg` vão prontos para o responsável.

## Implantação e rollback [Q4]

**Ordem:**
1. migração 0021;
2. worker;
3. API.

O TxSigner não muda. As imagens antigas continuam funcionando sobre a 0021 por causa do `DEFAULT` e do gatilho de `revoked_at`.

**Rollback:**
1. desligar o REAL ou ligar o kill switch;
2. voltar as imagens.

Se for preciso voltar o banco, o rollback da 0021 devolve o gatilho da 0005. As tabelas novas ficam, e o saque continua disponível.
