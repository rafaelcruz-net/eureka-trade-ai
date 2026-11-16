# Code Summary — U5 `u5-real-operations`

Conversa: português (Brasil). Intent `261113-trading-real-bot`, data 2026-11-16. Plano: `code-generation-plan.md` (Steps 1 a 16, todos marcados). Instruções de teste: `unit-test-instructions.md`. Código, identificadores, logs e comentários em inglês; textos de interface e deste resumo em português.

## O que foi construído, por etapa

| Etapa | Ordem | O que entrou |
|---|---|---|
| 1 — Configuração e pools | depois | `memetrade.build.sha`, `memetrade.real.founder-user-id` e o bloco `memetrade.real.ops` no `application.yml`; worker com 33 conexões e 25 threads (`application-worker.yml`, `RuntimeSettingsGuard`, `docker-compose.yml`, `.env.example`); `ARG BUILD_SHA` → `MEMETRADE_BUILD_SHA` nos Dockerfiles da API e do TxSigner; `RealOpsProperties`, `RealOpsStartupChecks` (MAINNET exige perfil de produção, fundador, `BUILD_SHA`, CI lida do GitHub e, no worker, repositório e token). |
| 2 — Arquitetura | testes antes | `RealOpsArchitectureTest` com 4 fixtures (rede na API, assinatura na liberação, divergência no PAPER, dependência do `riskcontrol`). |
| 3 — Migração 0021 | testes antes | 11 changesets só de expansão, com rollback cada um: pré-condição de duplicatas, estado da liberação com gatilho, matriz de privilégios e gatilhos de idade, tabelas `real_reconciliation_run`, `real_divergence`, `real_kill_switch`, `real_pending_txsigner_command`, `real_restore_marker`, endereços anuláveis, redação do saque (`redacted_at`, CHECK novo, rollback que para se houver linha redigida), enums ampliados (pausas, detalhes de 0018, `decision.mode` com `REAL`), compensação de token no ledger e redação da trilha (`redact_real_wallet_audit`, SECURITY DEFINER). |
| 4 — Liberação e portões | testes antes | `RealRelease` (11 campos), `ReleaseRules`, `GateEvaluator`, `ReleaseService`, `GatesService`, `GatesJob`, `CiStatus` com `GitHubCiStatus` e `ConfiguredCiStatus`. |
| 5 — Kill switch (M7) | testes antes | `RealKillSwitchState`, `RealKillSwitchService` (CAS na versão), `KillSwitchRows`, `KillSwitchCancelJob`, `GlobalPauses`; pausas globais na M1, na barreira (`RealRiskPolicy`) e no recheck da M6. |
| 6 — Reconciliação | testes antes | `ReconciliationPolicy`, `ReconciliationService`, `RealReconciliationJob` (4 linhas, tick de 15 s), `ReconciliationReader`, `RpcChainWatcher` (saldos de token, slot finalizado, assinaturas), `RpcBudget` (cotas próprias), M2 `tokenBalances`, M6 `ordersInTransit`. |
| 7 — Divergência e compensação | testes antes | `DivergenceService`, `CompensationRules`, compensação de SOL e de token no ledger (lançamento novo, ledger só de inserção). |
| 8 — Limite do saque | testes antes | `WithdrawalLimit` no `BotWalletService.reserve` (`WITHDRAWAL_LIMITED_BY_DIVERGENCE`). |
| 9 — Teto e comandos ao TxSigner | testes antes | `CapRules`, `CapService`, `TxSignerCommandService` e `TxSignerCommandJob` (`LOWER_CAP` e `DEACTIVATE` reenviados até confirmar). |
| 10 — Anonimização | testes antes | `RealAnonymization` e `BotWalletAnonymizationService` (recusas `ORDER_IN_PROGRESS`, `POSITION`, `BALANCE`; fecha, envia `DEACTIVATE`, limpa endereços, revoga liberações, redige a trilha); `AnonymizationService` da privacidade chama a M1. |
| 11 — Restauração | testes antes | `RestoreCheck`, `RestoreService`, `RestoreDetector` (subida e trabalho), M6 `knownTransactions`; depósitos não são varridos com a conferência pendente. |
| 12 — Rotas do administrador | depois | `RealAdminController` (14 rotas R4), `RealAdminDtos`, `RealAdminErrors` (RFC 7807), `RealAdministration` implementada por `RealAdministrationService`, `AdminViewStore`, `WalletDisplay.abbreviate`, M5 `blockedTokens`, `IdempotentOperation.REAL_ADMIN_*`, rotas no `route-registry.yml`, `openapi-admin.yaml` e tipos do app administrativo regenerados. |
| 13 — Expurgo | testes antes | `RealOpsPurgeJob` na API às 05:00 UTC, ShedLock `real-ops-purge`, pool `risk_retention`; divergências resolvidas há mais de 5 anos primeiro, depois reconciliações de mais de 13 meses sem divergência que as referencie (`NOT EXISTS`), comandos confirmados há mais de 13 meses e liberações revogadas há mais de 5 anos; exceções nomeadas nas regras ArchUnit. |
| 14 — Métricas, alertas e Terraform | depois | `RealOpsGaugesJob` (30 s, batimento `ops-gauges`), M6 `unsentOrders`; `infra/observability/real-ops-alerts.yml` (13 regras); `infra/terraform/modules/observability/real-ops.tf` (`Eureka/RealOps`, `restore-check` por metric math); segredo `github-actions-read-token` sem consumidor em `infra/terraform/modules/services/github-actions.tf`. |
| 15 — Ponta a ponta | depois | `mintToNewToken` em `local-validator.ts` (uma transação legada de 4 instruções feita à mão, sem SDK: `createAccount`, `InitializeMint2`, `CreateIdempotent`, `MintTo`; endereço associado derivado com SHA-256 e teste de curva Ed25519); variáveis do worker em `infra/docker-compose.real.yml`; spec `apps/web/e2e/real-ops/divergence.spec.ts`. |
| 16 — Documentação | — | `docs/runbooks/real-operations.md`; adendo da U5 em `inception/contract-design/contract-summary.md`; este resumo, `traceability.json` e `source-manifest.json`. |

A lista completa dos arquivos de código criados ou alterados está em `source-manifest.json` (189 caminhos).

## Testes

### Execuções

| Comando | Resultado |
|---|---|
| Comando de testes da Unit (`unit-test-instructions.md`) | 84 classes, 633 testes, 0 falhas, 0 erros, 0 ignorados |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (suíte completa com cobertura) | **Não concluído**: o Claude Code parou a execução por falta de memória na máquina (não foi falha do build). Não repeti, como manda a regra do trabalho. |
| `promtool check rules /rules/real-ops-alerts.yml` (`prom/prometheus:v3.5.0`) | SUCCESS: 13 rules found |
| `terraform fmt -check -recursive` (`hashicorp/terraform:1.9.8`) | sem diferenças |
| `terraform init -backend=false` e `terraform validate` (numa cópia de `infra/terraform`) | Success! The configuration is valid. |
| `pnpm -C apps/admin gen:api`, `check:api` e `typecheck` | tipos regenerados; `schema.d.ts matches openapi-admin.yaml`; sem erros |
| `prettier`, `eslint`, `tsc --noEmit` e `playwright test --list e2e/real-ops` em `apps/web` | sem erros; 4 testes listados |
| `spotless:apply` (palantir) antes do `verify` | aplicado |

### Testes novos da U5

| Classe | Testes | Etapa |
|---|---|---|
| `RealOpsArchitectureTest` | 8 | 2 |
| `RealOpsStartupChecksTest` | 9 | 1 |
| `RealOpsMigrationIT` | 19 | 3 |
| `GateEvaluatorTest`, `ReleaseRulesTest`, `GitHubCiStatusTest`, `ConfiguredCiStatusTest`, `ReleaseAndGatesIT` | 8, 7, 11, 3, 17 | 4 |
| `KillSwitchIT`, `RealRiskPolicyGlobalPausesTest`, `RealStateGlobalPausesTest`, `RealExecutionRecheckTest` | 9, 7 (parametrizado), 5, 8 | 5 |
| `ReconciliationPolicyTest`, `ReconciliationIT`, `ChainWatcherOpsTest` | 12, 14, 12 | 6 |
| `CompensationRulesTest` | 6 | 7 |
| `WithdrawalLimitTest`, `WithdrawalLimitIT` | 11, 3 | 8 |
| `CapRulesTest`, `CapAndCommandsIT` | 3, 4 | 9 |
| `BotWalletAnonymizationIT`, `NoWalletAddressAfterAnonymizationIT` | 6, 2 | 10 |
| `RestoreCheckTest`, `RestoreIT` | 6, 8 | 11 |
| `RealAdminRoutesIT` | 12 | 12 |
| `RealOpsPurgeJobIT` | 6 | 13 |
| `RealOpsMetricsTest`, `RealOpsGaugesIT`, `RealOpsAlarmParityTest` | 5, 6, 4 | 14 |
| `RealBotDecisionModeTest` | 5 | S25 |
| `divergence.spec.ts` (Playwright) | 4 | 15 |

Testes existentes ajustados: `AbstractRealIT`, `RealStateQueryIT`, `RecordedSwapCycleIT` (estado `ACTIVE` e S25), `RealStateServiceTest`, `ActivationRulesTest`, `FakeChain`, `FakeTxSigner`, `WithdrawalProcessorTest`, `ComposeFilesTest`, `AuditPropertiesTest`, `U4ContextIT` a `U8SettingsTest`, `RuntimeSettingsGuardWorkerCountTest`, `WorkerProcessRoleContextIT` (33 e 25), `ProcessRoleContextIT` (`RealOpsPurgeJob` na API), `RiskControlArchitectureTest` e `RealRiskArchitectureTest` (exceção nomeada do expurgo), `AdminOpenApiSlice` (controlador R4 e seus mocks).

### Ordem dos testes (honestidade sobre o "testes antes")

Nas etapas marcadas `[testes antes]` os testes foram escritos a partir das regras e dos cenários antes do código. A execução vermelha foi registrada nas etapas 3 (17 de 19 falharam), 4, 6 e 13 (as três por falta de compilação, porque as classes ainda não existiam). Nas etapas 5 e 7 a 11 os testes também foram escritos antes, mas não houve uma execução vermelha separada antes do código: a primeira execução já foi com o código pronto.

### Cobertura

Sem número: o `verify` com JaCoCo foi interrompido por falta de memória antes de terminar, então o piso de 80% de linhas do `apps/api` **não foi medido** nesta Unit. Nenhuma exclusão nova foi acrescentada e o piso não foi mexido. A conferência fica para a próxima execução do `verify` (local, com memória livre, ou o job `backend` da CI).

## Textos para o responsável do projeto

Não editei `scripts/verify.sh` nem `.github/workflows/*`. Os textos abaixo estão prontos para colar.

### `.github/workflows/ci.yml`

1. No job `smoke`, passo `Walking-skeleton verification`, e no job das e2e completas, passo `Full Playwright run on the smoke environment`, acrescente `BUILD_SHA` ao `env`:

```yaml
        env:
          IMAGE_TAG: ${{ github.sha }}
          BUILD_SHA: ${{ github.sha }}
        run: bash scripts/verify.sh
```

2. No job `backend`, passo `Alert rules (promtool)`, acrescente `real-ops-alerts` ao laço:

```yaml
          for rules in real-alerts marketdata-alerts real-risk-alerts real-execution-alerts real-ops-alerts; do
```

### `scripts/verify.sh`

1. Na construção da imagem do TxSigner (linha `docker build -t "memetrade-txsigner:${IMAGE_TAG:-local}" apps/txsigner`):

```bash
docker build --build-arg BUILD_SHA="${BUILD_SHA:-}" -t "memetrade-txsigner:${IMAGE_TAG:-local}" apps/txsigner
```

A imagem da API recebe o mesmo valor pelo `build.args` do `docker-compose.yml` (`BUILD_SHA: ${BUILD_SHA:-}`), já incluído nesta Unit; basta o `BUILD_SHA` estar no ambiente.

2. Na lista de specs do Playwright, acrescente `e2e/real-ops` **depois** de `e2e/real-swap` (o spec usa a carteira do bot que o `real-bot-wallet` ativa):

```bash
    e2e/reacceptance e2e/data-requests e2e/anonymization e2e/real-bot-wallet e2e/real-swap e2e/real-ops
```

O spec usa `SMOKE_WALLET_FILE`, `SMOKE_ADMIN_WALLET_FILE`, `API_BASE_URL` e `SOLANA_RPC_URL`, que o `verify.sh` já passa. O worker da sobreposição REAL já recebe `MEMETRADE_REAL_OPS_RECONCILIATION_INTERVAL=PT15S` e `MEMETRADE_REAL_OPS_CI=GREEN` (em `infra/docker-compose.real.yml`), então nenhum segredo do GitHub é preciso nessa execução.

## Desvios e decisões

| Item | Decisão e motivo |
|---|---|
| Adaptador do GitHub | Em `botwallet.infra.github` (o plano e a iteração de componentes põem os portões no `botwallet`; o `security-requirements.md` citava `administration.infra.github`). Sem Resilience4j: prazo de 5 s e qualquer erro vira `UNREACHABLE`, que bloqueia a liberação com `CI_UNREACHABLE`. |
| Segredo do GitHub | Não existe módulo `secrets` no Terraform; o segredo foi criado em `modules/services/github-actions.tf`, no mesmo padrão do segredo da CoinGecko da U2, sem consumidor. |
| Expurgo | Usa o pool `risk_retention` do `riskcontrol` como exceção nomeada (ArchUnit em `RiskControlArchitectureTest`, `RealRiskArchitectureTest` e `RealOpsArchitectureTest`). Apaga reconciliações de qualquer resultado com mais de 13 meses que nenhuma divergência referencia (não só as `CLEAN`), para a tabela não crescer com `DEFERRED` e `UNAVAILABLE`. Não grava tabela de registro de rodadas (a 0021 não tem uma); cada rodada vai para o log com os totais e conta o batimento `purge`. |
| Cotas do RPC | Reconciliação (20%) e conferência de restauração (10%) têm cotas próprias, sem tirar da cota dos depósitos, para manter o teste 80/20 existente. |
| Reconciliação | O trabalho roda a cada 15 s e reconcilia as carteiras devidas; a API nunca chama o RPC: a compensação deixa `COMPENSATED` e o worker roda `AFTER_COMPENSATION`. |
| S14 | O valor errado é recusado na hora (`422 COMPENSATION_MISMATCH`) e nada é gravado; a divergência continua `OPEN` e a pausa continua, que é o resultado pedido. |
| Reconciliação limpa com divergência `OPEN` | Uma rodada `CLEAN` sobre uma divergência ainda não compensada a deixa `OPEN` (só `COMPENSATED` vira `RESOLVED`). Item aberto abaixo. |
| Portão de rede | Compara a rede da liberação com a rede configurada do ambiente; a rede do TxSigner não é lida pela API (o próprio TxSigner recusa outra rede na subida). |
| Revogação | Cancela as ordens não enviadas; o recheck antes do envio não passou a consultar a liberação. |
| `DEACTIVATE` | Enviado ao TxSigner como consentimento de política `CLOSE` pela rota de consentimentos existente; o endereço é lido na hora do envio. |
| Visão da pessoa | `GET /api/v1/real/bot-wallet` passou a mostrar a pausa global (kill switch, restauração) em `pausedReason`, para a pessoa ver por que o bot parou. |
| Contrato R4 | Diferenças em relação ao rascunho do Inception (kill switch `PUT`, compensação por `divergenceId` e itens por ativo, motivo de até 500, sem `confirmation`, `CAP_ONLY_LOWERED`, rota de restauração) estão no adendo da U5 do `contract-summary.md`. |
| Correções depois da revisão | Duas correções:<br>• `RpcChainWatcher.transactionSigners`: uma assinatura sem transação finalizada agora lança `ChainUnavailableException`, e a conferência da restauração continua pendente. Antes, a lista de assinantes vinha vazia e a assinatura passava como "não assinada pelo bot", e a conferência falhava aberta. A regressão é o teste `aSignatureWithNoTransactionYetIsUnavailableNotUnsigned`.<br>• Alarme `real_ops_restore_pause`: agora usa metric math sobre as duas fases (`RESTORE` e `CHECK_PENDING`), porque a métrica tem a dimensão `phase`.<br>`ChainWatcherOpsTest` (13) e `RealOpsAlarmParityTest` (4) passaram, e o `terraform fmt -check` também. |
| Arquivo solto | Rodar `pnpm` dentro de `apps/web` fez o hook do AI-DLC criar `apps/web/aidlc/.aidlc-sessions/<sessão>.binding.json`. Não é código da Unit e não foi apagado (é arquivo de hook); pode ser removido. |

## Itens abertos

- Rodar o `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` completo (com Spotless check e o piso de cobertura). A execução desta Unit foi parada por falta de memória.

- Aplicar no `ci.yml` e no `verify.sh` os textos acima (responsável do projeto). Até lá o spec `e2e/real-ops` não roda na CI e o `BUILD_SHA` chega vazio às imagens.
- O spec `divergence.spec.ts` foi validado só por `tsc`, `eslint` e `playwright --list`; não rodei o `verify.sh` (regra do trabalho). A primeira execução real dele será na CI ou no `verify.sh` do responsável.
- Divergência `OPEN` com rodada `CLEAN` seguinte (sem compensação) continua `OPEN`; decidir se deve virar `RESOLVED` sozinha.
- Telas do administrador e do usuário para R4 ficam na U6; as condicionais do kill switch, na U7.
- A tarefa do worker na nuvem, que consome o segredo `github-actions-read-token`, fica para o Environment Provisioning; o caminho das métricas até o CloudWatch continua pendente para U1 a U5.
