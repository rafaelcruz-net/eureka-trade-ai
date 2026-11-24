# Instruções de testes da Unit — U4 `u4-own-quantity-cycle`

## Antes do primeiro teste: conferir os runners (Etapa 1)

Todos os comandos rodam a partir da raiz do repositório. Cada runner é conferido com um teste que já existe na mesma área, antes de qualquer teste novo:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='RealCycleOrderCapTest' -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealCycleAttributionIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
pnpm --dir apps/web exec jest src/features/bots/BotList.test.tsx
```

```bash
docker info
```

O `RealCycleAttributionIT` confirma o Testcontainers com o PostgreSQL 17 e a base `AbstractRealExecutionIT`, que os testes da Fase A estendem. O `docker info` confirma o Docker para o T20, que sobe a imagem `prom/prometheus` pelo Testcontainers; sem Docker local, o T20 fica para o job `backend` da CI, e o fato vai para o `code-summary.md`.

O `terraform` e o Checkov não estão instalados nesta máquina: os `run` do `terraform test` são escritos nesta Unit e rodados à mão pelo responsável (ver "Terraform").

Se um runner falhar, a Etapa 2 não começa: a falha do runner é corrigida primeiro.

## Estratégia e volume

- **Test Strategy:** `Standard`. Cada componente tem de 5 a 8 testes, com o caminho feliz e pelo menos 2 casos de erro ou de borda. As fronteiras principais (a leitura `RealBotHoldings`, `openSells`, o ciclo REAL com a flag, as migrações, o C7) têm testes de integração com Testcontainers e PostgreSQL 17 real, com as migrações da aplicação.
- **Piso:** 80% de cobertura de linhas, medido em separado. No `apps/api`, o JaCoCo junta unidade e integração. No `apps/web`, vale o `coverageThreshold.global.lines = 80` do Jest. `apps/admin` e `apps/txsigner` não mudam e continuam acima do piso. Nenhuma exclusão nova; o piso nunca é reduzido.
- **Ordem (Testing Contract `custom`):** os testes vêm antes do código no dinheiro REAL:
  - o corte e a venda da quantidade própria: os cenários T1 a T4 (E1 a E7, E10), quantidade zero, vendas em aberto com `AMBIGUOUS` e decisões SELL `PENDING` (inclusive adiadas), falha e prazo (T5), flag desligada igual a hoje (T7), assentamento em paralelo (T8), um bot nunca vende a parte de outro (T9), a marca do bot e a concorrência (T10, renovação da marca), uma transação por ordem (T11), arquitetura (T13), PAPER e compras sem mudança (T3, T14), o risco residual documentado (T18, T19) e as saídas do dinheiro;
  - a leitura `RealBotHoldings` (C15/C16): a semântica de `openSells`, a paridade com o `RealBotPnl` (T12) nos dois caminhos de S4, o tenant (T6), o isolamento e o prazo.

  Por NFR7.1, os T15 a T17 (aviso na tela) e o T20 (regra de alerta) também entram com o vermelho registrado antes do código da própria camada. Nas demais camadas (migrações, medida, métricas e logs, C7 e contrato, gêmeos no CloudWatch, Terraform, ponta a ponta, runbook), os testes vêm depois de cada camada.
- **Testes obrigatórios por risco que a U4 toca:**
  - pausa do bot impede novas operações (T5: bot pausado não lê nem vende);
  - deduplicação por chave de idempotência e uma única transação por ordem REAL (T11);
  - saque sempre disponível e só para a carteira principal (`OwnQuantityExitsIT` com o `RealBotHoldings` falhando);
  - REAL desligado por padrão e a flag da U4 desligada por padrão nos dois processos (`OwnQuantitySellDefaultIT`, T7);
  - isolamento entre contas com tarefa em segundo plano (T6, `RealBotHoldingsTenantIT`);
  - separação REAL/PAPER e modos nunca somados (T14, `NeverSumModesArchitectureTest`);
  - ledger sem edição e P&L exato (T1, T18, T19 conferem com o ledger por igualdade exata).

## Ferramentas

| Parte | Ferramentas |
|---|---|
| `apps/api` | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL 17 e `prom/prometheus` pelo digest do `ci.yml`), ArchUnit, JaCoCo, Mockito, `SimpleMeterRegistry`, SnakeYAML; propriedade com `java.util.Random` e sementes fixas |
| `apps/web` | Jest 30 com Testing Library (`jest.config.ts`), `fetch` simulado, `api` falso |
| Regras de alerta | `promtool test rules` dentro do `RealOpsOwnHoldingsAlertRuleIT`; o `promtool check rules` do `ci.yml` continua como está |
| Terraform | `terraform test` (`infra/terraform/tests/`), à mão, pelo responsável |
| Ponta a ponta | Playwright, com o validador Solana local, via `scripts/verify.sh` |

## Comandos desta Unit

Todos cobrem só os arquivos da U4. Os nomes seguem o `code-generation-plan.md`; se um nome mudar na geração, este arquivo muda junto.

### Fase A — vermelho e verde, testes de unidade (Etapas 2, 3, 4 e 5)

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='OwnSellCapTest,OwnDecisionInputsTest,RealBotHoldingsReaderTest,OwnHoldingsStepTest,OwnQuantityArchitectureTest,OwnHoldingsSqlNoLockTest,OwnHoldingsSqlTenantTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### Fase A — vermelho e verde, testes de integração (Etapas 3, 4 e 5)

Leitura `RealBotHoldings` e `openSells`:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealOpenSellsIT,RealBotHoldingsParityIT,RealBotHoldingsOrderPathParityIT,RealBotHoldingsTenantIT,RealBotHoldingsIsolationIT,RealBotHoldingsDeadlineIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

Ciclo REAL com a flag:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='OwnQuantitySellIT,OwnQuantityNoOwnIT,OwnQuantityOpenSellsIT,OwnHoldingsUnavailableIT,OwnHoldingsPrePhaseFailureIT,OwnQuantitySettlementRaceIT,OwnQuantityOtherBotsShareIT,OwnQuantityConditionalGapIT,OwnQuantityStaleSnapshotIT,RenewMarkOnSellIT,OwnQuantityOneTransactionIT,OwnQuantityFlagOffIT,OwnQuantityPaperUnchangedIT,OwnQuantityFlagFlipIT,OwnQuantityExitsIT,OwnQuantitySellDefaultIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

O vermelho de cada um é rodado e registrado antes do código (falha de compilação ou de asserção), e o resultado vai para o `code-summary.md` (Etapa 6).

### Backend — testes de unidade

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='OwnSellCapTest,OwnDecisionInputsTest,RealBotHoldingsReaderTest,OwnHoldingsStepTest,DecisionJsonOwnQuantityTest,OwnQuantityMetricsTest,OwnHoldingsStreaksTest,OwnQuantityLogRedactionTest,OwnQuantityArchitectureTest,OwnHoldingsSqlNoLockTest,OwnHoldingsSqlTenantTest,CiPromtoolDigestTest,AlertRuleTestAnnotationsTest,RealOpsAlarmParityTest,GuestOpenApiContractTest,ArchitectureTest,BotsArchitectureTest,RealCostArchitectureTest,ModeBoundaryArchitectureTest,NeverSumModesArchitectureTest,RealExecutionArchitectureTest,PnlViewsArchitectureTest,RealCycleOrderCapTest,RealCycleCostUnknownTest,RealBotDecisionModeTest,RealBotPnlTest,RealBotPnlQueryTest,RealCostParityTest,RealCostBasisEpochTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Os sete últimos (de `RealCycleOrderCapTest` em diante) e as regras ArchUnit que já existiam são de Units anteriores e continuam verdes com o `EpochAttribution` extraído e o ciclo mudado.

### Backend — testes de integração

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealOpenSellsIT,RealOpenSellsPlanIT,RealBotHoldingsParityIT,RealBotHoldingsOrderPathParityIT,RealBotHoldingsTenantIT,RealBotHoldingsIsolationIT,RealBotHoldingsDeadlineIT,RealBotHoldingsMeasurementIT,Migration0026IT,Migration0026LockTimeoutIT,Migration0027IndexRestartIT,AnonymizationMigrationIT,ActiveDispatchIT,OwnQuantitySellIT,OwnQuantityNoOwnIT,OwnQuantityOpenSellsIT,OwnHoldingsUnavailableIT,OwnHoldingsPrePhaseFailureIT,OwnQuantitySettlementRaceIT,OwnQuantityOtherBotsShareIT,OwnQuantityConditionalGapIT,OwnQuantityStaleSnapshotIT,RenewMarkOnSellIT,OwnQuantityOneTransactionIT,OwnQuantityFlagOffIT,OwnQuantityPaperUnchangedIT,OwnQuantityFlagFlipIT,OwnQuantityExitsIT,OwnQuantitySellDefaultIT,OwnQuantityMetricsIT,BotListOwnQuantityFieldIT,BotListOwnQuantityFlagOnIT,RealOpsOwnHoldingsAlertRuleIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

- Nota da geração: o `BotListOwnQuantityFlagOnIT` (a propriedade ligada na API) entrou ao lado do `BotListOwnQuantityFieldIT`. O `RealBotHoldingsMeasurementIT` e o `OwnQuantityLogRedactionTest` ficaram no pacote `bots.worker` (precisam do ciclo montado no teste e do corte de pacote do `CycleRunner`); os comandos usam só o nome da classe e não mudam. A medida aceita `-Du4.measure.reads=<n>` (10.000 por padrão), `-Du4.measure.cycles=<n>` (100) e `-Du4.measure.days=<n>` (730); nesta máquina, com 300 leituras, ela levou cerca de 14 minutos.
- Nas execuções por classe com `verify`, esta geração usou `-Djacoco.skip=true`: a regra de cobertura de 80% do JaCoCo vale para o módulo inteiro, e uma execução parcial não mede a cobertura do módulo. O piso é conferido no `verify` completo.
- O `RealBotHoldingsMeasurementIT` só registra no log, por semente (A e B), o p99,9 de 10.000 leituras (a 9.990ª), o p50, o p95 e o p99, a contagem acima de 250 ms, o p95 do ciclo com a flag ligada e desligada e a razão de `STALE_SNAPSHOT`; ele falha só por leitura com erro ou com quantidade errada, nunca pelo tempo (NFR1.5). A mediana de 3 execuções vai para `docs/perf/u4-own-quantity-cycle-<data>.md` no Build and Test.
- O `RealOpsOwnHoldingsAlertRuleIT` (T20) lê o digest do passo `Alert rules (promtool)` do `.github/workflows/ci.yml` e roda `promtool test rules` com os 10 casos de `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml`. Precisa de Docker.
- `Migration0026LockTimeoutIT` espera até 6 s pelo `55P03`; `OwnQuantityStaleSnapshotIT` e `OwnQuantitySettlementRaceIT` usam barreiras (`CountDownLatch`), nunca tempo. Uma falha instável é defeito a investigar, nunca um teste a ignorar.

### Backend — testes obrigatórios por risco e de Units anteriores que continuam verdes

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='CycleIT,CycleFailureIT,LockOrderIT,RecordedSwapCycleIT,RealCycleAttributionIT,AttributedCostConsistencyIT,AttributedCostTenantIT,AttributionFailureGateIT,DecisionConsumerIT,DecisionConsumerExitIT,ConfirmationAndAmbiguousIT,OrderDriverIT,RealSettlementIT,LiquidationIT,LiquidationExitIT,PersonSaleExitIT,KillSwitchIT,WithdrawalLimitIT,BotWalletRoutesIT,RealOffByDefaultRoutesIT,RealReleaseRouteOffIT,PaperExecutionIdempotencyIT,RealPnlTenantIsolationIT,RealNeverPaperIT,RealPositionEpochAppendOnlyIT,Migration0023IndexRestartIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

### Contrato (C7)

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='GuestOpenApiContractTest' -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='GuestOpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false
pnpm --dir apps/web run gen:api
pnpm --dir apps/web run check:api
```

O primeiro comando regenera o `openapi-guest.yaml`; o segundo confere que o arquivo versionado é igual ao gerado. O `schema.d.ts` só é regenerado por `gen:api`, nunca à mão.

### Frontend (Jest)

```bash
pnpm --dir apps/web exec jest src/features/bots/BotList.test.tsx src/features/bots/BotModeFilter.test.tsx src/features/bots/BotScreens.test.tsx
```

O `BotList.test.tsx` ganha os casos T15 a T17, escritos antes do código do aviso (vermelho registrado). Verificação completa do `apps/web` (Etapa 22):

```bash
pnpm --dir apps/web run lint
pnpm --dir apps/web run format:check
pnpm --dir apps/web run typecheck
pnpm --dir apps/web exec jest --coverage
```

### Terraform (à mão, pelo responsável)

```bash
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test
```

Inclui, em `tests/observability_metrics.tftest.hcl`, as asserções novas de `every_alarm_reads_a_published_metric` e os `run` `own_quantity_flag_default`, `own_quantity_flag_api_only` e `own_quantity_flag_worker_without_api` (este com `expect_failures = [var.real_own_quantity_sell_worker]`). O resultado vai para o registro do Build and Test da U4. Levar esses testes à CI continua pendência do responsável (`team.md`).

### Ponta a ponta e ponto de controle

O spec `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts` (com o caso novo de 360 px do aviso, por `page.route`) roda só pelo `verify.sh`, que já o lista; a U4 não muda o `scripts/verify.sh`.

```bash
bash scripts/verify-checkpoint.sh
```

Ele roda primeiro o `verify.sh` local. Se a máquina não aguentar (memória), a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`).

## Classes de teste por fase

| Fase | Antes do código | Depois da camada | Continuam verdes |
|---|---|---|---|
| A — quantidade própria e leitura | `OwnSellCapTest`, `OwnDecisionInputsTest`, `RealBotHoldingsReaderTest`, `RealOpenSellsIT`, `RealBotHoldingsParityIT` (T12), `RealBotHoldingsOrderPathParityIT`, `RealBotHoldingsTenantIT` (T6), `RealBotHoldingsIsolationIT`, `RealBotHoldingsDeadlineIT`, `OwnQuantitySellIT` (T1), `OwnQuantityNoOwnIT` (T3), `OwnQuantityOpenSellsIT` (T4), `OwnHoldingsStepTest` e `OwnHoldingsUnavailableIT` (T5), `OwnQuantitySettlementRaceIT` (T8), `OwnQuantityOtherBotsShareIT` (T9), `OwnQuantityConditionalGapIT` (T18), `OwnQuantityStaleSnapshotIT` (T10), `RenewMarkOnSellIT`, `OwnQuantityOneTransactionIT` (T11), `OwnQuantityFlagOffIT` (T7), `OwnQuantityPaperUnchangedIT` (T14), `OwnQuantityFlagFlipIT` (T19), `OwnQuantityExitsIT`, `OwnQuantitySellDefaultIT`, `OwnQuantityArchitectureTest` (T13), `OwnHoldingsSqlNoLockTest`, `OwnHoldingsSqlTenantTest` | — | — |
| B — banco e leitura | `Migration0026IT`, `Migration0026LockTimeoutIT`, `Migration0027IndexRestartIT` (camada de dados, escritos antes da migração) | `RealOpenSellsPlanIT`, `RealBotHoldingsMeasurementIT`; `AnonymizationMigrationIT` (alterado) | `RealBotPnlTest`, `RealBotPnlQueryTest`, `AttributedCostConsistencyIT`, `AttributedCostTenantIT`, `RealCycleAttributionIT`, `AttributionFailureGateIT`, `RealCostParityTest`, `RealCostBasisEpochTest`, `Migration0023IndexRestartIT` |
| C — ciclo | — | `ActiveDispatchIT`, `DecisionJsonOwnQuantityTest`, `OwnQuantityMetricsTest`, `OwnHoldingsStreaksTest`, `OwnQuantityMetricsIT`, `OwnQuantityLogRedactionTest` | `CycleIT`, `CycleFailureIT`, `LockOrderIT`, `RecordedSwapCycleIT`, `RealCycleOrderCapTest`, `RealCycleCostUnknownTest`, `RealBotDecisionModeTest`, as regras ArchUnit e os testes por risco |
| D — contrato e tela | `BotList.test.tsx` (T15 a T17) | `BotListOwnQuantityFieldIT`, `GuestOpenApiContractTest` (alterado), caso de 360 px do `paper-real-unico.spec.ts` | `BotModeFilter.test.tsx`, `BotScreens.test.tsx` |
| E — observabilidade e IaC | `RealOpsOwnHoldingsAlertRuleIT` (T20) com `real-ops-own-holdings-unavailable.test.yml`, `CiPromtoolDigestTest`, `AlertRuleTestAnnotationsTest` | `RealOpsAlarmParityTest` (alterado: `hasSize(16)`, `cloudPeriods`, 2 testes novos), `observability_metrics.tftest.hcl` (manual) | o `promtool check rules` do `ci.yml` |

## Metas de cobertura

| Parte | Meta | Ferramenta |
|---|---|---|
| `apps/api` | ≥ 80% de linhas, unidade e integração mescladas | JaCoCo (`mvnw verify`) |
| `apps/web` | ≥ 80% de linhas | Jest (`coverageThreshold.global.lines = 80`) |
| Regras da U4 | cada regra BR1.1 a BR7.2 com teste; o piso de 80% vale para o módulo inteiro | JaCoCo e Jest |

## Dublês (mocks e stubs)

- **Dinheiro (Fase A):** sem dublê nos ITs do ciclo. Eles estendem `AbstractRealExecutionIT`: o `FakeRealChain` e o `FakeSwapRouter` fazem o papel da rede e do roteador, o KMS é simulado e o `RealBotHoldingsReader`, o `RealBotPnl`, o consumidor da fila e o `OrderSettlement` são os reais, sobre o PostgreSQL do contêiner.
- **Falhas da leitura:** `OwnHoldingsUnavailableIT` injeta o erro de banco num `RealBotHoldings` de teste que delega ao real; o prazo é forçado com `pg_sleep` (`RealBotHoldingsDeadlineIT`) e com `Clock` falso (`RealBotHoldingsReaderTest`); `OwnQuantityExitsIT` usa um `RealBotHoldings` que sempre lança erro de banco.
- **Unidade:** `RealBotHoldingsReaderTest` usa dublês das portas (`BotDirectory`, `RealLedger`, `RealExecutionPort`), no padrão do `RealBotPnlTest`; `OwnHoldingsStepTest` usa um contador de chamadas e grava a ordem das leituras (P1 a P3, M0, `RealBotHoldings`).
- **Concorrência:** `OwnQuantityStaleSnapshotIT`, `OwnQuantitySettlementRaceIT` e `RealBotHoldingsIsolationIT` param os fluxos com `CountDownLatch`; nenhum `Thread.sleep` decide o resultado.
- **Relógio:** sempre o `Clock` injetado (prazo de 250 ms, despejo de 20 min do `OwnHoldingsStreaks`, slots de 5 min).
- **Métricas:** `SimpleMeterRegistry` nos testes de unidade; `/actuator/prometheus` real no `OwnQuantityMetricsIT`.
- **Regras de alerta:** a imagem real `prom/prometheus` pelo digest do `ci.yml`, nunca uma imagem por tag.
- **Web:** `api` falso nas telas, `fetch` simulado no cliente, fixtures de `BotPage` tiradas do contrato gerado (com `realOwnQuantitySell` `true`, `false` e ausente); no ponta a ponta, `page.route` só para pôr `realOwnQuantitySell: true` na resposta real.
- **Rede:** nenhuma. Nenhum teste usa devnet, mainnet, KMS real ou chave real.

## Dados de teste

- **Exemplos de dinheiro:** mint `XYZ` sintético com 6 casas (1 `XYZ` = 1.000.000 unidades base); bots A e B REAL da mesma pessoa na mesma carteira; os números de E1 a E11 do `functional-spec.md` em lamports e unidades base (por exemplo, A 100.000.000 por 100.000.000, B 300.000.000 por 450.000.000, recebido 200.000.000, taxa 5.000).
- **Semente da medida:** A (1 pessoa, 5 bots REAL, 10 mints, 730 dias, 4 ordens por bot por dia, 14.600 ordens, 5% de excedentes, 2% de condicionais, 2% de liquidações, 3 ordens SELL em andamento com uma `AMBIGUOUS` e 3 decisões SELL `PENDING` com uma adiada) e B (a mesma, sem nenhuma zeragem em 2 anos).
- **Plano do `openSells`:** 2 tenants, 20.000 decisões fechadas, 6 `PENDING` (3 SELL, 1 adiada, 3 BUY), 14.600 ordens finais e 1 ordem SELL em andamento do bot medido, com `ANALYZE`.
- **Paridade de S4:** exatamente 32 e exatamente 33 ordens distintas nos lotes.
- **Propriedade (T9):** `java.util.Random` com 3 sementes fixas e pelo menos 50 sequências por semente.
- **Regras de alerta:** os 10 casos do `observability-design.md`, com amostras de 15 s e avaliação a cada 1 min.
- **Dois tenants:** nos testes de isolamento (T6, `RealBotHoldingsTenantIT`) e do plano.
- **Carteiras, mints e assinaturas:** sintéticos, nunca reais; os endereços e a assinatura semeados servem para o `OwnQuantityLogRedactionTest` conferir que nunca aparecem no log.
- **Independência:** cada IT limpa os próprios dados e não depende da ordem de execução.
