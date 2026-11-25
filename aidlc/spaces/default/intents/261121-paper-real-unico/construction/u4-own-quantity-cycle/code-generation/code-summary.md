# Resumo da geração de código — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Plano aprovado: `code-generation-plan.md` (31 Etapas, fases A a F). Testing Contract `custom` (`sha256:c4f4514e…`). Nenhum commit e nenhum push foram feitos.

## O que foi construído

Com a flag `memetrade.real.own-quantity-sell` (desligada por padrão na API e no worker), o ciclo de um bot REAL vende só a quantidade disponível do próprio bot.

| Parte | Arquivos | O que faz |
|---|---|---|
| Leitura C15 | `pnl/api/RealBotHoldings`, `HoldingsResult`, `BotHolding`, `UnavailableReason`; `pnl/application/RealBotHoldingsReader` | Transação própria `REQUIRES_NEW`, `REPEATABLE READ`, só de leitura; `ReadDeadline` de 250 ms criado antes de pedir a conexão; S0 `statement_timeout`, S1 `botsOf` (fixa o instantâneo), S2 carteiras, S3 `RealLedger.view`, S4 ordens por id até `ORDER_PREFETCH_THRESHOLD = 32`, num só comando com `= ANY` (`RealExecutionPort.settledOrdersById`, `JdbcRealExecutionStore.settledByIds`), e pelo fluxo de C13 acima; ordem não achada guardada num cache negativo da própria leitura (revisão R-01); S5 `openSells`, S6 `available = max(0, own − open)` com operações exatas; conferência final do prazo depois do commit; toda falha vira `Unavailable` (`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`); transação já ativa na thread é `IllegalStateException`. |
| Atribuição | `pnl/application/EpochAttribution` (extraída), `AttributedCostReader` | O acumulador de época da U2 saiu da classe interna `Accumulation` para uma classe de pacote, usada pelos dois leitores sem cópia. |
| C16 | `realexecution/api/OpenSell`, `OpenSellSource`, `RealExecutionPort.openSells`, `JdbcRealExecutionStore.OPEN_SELLS_SQL`, `RealExecutionService` | Ordens SELL do bot em andamento e decisões SELL `PENDING` (adiadas incluídas), num `UNION ALL` com estados, lado e `PENDING` literais e `tenant_id = :tenant` nas duas partes. |
| Migrações | `0026-bot-cycle-own-quantity-outcomes.sql` (2 changesets, `SET LOCAL lock_timeout = '5s'`), `0027-real-decision-request-bot-pending-sell-idx.sql` (`CONCURRENTLY`), `db.changelog-master.yaml` | Regra de `bot_cycle.outcome` com `REAL_NO_OWN_QUANTITY` e `REAL_OWN_QUANTITY_UNAVAILABLE` (`NOT VALID` e depois `VALIDATE`); índice parcial das decisões SELL `PENDING` por bot. |
| Domínio | `bots/domain/OwnSellCap`, `SellCapOutcome`, `OwnQuantityNote`, `OwnQuantityReason`, `OwnHoldings`, `OwnPosition`, `OwnDecisionInputs` | Funções puras: o corte (`NotApplicable`, `Sell`, `NoOwnQuantity`, `Unavailable`; marca conferida antes da quantidade; pedido `piso(quantidade × 10^decimais)` com `longValueExact`) e as entradas próprias (`heldNotSellable`, custo `piso(custo × disponível ÷ própria)` em `BigInteger`). |
| Flag | `bots/api/OwnQuantitySell`, `bots/infra/OwnQuantitySellProperties`, `application.yml` | `${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}`; sem rota, actuator gravável ou tabela. |
| Ciclo | `bots/worker/DispatchedBot`, `HoldingsSnapshot`, `OwnHoldingsStep`, `OwnHoldingsStreaks`, `CycleRunner`, `RealCycle`, `SlotDispatcher`; `bots/infra/JdbcBotRepository` (`activeDispatch`, `precheckRow`, `slotClaimed`) | P0 a P3 sem trava antes da transação, M0 da linha de P1, leitura de C15 com 250 ms; sob a trava, M1 do `lockBot`; tokens sem `heldNotSellable` antes de `market.read`; registro de abertura preservado (BR1.8); veredito do corte antes de `decisions.insert`; `step.submit` só com venda maior que zero; `NotRead` na corrida da BR2.5; uma exceção na fase prévia (P0 a P3, contadores, sequências) nunca sai do `OwnHoldingsStep`: vira `READ_FAILED` contado e registrado, sem venda, e as compras do ciclo seguem (revisão R-02, BR2.6). |
| Contexto da decisão | `decision/infra/OwnQuantityContext`, `DecisionJson.write(request, outcome, context)` | `context.ownQuantity` (`read`, `reason`, `mints`, `cap`) só nas decisões REAL com a flag ligada; `write(request, outcome)` e `read` sem mudança. |
| Métricas | `bots/infra/OwnQuantityMetrics` | `real_own_holdings_reads`, `real_own_holdings_unavailable{reason}`, `real_cycle_sell_capped{outcome}` (7 séries com 0) e o gauge `real_own_holdings_stuck_bots`, só no worker. |
| C7 | `bots/web/BotDtos` (`realOwnQuantitySell`), `BotController`, `openapi-guest.yaml`, `apps/web/src/lib/api/schema.d.ts` | Campo sempre enviado e fora de `required` (conflito 2). |
| Tela | `apps/web/src/features/bots/BotList.tsx`, `mode-messages.ts` | `<p role="status" aria-label=… className="notice" data-testid="bots-own-quantity-notice">` com a flag ligada e o filtro diferente de "Simulado". |
| Observabilidade | `infra/observability/real-ops-alerts.yml`, `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml`, `infra/terraform/modules/observability/real-ops.tf` e `app-metrics.tf` | Regras `RealOpsOwnHoldingsUnavailable` (razão `[10m]` `> 0.05`, `for: 15m`) e `RealOpsOwnHoldingsStuckBots`; gêmeos com `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)` (600 s, 2 de 2) e `Minimum` (300 s, 2 de 2); lista de permissão; saída `alarm_expressions`. |
| Terraform da flag (F4) | `infra/terraform/main.tf`, `environments/devnet.tfvars.example`, `mainnet.tfvars.example`, `modules/services/outputs.tf`, `tests/observability_metrics.tftest.hcl` | `real_own_quantity_sell_api` e `_worker` (`bool`, `false`, validação "worker só com a API"); `MEMETRADE_REAL_OWN_QUANTITY_SELL` em `api_environment` e `worker_environment`; saída `api_worker_container_environment`; 3 `run` novos e as asserções da U4. Nenhum `tfvars` real. |
| Runbook | `docs/runbooks/real-operations.md` | Seções 10 a 13. |
| Textos de design | `functional-design/rules.md`, `functional-spec.md`, `entities.md`; `inception/contract-design/contract-summary.md`; `nfr-requirements/*.md` e `traceability.json`; `nfr-design/traceability.json` | Alinhamentos A1 a A19 (ver abaixo). |

## Testes antes do código: vermelho registrado

| Momento | Comando | Vermelho |
|---|---|---|
| Etapa 6, Fase A (unidade) | `mvnw test -Dtest='OwnSellCapTest,OwnDecisionInputsTest,RealBotHoldingsReaderTest,OwnHoldingsStepTest,OwnQuantityArchitectureTest,OwnHoldingsSqlNoLockTest,OwnHoldingsSqlTenantTest'` | `COMPILATION ERROR` na compilação dos testes: 184 `cannot find symbol` (`HoldingsResult` 16, `RealBotHoldings` 13, `RealBotHoldingsReader` 9, `UnavailableReason` 7, `OwnQuantityMetrics` 6, `OwnDecisionInputs` 6, `OwnQuantitySell` 5, `OwnPosition` 3, `OwnHoldingsStep` 3, `BotHolding` 3, `OwnHoldingsStreaks` 2, `OpenSell` 2, `OpenSellSource` 2, `PrecheckRow`, `OwnQuantitySellProperties`, `OwnHoldings`). Nenhum código de produção da U4 existia. |
| Etapa 6, Fase A (integração) | `mvnw verify -Dit.test='OwnQuantitySellIT,OwnQuantityNoOwnIT,RealOpenSellsIT,RealBotHoldingsParityIT'` | `COMPILATION ERROR` (o mesmo módulo de testes): todos os ITs da Fase A (Etapas 3 a 5) ficam vermelhos pela mesma falha de compilação. |
| Etapa 20, T15 a T17 | `jest src/features/bots/BotList.test.tsx -t "notice of the own quantity"` | 4 falhas por asserção (T15 ×3 e T17: o aviso não existia); os 3 casos do T16 passam porque só conferem a ausência. |
| Revisão R-02, falha na fase prévia | `mvnw verify -Dtest=OwnHoldingsStepTest` e `-Dit.test=OwnHoldingsPrePhaseFailureIT` | Unidade: 3 erros (as exceções de `precheckRow`, `slotClaimed`, `systemPaused`, da leitura e das sequências saíam do passo). IT: 2 falhas por asserção, `expected: COMPLETED` (o slot terminava `FAILED` e a compra do ciclo se perdia). |
| Etapa 23, T20 | `mvnw verify -Dtest='CiPromtoolDigestTest,AlertRuleTestAnnotationsTest' -Dit.test='RealOpsOwnHoldingsAlertRuleIT'` | `promtool test rules` falhou com `got:[]` em todos os casos que esperam alerta (as duas regras não existiam); `AlertRuleTestAnnotationsTest` 1 falha e 1 erro; `CiPromtoolDigestTest` verde (só lê o `ci.yml`). |

As migrações (Etapa 7) tiveram os testes escritos antes dos arquivos SQL; eles entraram na mesma execução que já as continha e ficaram verdes depois de dois ajustes de teste (ver "Desvios").

## Resultados (verde)

**Backend, unidade** (lista de `unit-test-instructions.md`, mais `LockClauseSourceTest` e `U4ArchitectureTest`): 196 testes, 0 falhas. Inclui `OwnSellCapTest` (11), `OwnDecisionInputsTest` (8), `RealBotHoldingsReaderTest` (12, com o caso do cache negativo), `OwnHoldingsStepTest` (9, com os 3 casos da R-02), `DecisionJsonOwnQuantityTest` (4), `OwnQuantityMetricsTest` (5), `OwnHoldingsStreaksTest` (6), `OwnQuantityLogRedactionTest` (1), `OwnQuantityArchitectureTest` (7, com a amostra que faz R1 falhar), `RealOpsAlarmParityTest` (8, `hasSize(16)`), `GuestOpenApiContractTest` (18) e as regras ArchUnit e testes das Units anteriores.

**Backend, integração da U4** (Testcontainers, PostgreSQL 17; Docker 29.9 local): `RealOpenSellsIT` (12), `RealOpenSellsPlanIT` (1), `RealBotHoldingsParityIT` (2), `RealBotHoldingsOrderPathParityIT` (3), `RealBotHoldingsTenantIT` (3), `RealBotHoldingsIsolationIT` (1), `RealBotHoldingsDeadlineIT` (2), `Migration0026IT` (3), `Migration0026LockTimeoutIT` (1), `Migration0027IndexRestartIT` (1), `AnonymizationMigrationIT` (6), `OwnQuantitySellDefaultIT` (4), `ActiveDispatchIT` (3), `OwnQuantitySellIT` (2), `OwnQuantityNoOwnIT` (3), `OwnQuantityOpenSellsIT` (3), `OwnHoldingsUnavailableIT` (5), `OwnHoldingsPrePhaseFailureIT` (2, R-02), `OwnQuantitySettlementRaceIT` (2), `OwnQuantityOtherBotsShareIT` (3 sementes × 50 sequências), `OwnQuantityConditionalGapIT` (1), `OwnQuantityStaleSnapshotIT` (2), `RenewMarkOnSellIT` (1), `OwnQuantityOneTransactionIT` (2), `OwnQuantityFlagOffIT` (1), `OwnQuantityPaperUnchangedIT` (1), `OwnQuantityFlagFlipIT` (1), `OwnQuantityExitsIT` (1), `OwnQuantityMetricsIT` (1), `BotListOwnQuantityFieldIT` (2), `BotListOwnQuantityFlagOnIT` (1), `RealOpsOwnHoldingsAlertRuleIT` (T20, 10 casos do `promtool` verdes), `RealBotHoldingsMeasurementIT` (2, ver "Medida"), e os de contexto `ApplicationContextIT`, `ProcessRoleContextIT`, `RealApiWiringIT`, `RealWorkerWiringIT`.

**Regressão (testes por risco e de Units anteriores):** ver "Regressão" no fim deste arquivo.

**Web:** `lint` verde; `typecheck` verde; `prettier --check src e2e` verde; `jest --coverage`: 86 suítes, 1004 testes verdes, linhas 94,43% (piso 80%); `gen:api` e `check:api` verdes. O `format:check` do script falha **só** por `apps/web/aidlc/.aidlc-sessions/…binding.json`, um arquivo de sessão do AI-DLC não rastreado que já existia antes desta geração (estava no `git status` inicial como `?? apps/web/aidlc/`); não é deste trabalho e não foi tocado.

## Medida (Etapa 11) — achado para o fundador

### O que mudou na medida (revisão R-01)

A primeira medida usava o `Clock` do contexto de teste, que fica parado. Com ele, o `ReadDeadline` não age: cada comando recebia 250 ms inteiros e a conferência depois do commit nunca falhava. Por isso a semente B tinha 300 leituras acima de 250 ms e só 37 `BUDGET_EXCEEDED`. Os números daquela medida (p99,9 de 386 ms na semente A e 638 ms na B) **não valem como taxa de produção** e foram substituídos pelos de baixo.

Agora o `RealBotHoldingsMeasurementIT` usa o relógio do sistema (`HoldingsReaders.withClock(context, Clock.systemUTC())`), também no leitor do ciclo medido. Por semente, ele faz duas amostras com o mesmo leitor:

- **com o prazo de produção (250 ms):** p50, p99 e p99,9, com o tamanho da amostra no log, e a taxa de `BUDGET_EXCEEDED` que o worker veria;
- **sem prazo que corte (30 s):** o tempo da leitura inteira e quantas passam de 250 ms, para comparar antes e depois de uma mudança.

Continua só informativa, sem portão de tempo. O padrão continua 10.000 leituras (`-Du4.measure.reads`); novos parâmetros: `-Du4.measure.warmups` (200) e `-Du4.measure.cycles=0`, que pula o ciclo. Com 300 leituras o p99,9 é o maior valor da amostra, e o log diz isso.

### Números nesta máquina (Windows, Docker Desktop; 300 leituras, 50 aquecimentos, uma execução por linha)

Sementes com 730 dias, 5 bots, 10 mints, cerca de 14.400 ordens. A semente A tem zeragens (S4 por id); a B não tem nenhuma (S4 pelo fluxo de C13, cerca de 14.300 linhas por leitura).

| Momento | Semente | Com 250 ms: p50 / p99 / p99,9 | `BUDGET_EXCEEDED` | Sem prazo: p50 / p99 / p99,9 | Acima de 250 ms sem prazo |
|---|---|---|---|---|---|
| Antes (código revisado) | A | 219 / 256 / 283 ms | 20 de 300 (6,7%) | 208 / 237 / 240 ms | 0 de 300 |
| Antes (código revisado) | B | 252 / 261 / 263 ms | 300 de 300 (100%) | 420 / 536 / 751 ms | 300 de 300 |
| Depois da mudança 1 (`= ANY`) | A | 220 / 251 / 254 ms | 7 de 300 (2,3%) | 210 / 245 / 251 ms | 2 de 300 (0,7%) |
| Depois da mudança 1 (`= ANY`) | B | 253 / 271 / 519 ms | 300 de 300 (100%) | 418 / 505 / 548 ms | 300 de 300 |
| Depois da mudança 2 (cache negativo) | A | 232 / 253 / 255 ms | 28 de 300 (9,3%) | 226 / 353 / 358 ms | 33 de 300 (11%) |
| Depois da mudança 2 (cache negativo) | B | 253 / 274 / 285 ms | 300 de 300 (100%) | 419 / 476 / 496 ms | 300 de 300 |
| Repetição do estado final | A | 239 / 256 / 257 ms | 79 de 300 (26,3%) | 239 / 274 / 291 ms | 77 de 300 (25,7%) |
| Repetição do estado final | B | 257 / 277 / 343 ms | 300 de 300 (100%) | 481 / 823 / 898 ms | 300 de 300 |

Leitura dos números:

- **Semente B (pior caso, sem zeragem): 100% de `BUDGET_EXCEEDED` em todas as rodadas.** A leitura inteira leva cerca de 420 ms de mediana. O custo é o fluxo de C13, que lê todo o histórico de ordens da pessoa a cada leitura. Nenhuma das duas otimizações mexe nesse caminho. Com a flag ligada, um bot nessa situação nunca venderia: a falha é fechada, e o alarme de razão (5%) e o de bot preso disparariam.
- **Semente A:** a taxa variou de 2,3% a 26,3% entre rodadas (6,7%, 2,3%, 9,3% e, na repetição, 26,3%, rodada logo depois de cerca de 400 outros testes, com a máquina mais carregada). A variação entre rodadas desta máquina é maior que o efeito das mudanças: a terceira linha piorou sem nenhuma mudança que pudesse piorar o caminho. Uma execução de 300 leituras não separa os efeitos. A comparação firme é a medida oficial.
- **Mudança 1** (S4 por id num só comando, `real_order_id = any(cast(:ids as uuid[]))`, em vez de até 2 comandos por ordem): troca até 64 idas ao banco por 2. É o único ganho que esta máquina mostrou com alguma clareza (semente A, de 6,7% para 2,3%).
- **Mudança 2** (cache negativo dentro de uma leitura em `EpochAttribution.order`): uma ordem que o índice não tem passa a ser procurada uma vez por leitura, não uma vez por lançamento. Nestas sementes quase não há ordem ausente, então o efeito esperado é nulo, e foi o que se viu. Ela protege o caso de ordens expurgadas.
- O orçamento de 250 ms e o `ORDER_PREFETCH_THRESHOLD = 32` **não mudaram**; são decisões do fundador (F5). O limite de 32 continua sem confirmação.

### Equivalência das duas mudanças

O resultado da leitura é o mesmo de antes. O comando único usa o mesmo filtro do `findForOwner` mais os estados liquidados (`tx_signature` não nulo e `CONFIRMED` ou `FAILED`). Uma ordem que ele não devolve continua `UNOWNED`, como antes. O cache negativo vale só dentro de uma leitura num único instantâneo (`REPEATABLE READ`), em que uma segunda busca daria a mesma resposta. O caminho da U2 (`AttributedCostReader`) não usa o cache (construtor de 7 argumentos, `absent = null`).

Continuam verdes: `RealBotHoldingsOrderPathParityIT` (32 e 33 ordens, os dois caminhos de S4 iguais), `RealBotHoldingsParityIT` (T12), `RealBotHoldingsTenantIT`, `RealBotHoldingsDeadlineIT`, `AttributedCostConsistencyIT` e `AttributedCostTenantIT`. Também passam as duas checagens de exatidão da própria medida, nas três rodadas: os dois caminhos de S4 iguais e a soma das quantidades próprias igual à carteira. Há um teste de unidade novo no `RealBotHoldingsReaderTest`: uma ordem ausente citada por dois lotes é buscada uma vez, no comando único, e nunca pelo `settledOrder`.

## Testes não executados e por quê

- **Terraform:** `terraform` e Checkov não estão instalados nesta máquina. Os 3 `run` novos (`own_quantity_flag_default`, `own_quantity_flag_api_only`, `own_quantity_flag_worker_without_api` com `expect_failures`) e as asserções novas de `every_alarm_reads_a_published_metric` foram escritos e **não rodaram**. O responsável roda `terraform -chdir=infra/terraform fmt -check -recursive`, `validate` e `test` à mão (`team.md`, Testes da IaC).
- **Ponta a ponta:** os dois casos novos do `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts` (U4 1 e U4 2, 360 px) rodam só pelo `bash scripts/verify.sh`, que não foi executado nesta geração.
- **`mvnw verify` completo** (JaCoCo de 80% e Spotless check do módulo inteiro): não rodado; as execuções foram por classe com `-Djacoco.skip=true`. A prova do piso é o `verify` completo ou o `ci` do commit.
- **Medida completa** (10.000 leituras por semente, 3 execuções): fica para o Build and Test.

## Desvios do plano

1. **Teste do ciclo montado no contexto da API.** O `CycleRunner`, o `RealCycle` e o `OwnHoldingsStep` são beans só do worker; os ITs do ciclo (Etapas 4 e 5) usam a base `AbstractRealExecutionIT` (rede, roteador e TxSigner simulados) e montam o ciclo real com `OwnQuantityCycle` (teste). Duas bordas são roteirizadas: o motor de decisão (vende a posição inteira que recebeu, como o motor real faz numa saída) e o leitor de mercado do ciclo (as janelas não interessam ao corte). O leitor de C15, a barreira, o `RealDecisionStep`, a fila, o driver, a confirmação e o ledger são os reais. O T14 (`OwnQuantityPaperUnchangedIT`) roda no contexto do worker (`AbstractU5WorkerIT`) com o motor real.
2. **T12 (paridade).** O `RealBotPnl` não expõe a quantidade e o custo restantes de um titular. A paridade do `RealBotHoldingsParityIT` é: (a) um histórico de 3 bots e 4 mints com resultado calculado à mão pelas regras da U2 (venda própria, liquidação e condicional rateadas, zeragem); (b) a soma das quantidades próprias igual à posição da carteira; (c) a soma dos custos próprios igual ao custo da carteira pelo caminho `GATE` da U2, que lê o histórico inteiro com o mesmo `RealCostMethod`.
3. **Três ordens SELL em andamento do bot medido** não cabem no índice único `uq_real_order_bot_in_progress` (uma por bot). A medida usa 1 ordem `AMBIGUOUS` do bot medido e 2 ordens `SENT` de outros bots; as 3 decisões `PENDING` (1 adiada) são do bot medido.
4. **Localização de duas classes:** `RealBotHoldingsMeasurementIT` e `OwnQuantityLogRedactionTest` ficaram em `bots/worker` (não em `pnl/` e `bots/`), porque usam o ciclo montado e o método de pacote `CycleRunner.cut`.
5. **`OwnQuantityMetricsIT`** confere as 8 séries no registro de métricas do worker (o mesmo que o `/actuator/prometheus` exporta com o sufixo `_total`) em vez de chamar a porta 8082: o contexto de teste do worker não sobe servidor HTTP. A ausência na API está no `BotListOwnQuantityFieldIT`.
6. **`RealBotHoldingsIsolationIT`:** o saque, a liquidação e a fila rodam pelos serviços reais (não pela rota HTTP) enquanto a leitura fica parada em S5; o teste confere que os três terminam, qualquer que seja a resposta de negócio. A ordem "primeiro comando é o `set local statement_timeout`, primeira consulta é a de S1" é conferida no `RealBotHoldingsReaderTest` (ordem das chamadas), não no log do driver.
7. **R2 da regra ArchUnit:** `riskcontrol` já depende de `pnl.api` (custo da carteira da U2 e `PortfolioRiskView`); a regra ficou "`realexecution`, `realexits` e `botwallet` não dependem de `pnl`" e "`riskcontrol` não depende de `RealBotHoldings`, `HoldingsResult` nem `BotHolding`".
8. **`NeverSumModesArchitectureTest` alterado:** a regra do próprio teste exige que todo tipo REAL novo do `pnl.api` entre na lista REAL no mesmo commit; entraram `RealBotHoldings`, `HoldingsResult` (com os dois casos) e `BotHolding`.
9. **`GuestOpenApiSlice` alterado:** o slice do contrato ganhou o `@MockitoBean OwnQuantitySell`, porque o `BotController` passou a lê-lo.
10. **`CycleRunner.runDispatched(DispatchedBot, Instant)`** em vez de uma sobrecarga de `run`: a sobrecarga deixava ambíguo um `when(runner.run(any(), any()))` do `RecordedSwapFirstCycleTest`, que não muda.
11. **Ajustes de teste durante o verde** (o código de produção não mudou por eles): a ordem por `created_at` das decisões mistura o relógio fixo do teste com o relógio real das fixtures (asserções sem ordem); a compra depois da virada no T19 precisa de um slot depois do assentamento; o `migrate` barrado lança `BeanCreationException`, não `IllegalStateException`; o T8 passou a assentar a venda pelo caminho real (consumidor, driver e confirmação), porque o login da aplicação não pode atualizar `real_order` à mão; a prova de "0 gravações" do `RealOpenSellsIT` usa `txid_current_if_assigned()` numa transação só de leitura.
12. **Log do `READ_FAILED`:** o `WARN` do `OwnHoldingsStep` leva `botId`, `reason`, `elapsedMs` e `streak`; a classe da exceção (`exceptionClass`) fica num `DEBUG` do leitor, porque o `HoldingsResult` não carrega a exceção.

## Correções da revisão 1 (READY com 1 Major e 6 Minor)

- **R-01 (Major), medida:** relógio do sistema no IT de medida, com taxa de `BUDGET_EXCEEDED` e tempo sem prazo (ver "Medida"). Duas otimizações dentro do desenho aprovado:
  - **mudança 1:** S4 por id num só comando. O contrato C13 ganha o método `RealExecutionPort.settledOrdersById`; a implementação padrão busca uma ordem por vez, e o serviço usa `JdbcRealExecutionStore.settledByIds` com `= any(cast(:ids as uuid[]))`;
  - **mudança 2:** cache negativo por leitura em `EpochAttribution` (construtor novo de 8 argumentos; o de 7, usado pela U2, não muda).

  O orçamento de 250 ms e o limite de 32 não mudaram.
- **R-02 (Minor), fase prévia (BR2.6):** o `OwnHoldingsStep` captura qualquer `RuntimeException` da fase prévia. Antes da leitura, o caso conta como leitura com `READ_FAILED`, sobe a sequência e escreve um `WARN` `event=REAL_OWN_HOLDINGS_UNAVAILABLE … phase=PRE exceptionClass=…`, sem a mensagem da exceção. O passo devolve:
  - `Read(m0, READ_FAILED)` quando M0 já foi lido;
  - `Skipped("PRE_PHASE_FAILED")` quando não foi, e o ciclo transforma isso em `NotRead` sob a trava.

  Nos dois casos o ciclo não vende nada da carteira e a compra segue. Depois de uma leitura boa, a falha da contabilidade mantém a leitura (`WARN` `event=REAL_OWN_HOLDINGS_STEP_FAILED`). Primeiro os testes, com o vermelho registrado: 3 casos novos no `OwnHoldingsStepTest` e o `OwnHoldingsPrePhaseFailureIT`, com 2 casos. Para o IT, o harness `OwnQuantityCycle` ganhou `stepBots`, um dublê que delega ao repositório real. Um `spy` não serve, porque o bean é um proxy do Spring.
- **R-05 (Minor), limpeza:**
  - `OwnDecisionInputs.buyBlockingMints()` foi removido. Nenhum código de produção o usava, e o plano não o pede: o bloqueio de compra vem de tirar o mint de `request.tokens()`. A asserção dele no `OwnDecisionInputsTest` saiu; o teste já confere a união `ownPositions ∪ heldNotSellable`;
  - o comentário do corte no `CycleRunner`, quebrado pelo Spotless, agora ocupa uma linha.
- **R-03, R-04, R-06, R-07:** sem mudança de código. O vermelho por mutação não foi feito. A execução do Terraform, o `verify` completo, a confirmação dos alinhamentos A1 a A19 e a limpeza de `apps/web/aidlc/` ficam com o responsável (ver "Pendências").

## Decisões de implementação

- Domínio sem `pnl.api`: o corte e as entradas usam tipos próprios (`OwnHoldings`, `OwnPosition`), porque a R1 só permite `bots.worker` ler `RealBotHoldings` e `HoldingsResult`; a conversão fica no `CycleRunner`.
- `NotRead` é decidido no `CycleRunner`: um `Skipped` do passo prévio com o bot REAL rodando sob a trava e a flag ligada vira `OwnHoldings.NotRead`, com o `WARN` `precheck=NOT_READ` e sem métrica.
- O veredito do corte é calculado para toda decisão REAL com a flag ligada (`NotApplicable` para o que não é SELL) e vai para `context.ownQuantity.cap`.
- `NO_OWN_QUANTITY` sobe 1 por mint tirado dos tokens com disponível 0 (F2) e 1 no corte com venda 0.

## Par de cores do aviso

O aviso usa a classe `notice`, a mesma do `bots-disclaimer`: texto `--text` `#e8eaed` sobre `--surface-raised` `#1f232c`, razão de contraste **13,05:1** (mínimo 4,5:1), com a borda `--accent`; nenhum significado só por cor.

## Alinhamentos feitos (Etapas 27 e 28)

A1 (`rules.md` BR2.7 e resumo), A2 a A10 e A15 (`functional-spec.md`: T20, E8, contratos, migrações, testes da tela antes do código, F6 passo 4, F3 passo 1, F1 passos 0 e 1, F2, `SlotDispatcher`), A7 e A12 (`rules.md` BR5.5 e BR2.5), A10 e A11 (`entities.md` ENT-408 e ENT-404), A13 e A14 e a nota do C7 (`contract-summary.md`), A16 (NFR2.7), A17 (NFR2.18 nova, NFR2.13, NFR7.6 com os casos 9 e 10, NFR8.2 com cerca de US$ 1,90, `hasSize(16)`, `nfr-requirements/traceability.json` e o `reverse` do `nfr-design/traceability.json`), A18 (NFR2.12) e A19 (NFR1.5).

## Pendências para o responsável

1. **Medida acima da meta** (ver "Medida"): rodar a medida oficial no Build and Test (10.000 leituras, 3 execuções, em Linux, de preferência num PostgreSQL comparável ao RDS).

### Para o fundador

- **F5. Taxa e orçamento da leitura, antes de ligar a flag.** Com o relógio real, a semente sem zeragem (B) dá 100% de `BUDGET_EXCEEDED` com 250 ms, e a leitura inteira leva cerca de 420 ms de mediana nesta máquina. A semente com zeragens (A) fica perto do limite de 5% do alarme. Depois da medida oficial, decidir entre:
  - (a) aceitar a taxa medida;
  - (b) aumentar o orçamento (a leitura fica fora da transação do ciclo, então um prazo maior só alonga o ciclo e não segura trava);
  - (c) mudar o caminho do fluxo de C13 da semente B, por exemplo com um limite de época por mint ou buscando só as ordens nomeadas, sempre com `= ANY`, inclusive acima de 32;
  - (d) reabrir a restrição "nada guardado entre leituras" do ADR-004.

  Esta geração não mudou o orçamento, o limite de 32 nem o ADR. A flag continua desligada por padrão, então nada disso bloqueia o commit.
- **Confirmar no gate** os alinhamentos A1 a A19 feitos em artefatos de estágios já aprovados (revisão R-06), e a diferença da F4 com o `team.md`.
2. **Terraform:** rodar `fmt -check`, `validate`, `test` e o Checkov à mão; o resultado vai para o Build and Test.
3. **F4 contra o `team.md` (Deployment, Flags):** o `team.md` diz que as flags mudam por commit em `main`; com a F4, o padrão `false` fica versionado e o valor ligado fica no `tfvars` de cada conta, fora do repositório. Esta geração **não** editou o `team.md`; a diferença vai para o ritual de aprendizado.
4. **Ponto de controle:** `bash scripts/verify-checkpoint.sh` no commit que o responsável pedir (o `verify.sh` roda os dois casos novos do spec `zz-paper-real-unico`).
5. Nenhuma mudança em `.github/workflows/` nem em `scripts/verify.sh` é necessária: o laço do `promtool check rules` do `ci.yml` já confere `real-ops-alerts.yml` (agora com 16 regras), o T20 roda dentro do `mvnw verify` do job `backend` e o spec `zz-paper-real-unico` já está no `verify.sh`.

## Regressão

**Depois das correções da revisão 1** (uma só chamada do Maven, depois do `spotless:apply`; `verify` com `-Djacoco.skip=true` e o `spotless:check` do módulo verde): **unidade 200 testes** (a lista de `unit-test-instructions.md` mais `U5SettingsTest`, `U8SettingsTest` e `RecordedSwapFirstCycleTest`; inclui os 3 casos novos do `OwnHoldingsStepTest` e o caso novo do `RealBotHoldingsReaderTest`) e **integração 238 testes**, 0 falhas, `BUILD SUCCESS`. A integração inclui todos os ITs da U4 (com o `OwnHoldingsPrePhaseFailureIT`), as regressões por risco da lista abaixo, os ITs de contexto e a repetição da medida.

Execução anterior às correções, guardada como registro:

Execução final, depois do `spotless:apply`, numa só chamada do Maven (`verify` com `-Djacoco.skip=true`): **unidade 18 testes** (`U5SettingsTest`, `U8SettingsTest`, `RealCycleOrderCapTest`, `RecordedSwapFirstCycleTest`) e **integração 226 testes, 0 falhas, `BUILD SUCCESS`**. A lista junta os ITs da U4 e os testes obrigatórios por risco e de Units anteriores: `CycleIT`, `CycleFailureIT`, `LockOrderIT`, `RecordedSwapCycleIT`, `RealCycleAttributionIT`, `AttributedCostConsistencyIT`, `AttributedCostTenantIT`, `AttributionFailureGateIT`, `DecisionConsumerIT`, `DecisionConsumerExitIT`, `ConfirmationAndAmbiguousIT`, `OrderDriverIT`, `RealSettlementIT`, `LiquidationIT`, `LiquidationExitIT`, `PersonSaleExitIT`, `KillSwitchIT`, `WithdrawalLimitIT`, `BotWalletRoutesIT`, `RealOffByDefaultRoutesIT`, `RealReleaseRouteOffIT`, `PaperExecutionIdempotencyIT`, `RealPnlTenantIsolationIT`, `RealNeverPaperIT`, `RealPositionEpochAppendOnlyIT`, `Migration0023IndexRestartIT`, `BotModePathIT`, `BotListMarketIT`, `BotRepositoriesIT`.

Itens do plano que ficam abertos: Etapa 11 (`ORDER_PREFETCH_THRESHOLD` não confirmado, ver "Medida"), Etapa 30 (`mvnw verify` completo com JaCoCo e o ponto de controle `verify-checkpoint.sh`, que dependem do commit pedido ao responsável).

## Loop-back 1 — Fase G (decisão F6 do fundador, 2026-11-25)

O Build and Test deixou U4-NFR1.1, U4-NFR1.13 e a premissa dos alarmes como `Not Met`, com a CI Linux do `fc65b7f` (n=300): na semente A, 18,3% das leituras passaram de 250 ms; na semente B, 100%. A causa é S4: acima de 32 ordens, a leitura percorria o fluxo de C13, que lê o histórico inteiro de ordens. O fundador decidiu (F6) duas coisas: o orçamento passa a 500 ms, e S4 busca as ordens por id em qualquer tamanho. As Etapas 32 a 35 mexem só na U4.

### Etapa 32 — teste antes do código (vermelho registrado)

`RealBotHoldingsOrderPathParityIT` (alterado):

- casos de 32 e 33 ordens nas sementes A (com uma zeragem e marca de época antes dos lotes) e B (sem zeragem), mais um caso de 5.000 ordens distintas;
- os valores (`own`, custo, `open`, `available`) são conferidos contra um oráculo calculado da própria semente e contra o leitor de produção;
- a contagem é feita num `DataSource` instrumentado do teste (`StatementLog`, novo, e `HoldingsReaders.counted`): no máximo `ceil(n / 1.000)` buscas por id, nenhuma busca avulsa (`settledOrder`) e nenhum comando do fluxo de C13;
- o caso T12 confere os valores calculados à mão e nenhum fluxo.

**Vermelho** (`mvnw verify -Dit.test=RealBotHoldingsOrderPathParityIT`, antes da Etapa 33): 6 casos, **3 falhas por asserção**, todas `[the stream of C13 is never used by S4] expected: 0L but was: 1L`, nos casos A/33, B/33 e B/5.000. Os casos de 32 ordens e o T12 passaram. As asserções de valor vêm antes da contagem. Elas passaram nos três casos que falharam, que ainda rodavam pelo caminho antigo (o fluxo). Isso prova que o oráculo é igual ao caminho antigo. Depois da troca, os mesmos valores passam no caminho novo, então os dois caminhos dão o mesmo resultado.

### Etapa 33 — S4 por id em lotes

- `RealBotHoldingsReader`: S4 busca sempre por `RealExecutionPort.settledOrdersById`, em lotes de até `ORDER_BATCH = 1.000` ids. Antes de cada lote, aplica o prazo que resta como `statement_timeout`; entre os lotes, faz `deadline.check()`. Saíram o fluxo de C13, o `ORDER_PREFETCH_THRESHOLD` e o construtor de pacote com o limite. O id ausente segue a regra de hoje: o lote marca como ausente o que não veio, e o cache negativo da `EpochAttribution` continua.
- `JdbcRealExecutionStore`: o comando de `settledByIds` virou a constante `SETTLED_BY_IDS_SQL`. O `RealOpenSellsPlanIT` ganhou o `EXPLAIN (FORMAT JSON)` de um lote de 1.000 ids, sobre 14.600 ordens: usa `real_order_pkey` e nunca `Seq Scan` em `real_order`.
- Testes ajustados: o `RealBotHoldingsReaderTest` troca o caso "por id até o limite e fluxo acima" por "1.001 ordens, dois lotes de 1.000 e 1, nunca fluxo nem busca avulsa". `HoldingsReaders.of(context, threshold)` saiu, e os chamadores (`OwnHoldingsUnavailableIT`, `RealBotHoldingsDeadlineIT`, `RealBotHoldingsIsolationIT`) usam `of(context, port)`. No `RealBotHoldingsMeasurementIT`, a conferência das sementes A e B compara o leitor contado com o de produção, soma as quantidades contra a carteira e conta zero fluxo.

### Etapa 34 — orçamento de 500 ms

- `OwnHoldingsStep.BUDGET = Duration.ofMillis(500)`, com o javadoc citando F6.
- Testes: `OwnHoldingsStepTest` (500 ms); `RealBotHoldingsDeadlineIT` (500 ms, `pg_sleep` cancelado em até 550 ms, a mesma folga de 50 ms de antes; a conexão do pool esgotado fica segura por 800 ms em vez de 400, para continuar maior que o prazo, o que deixa o teste mais exigente, não menos). No `OwnHoldingsUnavailableIT` nenhum valor de 250 ms estava fixado; só o chamador mudou.
- `RealBotHoldingsMeasurementIT`: `BUDGET` de 500 ms; a linha do log mostra `mode=budget500`, `target: p99.9 <= 500 ms` e `over500`. Os padrões da CI (`fc65b7f`) ficaram como estavam: 300 leituras, 20 aquecimentos, 0 ciclos.
- Alinhamento A20 dos textos de desenho, com a nota de que a mudança é a decisão F6 do fundador depois do Build and Test, nunca um afrouxamento desta etapa:
  - `nfr-requirements/performance-requirements.md` (NFR1.1, NFR1.7, item "0a" das decisões);
  - `nfr-requirements/observability-requirements.md` (NFR1.13 e a base do cálculo);
  - `nfr-design/performance-design.md` (o prazo, a seção "S4 depois do loop-back 1" e a tabela de custos);
  - `functional-design/rules.md` (BR2.1 e BR2.2);
  - `inception/contract-design/contract-summary.md` (C15, o prazo padrão e S4 por id).
- Premissa dos alarmes refeita com 500 ms em `nfr-design/observability-design.md`. A taxa normal de `BUDGET_EXCEEDED` continua em no máximo 0,1%; o limite de 5% continua 50 vezes acima dela; 3 indisponíveis seguidas continuam em cerca de 10⁻⁹. **Os limites (5% e o bot preso) não mudaram**, nem `real-ops-alerts.yml` nem `real-ops.tf`.

### Etapa 35 — medida e registros

**Medida local** (Windows com Docker Desktop; padrões da CI: 300 leituras, 20 aquecimentos, 0 ciclos; relógio do sistema; prazo de 500 ms). Com n=300, o p99,9 é o maior valor da amostra.

| Semente | Com 500 ms: p50 / p99 / p99,9 | `BUDGET_EXCEEDED` | Sem prazo: p50 / p99 / p99,9 | Acima de 500 ms sem prazo |
|---|---|---|---|---|
| A (com zeragens) | 106 / 140 / 157 ms | 0 de 300 (0%) | 106 / 129 / 140 ms | 0 de 300 |
| B (sem zeragem) | 418 / 503 / 507 ms | 4 de 300 (1,3%) | 415 / 461 / 469 ms | 0 de 300 |

Para comparar, a última medida local com 250 ms e o fluxo de C13 deu: semente A com p50 de 208 a 239 ms; semente B com p50 de 415 a 481 ms sem prazo e 100% de `BUDGET_EXCEEDED`.

- **Semente A:** a mediana caiu para cerca da metade (de cerca de 210 ms para 106 ms). Nenhuma leitura passou do prazo. A meta de 500 ms está cumprida com folga nesta máquina.
- **Semente B:** a busca por id tirou o fluxo, e a meta ficou no limite. Sem prazo, nenhuma leitura passou de 500 ms (p99,9 de 469 ms). Com o prazo, 4 de 300 (1,3%) passaram, porque o corte inclui o commit e a última conferência do relógio. A mediana quase não mudou (cerca de 415 ms): nessa semente o mint nunca zera, e o custo que sobra é S3 mais o acumulador, que percorrem cerca de 14.300 lotes abertos, não a busca das ordens (15 comandos por chave primária). Isso fica abaixo do limite de 5% do alarme, mas acima da cauda de 0,1% da meta, e é medido nesta máquina, que costuma ser mais lenta que a CI Linux.
- A medida que vale é a da CI do commit (300 leituras) e, antes de ligar a flag, a oficial (10.000 leituras × 3, Linux). Se a semente B continuar acima de 0,1% de `BUDGET_EXCEEDED` com 500 ms, o próximo passo já está nas opções do Build and Test (o cache por bot, a opção 2) e é decisão do fundador.

**Testes da rodada final** (uma só chamada do Maven depois do `spotless:apply`, `verify` com `-Djacoco.skip=true` e o `spotless:check` verde): **unidade 188 testes, integração 91 testes, 0 falhas, `BUILD SUCCESS`**.
- Unidade: as classes da U4, as regras ArchUnit, o `RealBotPnlTest` e o `RecordedSwapFirstCycleTest`.
- Integração: todos os ITs da U4 (cenários T1 a T20) e os de custo da U2 que usam `EpochAttribution` (`AttributedCostConsistencyIT`, `AttributedCostTenantIT`, `AttributionFailureGateIT`, `RealCycleAttributionIT`), mais o `RealPnlTenantIsolationIT` e a medida.

Fora a medida, só a U4 usa o `settledOrdersById`; os ITs que passam por ele (`RealBotHoldings*`) estão na lista.

### Desvios da Fase G

- O plano cita `pnl/application/RealBotHoldingsOrderPathParityIT.java`, mas o IT já estava em `pnl/` e ficou lá.
- A paridade antigo × novo é conferida por um oráculo calculado da semente. Ele foi provado igual ao caminho antigo na execução vermelha; o caminho antigo não fica no código para um teste de lado a lado.
- O `HoldingsReaders.counted` monta o `JdbcRealExecutionStore` e o `RealExecutionService` sobre o `DataSource` instrumentado, porque o bean do contexto não deixa contar os comandos. O ledger, o diretório de bots e as carteiras continuam nos beans do contexto e não entram na contagem.
- Ficaram com 250 ms, porque não fixam o prazo de produção (passam o orçamento como argumento a um leitor de teste): `RealBotHoldingsReaderTest` (relógio parado), `RealBotHoldingsParityIT`, `RealBotHoldingsTenantIT`, `OwnQuantitySellIT`, `HoldingsOutsideTheCycleSample` e `ReadDeadlineTest`. O plano lista só os três testes que fixavam o prazo.
- A medida oficial (10.000 leituras × 3, Linux) e a medida de 300 leituras na CI do commit continuam pendentes: a CI roda depois do commit que o responsável pedir.
