# Componentes lógicos — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` e da decisão do fundador F3.

Este arquivo liga os padrões de NFR desta Unit às peças do monólito modular onde eles moram: o que muda, o que é novo, os domínios de falha, o raio de impacto e a lista de alinhamentos para a Code Generation. Ele segue as respostas Q1 a Q4 [Q1] [Q2] [Q3] [Q4].

## Processos e recursos compartilhados

| Recurso | Quem usa na U4 | Mudança |
|---|---|---|
| Contêiner `worker` (tarefa `api-worker`) | ciclo REAL, RealBotHoldings, contadores | nenhuma; só a variável da flag |
| Contêiner `api` (mesma tarefa) | `BotPage.realOwnQuantitySell` (C7) | nenhuma; só a variável da flag |
| PostgreSQL (app) | conferências prévias, leitura no instantâneo, ciclo | regra de `bot_cycle` (`0026`) e 1 índice parcial (`0027`) |
| `memetrade-worker-pool` (36) | 1 conexão por ciclo de cada vez | nenhuma |
| `cycle-threads` (4) | o ciclo inteiro, inclusive a leitura | nenhuma |
| CloudWatch (`Eureka/App`) | 5 métricas, 1 alarme de expressão e 1 alarme padrão | acréscimo |
| Prometheus local e `promtool` | 2 regras e 1 arquivo de teste | acréscimo |

## Componentes

| Módulo | Componente | Situação | Papel | NFR |
|---|---|---|---|---|
| `bots.worker` | `SlotDispatcher` | muda | entrega `DispatchedBot(ref, market)` | NFR1.10 |
| `bots.worker` | `OwnHoldingsStep` (nome proposto) | novo | conferências prévias P0 a P3, M0, chamada do RealBotHoldings, contadores de leitura; devolve `HoldingsSnapshot` (`Skipped`, `Read(m0, HoldingsResult)`) | NFR1.4, NFR1.10, NFR2.3, NFR2.16 |
| `bots.worker` | `CycleRunner` | muda | chama o `OwnHoldingsStep` antes de `transactions.execute`; M1 do `lockBot`; tira `heldNotSellable` de `request.tokens()`; não fecha registro de abertura desses mints; avalia o corte antes de `decisions.insert` | NFR1.2, NFR3.4, NFR2.5 |
| `bots.worker` | `RealCycle` | muda | com a flag ligada: posições próprias, sem `walletCost.costOf`; SELL pelo veredito do corte | NFR1.2, NFR3.4 |
| `bots.worker` | `OwnHoldingsStreaks` (nome proposto) | novo (F3) | mapa em memória por bot das leituras indisponíveis seguidas; zera com leitura boa; despejo em 20 min; teto de 10.000; base do gauge | NFR2.18 |
| `bots.domain` | `OwnSellCap`, `SellCapOutcome`, `OwnDecisionInputs` | novo | funções puras do corte e da montagem das entradas | NFR2.4, NFR2.6 |
| `bots.infra` | `OwnQuantitySellProperties` | novo | `@ConfigurationProperties(prefix = "memetrade.real")`, `ownQuantitySell` padrão `false` | NFR3.6 |
| `bots.infra` | `OwnQuantityMetrics` | novo | 3 contadores (7 séries com zero) e o gauge `real_own_holdings_stuck_bots` | NFR2.10, NFR2.11, NFR2.16, NFR2.18 |
| `bots.infra` | `JdbcBotRepository` | muda | `activeDispatch()` com `market`; `precheckRow(ref)` (P1) e `slotClaimed(bot, slot)` (P2) | NFR1.10 |
| `bots.api` | `OwnQuantitySell` | novo | `boolean enabled()`; lido pelo worker e pela API | NFR3.6 |
| `bots.web` | montagem do `BotPage` | muda | `realOwnQuantitySell` sempre presente (C7) | NFR6.2 |
| `pnl.api` | `RealBotHoldings`, `HoldingsResult`, `BotHolding`, `UnavailableReason` | novo (C15) | contrato da leitura | NFR3.1, NFR4.2 |
| `pnl.application` | `RealBotHoldingsReader` | novo | transação própria `REPEATABLE READ` só de leitura, `ReadDeadline` de 250 ms, S0 a S6 | NFR1.1, NFR1.4, NFR1.7, NFR2.3 |
| `pnl.application` | `EpochAttribution` (nome proposto) | extraído | o acumulador de época que hoje é a classe interna `Accumulation` do `AttributedCostReader`, usado pelos dois leitores sem cópia de código | NFR2.9 |
| `realexecution.api` | `RealExecutionPort.openSells`, `OpenSell(source, sourceId, mint, units)` | novo (C16 com D1) | vendas do bot em aberto | NFR1.11 |
| `realexecution.infra` | `JdbcRealExecutionStore.OPEN_SELLS_SQL` | novo | o `UNION ALL` com literais | NFR1.11 |
| `decision.infra` | `DecisionJson` | muda | sobrecarga que grava `context.ownQuantity` só nas decisões REAL com a flag ligada | NFR4.1 |
| banco | `0026-bot-cycle-own-quantity-outcomes.sql` | novo | 2 changesets, `lock_timeout` 5 s | NFR2.7, NFR3.8 |
| banco | `0027-real-decision-request-bot-pending-sell-idx.sql` | novo | índice parcial `CONCURRENTLY` | NFR1.11, NFR1.12 |
| observabilidade | `real-ops-alerts.yml`, `tests/real-ops-own-holdings-unavailable.test.yml` | muda / novo | regras da razão e do bot preso e teste do `promtool` (10 casos) | NFR2.12, NFR2.18, NFR7.6 |
| IaC | `real-ops.tf`, `app-metrics.tf`, `main.tf` | muda | alarme de expressão com `FILL`, alarme do bot preso, métricas, flag nos dois mapas | NFR2.12, NFR2.18, NFR8.2, NFR8.4 |
| web | `apps/web/src/features/bots/BotList.tsx` | muda | aviso `role="status"` | NFR5.1, NFR5.2, NFR6.1 |
| docs | `docs/runbooks/real-operations.md` | muda | seções 10 a 13 | NFR2.14 |

Não mudam: `decision.domain` (motor, `DecisionRequest`, `PositionInput`), `RealDecisionStep`, `RealDecision`, `DecisionConsumer`, `LiquidationJob`, condicionais, saque, `OrderSettlement`, `AttributedCostReader` no caminho `GATE` e com a flag desligada, o ramo PAPER, o TxSigner.

## Domínios de falha e raio de impacto

| Falha | Onde | Efeito | Raio | Recuperação |
|---|---|---|---|---|
| Leitura lenta ou erro de banco na leitura | `RealBotHoldingsReader` | `Unavailable`; o bot não vende pelo ciclo; compras seguem | 1 bot, 1 slot | automática no ciclo seguinte (5 min); alarme da razão se passar de 5% por 15 min; alarme do bot preso (F3) se um bot tiver 3 ou mais leituras indisponíveis seguidas por 10 min |
| `BOT_NOT_OWNED` | `RealBotHoldingsReader` | idem, com log `ERROR` | 1 bot | defeito; corrige no código |
| Marca mudou (`STALE_SNAPSHOT`) | `OwnSellCap` | a venda do ciclo atrasado não é gravada | 1 bot, 1 slot | automática; revisão semanal |
| Conferência prévia diferente da trava | `CycleRunner` | `NotRead`: sem venda; ou uma leitura desperdiçada | 1 bot, 1 slot | automática |
| Banco fora | todo o ciclo | como hoje: o ciclo falha e grava `FAILED` | todos os bots | como hoje |
| Pool esgotado | conferências e leitura | `BUDGET_EXCEEDED` (o prazo inclui a espera) | os bots do slot | alarme; runbook seção 10 |
| `0026` ou `0027` barrada | `db-migrate` | a implantação para; imagens antigas servem | implantação | runbook seção 12 |
| Flag ligada no worker com venda antiga na fila | virada | a venda antiga vende como hoje; excesso no rateio | 1 venda | conferência da virada; T19 |
| Reinicialização do worker | `OwnHoldingsStreaks` | o gauge volta a 0; um bot preso reaparece depois de mais 3 leituras | sinal de 1 bot | automática em cerca de 25 min; a razão e o log continuam |
| Defeito no cálculo da posição própria | `RealBotHoldingsReader` | subestima ou superestima a quantidade | bots com a flag ligada | T12 e T1 antes do código; o consumidor ainda corta pela carteira; desligar a flag do worker |

A saída do dinheiro (saque, "Vender tudo e sacar", condicionais, liquidação) está fora de todos esses domínios (R2 de `OwnQuantityArchitectureTest`).

## Fronteiras de módulo (ArchUnit)

`OwnQuantityArchitectureTest` (regras R1 a R6 em `security-design.md`). As regras de `BotsArchitectureTest`, `RealCostArchitectureTest`, `ModeBoundaryArchitectureTest` e `NeverSumModesArchitectureTest` continuam como estão; o `pnl.application` lê o `realexecution` e o `ledger` só por `RealExecutionPort` e `RealLedger`, nunca pelas tabelas.

## Ordem de construção

1. Testes antes do código (Q2, `team.md`): T1 a T14, T18, T19, `OwnSellCapTest`, os de migração e o T20 com o arquivo de teste do `promtool`; T15 a T17 também antes (Q2).
2. Migrações `0026` e `0027` e os testes delas.
3. `openSells` (C16) e `RealOpenSellsPlanIT`.
4. Extração do `EpochAttribution` sem mudança de comportamento (os testes da U2 continuam verdes), depois o `RealBotHoldingsReader` (C15), T12 e `RealBotHoldingsMeasurementIT`.
5. `OwnQuantitySellProperties`, `OwnQuantityMetrics`, `OwnHoldingsStep`, mudanças em `SlotDispatcher`, `CycleRunner`, `RealCycle`, `DecisionJson`.
6. Regra, alarme, métricas, flag no Terraform, `RealOpsAlarmParityTest`.
7. `BotPage` e `BotList` (integrando a mudança do filtro da U3 no mesmo arquivo, RK-6).
8. Runbook.

## Alinhamentos para a Code Generation

Textos dos estágios anteriores que este desenho muda ou detalha. Eles são editados na Code Generation, não neste estágio.

| ID | Onde | Hoje | Passa a ser | Origem |
|---|---|---|---|---|
| A1 | `functional-design/rules.md`, BR2.7 e a linha BR2.7 do resumo | "3 ciclos seguidos"; Prometheus `increase[5m] > 0` por 15 min; CloudWatch `period = 300`, 3 de 3 | razão `(BUDGET_EXCEEDED + READ_FAILED) / leituras` em `[10m]`, `> 0.05`, `for: 15m`; CloudWatch `(m1 + m2) / m3`, `period = 600`, 2 de 2, `notBreaching`. **A BR2.7 é substituída pela F2.** | fundador F2; NFR2.12 |
| A2 | `functional-spec.md`, T20 | "três ciclos seguidos dispara; um ciclo só não" | os 8 casos de NFR7.6, por Testcontainers com o digest do `ci.yml` | F1, F2; NFR7.6 |
| A3 | `functional-spec.md`, E8 | "persistindo por 15 minutos, alerta (BR2.7)" | "persistindo acima de 5% das leituras por 15 minutos, alerta (NFR2.12)" | F2 |
| A4 | `functional-spec.md`, "Efeito nos contratos", métrica | `real_own_holdings_unavailable_total{reason}` com `STALE_SNAPSHOT` | 3 motivos nesse contador; `STALE_SNAPSHOT` em `real_cycle_sell_capped_total{outcome}`; mais `real_own_holdings_reads_total` | Q3 do NFR Requirements; NFR2.10, NFR2.11, NFR2.16 |
| A5 | `functional-spec.md`, "Efeito nos contratos", migração | `VALIDATE` "num segundo passo do mesmo changeset" | dois changesets na `0026`, `lock_timeout` 5 s; mais a `0027` com o índice parcial da fila | NFR2.7, NFR1.12 |
| A6 | `functional-spec.md`, "Testes da tela (depois do código)" | T15 a T17 depois do código | antes do código | Q2 do NFR Requirements; NFR7.1 |
| A7 | `functional-spec.md` F6 passo 4 e `rules.md` BR5.5 | só decisões SELL `PENDING` na conferência | também 0 ordens SELL de bot em andamento, as duas consultas e a consulta por `jsonb_exists(d.context, 'ownQuantity')`, numa sessão `REPEATABLE READ READ ONLY` | revisão R-04; NFR2.8 |
| A8 | `functional-spec.md` F3 passo 1 | "lê a marca do bot sob a trava (M1)" e o corte depois da gravação da decisão | M1 = `change_seq` da linha devolvida por `lockBot` (sem consulta a mais); o veredito do corte é calculado depois de `engine.decide` e **antes** de `decisions.insert`, para a nota entrar no contexto; `step.submit` só com venda > 0 | este estágio [Q2] |
| A9 | `functional-spec.md` F1 passos 0 e 1 | M0 lida depois das conferências | M0 lida na mesma consulta da conferência P1 (linha do bot), antes de P2, P3 e do instantâneo | este estágio [Q2] |
| A10 | `functional-spec.md` F2 e `entities.md` ENT-408 | "contexto da decisão" sem forma | objeto `context.ownQuantity` (`read`, `mints`, `cap`) gravado pelo `DecisionJson` só nas decisões REAL com a flag ligada; interno, nenhuma rota nem contrato expõe `decision.context` (revisão R-06) | este estágio |
| A11 | `entities.md` ENT-404 | um enum com os 3 motivos de C15 mais `STALE_SNAPSHOT` | dois enums: `pnl.api.UnavailableReason` (3, C15) e o motivo da nota do ciclo em `bots` (os 3 mais `STALE_SNAPSHOT`) | este estágio |
| A12 | `rules.md` BR2.5 | o bot REAL que roda sem a leitura é tratado como `Unavailable(READ_FAILED)` | idem, com `read = NOT_READ` na nota e sem subir os contadores de leitura | este estágio; NFR2.10 |
| A13 | `contract-summary.md` C15, `concurrency` e `transaction` | trava `withPersonLock` no ciclo; `statement_timeout` igual ao `budget`; consumidor `RealCycle` | a garantia é a marca do bot; `ReadDeadline` com o tempo que resta, por comando e por busca, e conferência final com a espera da conexão; quem chama é o `CycleRunner` (pelo `OwnHoldingsStep`), antes da transação | FD; este estágio |
| A14 | `contract-summary.md` C16 | `OpenSell(realOrderId, mint, ...)`, só ordens | `OpenSell(source, sourceId, mint, units)` com as decisões SELL `PENDING`; consulta com literais; índice `real_decision_request_bot_pending_sell_idx` | D1; este estágio |
| A15 | `functional-spec.md` "O que existe hoje", `SlotDispatcher` | "a lista traz o mercado" | `DispatchedBot(ref, market)` e `run(BotRef, Instant)` mantido para os chamadores de hoje | este estágio |
| A16 | `reliability-requirements.md` NFR2.7, rollback | "cada changeset" recria e valida | changeset 2 volta com `SELECT 1`; changeset 1 recria a lista de hoje `NOT VALID` e valida | este estágio |
| A17 | `nfr-requirements/` (`observability-requirements.md`, `reliability-requirements.md`, `scalability-requirements.md`, `traceability.json`) | sem sinal por bot; NFR2.13 "nenhum outro alarme"; T20 com 8 casos; custo de US$ 1,50 | **linha nova NFR2.18** (decisão do fundador F3): gauge `real_own_holdings_stuck_bots` sem etiqueta, alarme `RealOpsOwnHoldingsStuckBots` (`max(min_over_time(...[10m])) > 0`) e gêmeo `Minimum`, 300 s, 2 de 2, `notBreaching`, `cloud_periods: "1"`; NFR2.13 com a exceção desse alarme; NFR7.6 com os casos 9 e 10; NFR8.2 com cerca de US$ 1,90 (US$ 0,40 a mais); `hasSize(16)`; NFR2.18 entra no `traceability.json` dos requisitos | fundador F3; revisão R-02 |
| A18 | `observability-requirements.md` NFR2.12, alarme do CloudWatch e teste próprio | expressão `e1 = (m1 + m2) / m3` | `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`; o teste confere esse texto; o teste offline do Terraform também | revisão R-01 |
| A19 | `performance-requirements.md` NFR1.5 | 2.000 medidas, p99,9 na 1.998ª | 10.000 medidas por semente, p99,9 na 9.990ª, sem portão de tempo na CI; acréscimo da semente B e do `RealBotHoldingsOrderPathParityIT` | revisões R-05 e R-07 |

## Sources

- `nfr-design-questions.md`: Q1 a Q4 e o resumo confirmado [Q1] [Q2] [Q3] [Q4].
- Os outros cinco arquivos deste diretório e `reviews/review-01.md`, com a decisão do fundador F3.
- `construction/u4-own-quantity-cycle/nfr-requirements/` (todos os arquivos e `reviews/review-01.md`, com as decisões F1 e F2).
- `construction/u4-own-quantity-cycle/functional-design/` (`functional-spec.md`, `rules.md`, `entities.md`), com a BR2.7 substituída pela F2.
- `construction/u2-real-bot-pnl/nfr-design/logical-components.md` (forma).
- `inception/contract-design/contract-summary.md`: C7, C8, C12, C13, C15, C16.
- `aidlc/spaces/default/memory/team.md` (Code Style: fronteiras ArchUnit; Testing Posture) e `project.md`.
- Código lido: `bots/worker/CycleRunner.java`, `SlotDispatcher.java`, `RealCycle.java`; `bots/application/RealDecisionStep.java`; `bots/infra/JdbcBotRepository.java`, `LockOrder.java`; `pnl/application/AttributedCostReader.java`; `realexecution/api/RealExecutionPort.java`; `realexecution/infra/DecisionConsumer.java`, `JdbcRealExecutionStore.java`; `decision/infra/DecisionJson.java`; `apps/api/src/test/java/com/memetrade/architecture/` (lista das regras).

## Assumptions & Open Questions

1. [assumption] Os nomes `OwnHoldingsStep`, `RealBotHoldingsReader`, `EpochAttribution`, `OwnSellCap`, `DispatchedBot` e `OwnQuantityMetrics` são propostos; a Code Generation pode trocá-los sem mudar o desenho.
2. A integração com a mudança da U3 em `BotList.tsx` (RK-6) é feita no mesmo arquivo, sem mudar o filtro.
3. Nenhum conflito com as respostas confirmadas ficou aberto. As diferenças com o Functional Design e o C15/C16 estão na tabela de alinhamentos.
