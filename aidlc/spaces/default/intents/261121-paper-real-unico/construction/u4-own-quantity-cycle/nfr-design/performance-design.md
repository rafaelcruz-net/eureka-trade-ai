# Desenho de desempenho — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-05, R-07).

Este arquivo diz como a leitura da posição própria do bot (RealBotHoldings, C15) cabe em 500 ms fora da transação do ciclo, sem trava e sem nenhum segundo a mais no ciclo (NFR1.1 a NFR1.7, NFR1.10, NFR1.11). As decisões seguem as respostas confirmadas Q1 e Q2 [Q1] [Q2] e as decisões do fundador F2 (meta p99,9 ≤ 250 ms, 2026-11-24) e F6 (2026-11-25, loop-back 1 do Build and Test: meta e prazo passam a 500 ms, e S4 lê as ordens sempre por id em lotes de 1.000; alinhamento A20). A mudança de meta é decisão do fundador depois da medição na CI, nunca um afrouxamento feito pela Code Generation.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Onde a leitura roda | No `CycleRunner.run`, dentro de `auth.as(owner)` e **antes** de `transactions.execute(...)`. Ela abre a própria transação só de leitura e a fecha antes de a transação do ciclo abrir. | NFR1.4 |
| Isolamento | `TransactionTemplate` próprio do RealBotHoldings: `PROPAGATION_REQUIRES_NEW`, `ISOLATION_REPEATABLE_READ`, `readOnly = true`. Um instantâneo para as leituras de C12, C13 e C16. | NFR1.4, BR2.1 |
| Prazo | `ReadDeadline.after(clock, 500 ms)` (F6; antes 250 ms), criado **antes** de pedir a conexão; conferido em cada linha e reaplicado como `set local statement_timeout` antes de cada comando e de cada busca de 500 linhas. Depois do commit, uma última conferência: passou do prazo, `BUDGET_EXCEEDED`. | NFR1.7, BR2.2 |
| Conferências prévias | 3 consultas por chave, sem trava e fora do orçamento, antes da leitura da marca M0 e do RealBotHoldings. O mercado do bot chega da lista do `SlotDispatcher`. | NFR1.10, BR2.5 |
| Leitura do ledger | O `RealLedger.view` da U2 (épocas, `VIEW_EPOCH_SQL`), uma consulta só, e o acumulador por titular do `RealCostMethod`, como o `AttributedCostReader.readFromEpoch`. | NFR1.11, BR1.1 |
| Ordens dos lotes | Até 32 ordens distintas nos lotes da janela: leitura por id (`real_order_pkey`). Acima disso: o fluxo de C13 (`streamSettledOrders` com o prazo), depois por id só o que faltar. | NFR1.1, NFR1.11 |
| Vendas em aberto (C16) | Um comando com `UNION ALL`: ordens SELL do bot em andamento pelo `uq_real_order_bot_in_progress` e decisões SELL `PENDING` do bot pelo índice parcial **novo** `real_decision_request_bot_pending_sell_idx` (migração `0027`). | NFR1.11, NFR1.12 |
| Custo do ciclo | Com a flag ligada, o `AttributedCostReader` (caminho `CYCLE`) **não** é chamado: o custo vem do RealBotHoldings (BR1.6). O trabalho somado do ciclo fica na ordem do de hoje. | NFR1.2 |
| Flag desligada, PAPER e saídas | Nenhuma consulta nova: as conferências prévias, M0 e o RealBotHoldings só existem no ramo REAL com a flag ligada. | NFR1.3 |
| Medida | `RealBotHoldingsMeasurementIT` com duas sementes (com marcas de zeragem e sem nenhuma zeragem em 2 anos), só registrado, sem portão de tempo. | NFR1.5, NFR1.6 |

## Sequência do ciclo REAL com a flag ligada

```mermaid
sequenceDiagram
    participant D as SlotDispatcher
    participant R as CycleRunner
    participant H as RealBotHoldings (pnl)
    participant DB as PostgreSQL
    D->>R: run(DispatchedBot(ref, market=REAL), slot)
    R->>DB: P1..P3 (autocommit, por chave); M0 = bot.change_seq
    R->>H: holdings(tenant, user, bot, 250 ms)
    H->>DB: BEGIN READ ONLY ISOLATION LEVEL REPEATABLE READ
    H->>DB: S1..S5 (statement_timeout = o que resta)
    H->>DB: COMMIT (conexão devolvida)
    H-->>R: Available | Unavailable(motivo)
    R->>DB: transação do ciclo (READ COMMITTED), lockBot -> M1
    R->>R: OwnSellCap (M1 = M0?, min(pedido, disponível))
    R->>DB: RealDecisionStep.submit -> recordDecision (só se a venda > 0)
```

Texto equivalente: o despachante entrega o bot com o mercado. O ciclo faz as conferências prévias e lê M0, chama o RealBotHoldings, que lê tudo num instantâneo só de leitura e devolve a conexão, e só então abre a transação do ciclo. Sob a trava, a marca é lida de novo e o corte é aplicado antes de a decisão entrar na fila.

## Conferências prévias e marca M0 (F1 passo 0 e 1; NFR1.10)

O `SlotDispatcher` passa a ler `bot_id, tenant_id, owner_user_id, market` (`JdbcBotRepository.activeDispatch()`, nome proposto; a consulta de hoje com uma coluna a mais) e entrega um `DispatchedBot(BotRef ref, BotMarket market)` ao `CycleRunner.run`. A assinatura `run(BotRef, Instant)` continua para os chamadores de hoje e trata o mercado como desconhecido: nesse caso P1 decide pelo `market` da linha.

As conferências rodam na ordem abaixo, em autocommit (sem transação), na conexão do pool do worker, e só para mercado REAL com a flag ligada. A primeira que falha encerra a fase prévia; o ciclo segue como hoje e pula dentro da transação.

| Passo | O que confere | Consulta | Índice |
|---|---|---|---|
| P0 | flag ligada, mercado REAL, `now ≤ slotStart + 5 min` | nenhuma (memória e relógio injetado) | — |
| P1 | estado `ACTIVE`, `pause_reasons` vazio, `market = 'REAL'`; lê **M0** | `select state, pause_reasons, market, change_seq from bot where bot_id = :bot and tenant_id = :tenant and owner_user_id = :owner` | `bot_pkey` |
| P2 | slot ainda não reclamado | `select exists (select 1 from bot_cycle where bot_id = :bot and slot_start = :slot)` | `bot_cycle_slot_uq` |
| P3 | pausa do sistema desligada | `select active from system_pause_state where id = 1` (o `systemPaused()` de hoje) | chave primária |

M0 vem da mesma linha de P1. A consulta termina antes de o instantâneo do RealBotHoldings abrir, que é o que a BR3.1 pede: qualquer decisão do bot confirmada depois de M0 muda a marca e é pega sob a trava. Ler M0 antes de P2 e P3 só pode aumentar um `STALE_SNAPSHOT`, nunca permitir uma venda a mais.

Custo: 3 comandos por chave primária ou única, cerca de 1 ms no total, fora do orçamento de 250 ms (o orçamento é da leitura do RealBotHoldings, NFR1.1). T5 confere, com um contador de chamadas e a ordem das leituras, que bot PAPER, flag desligada, slot passado ou reclamado, bot fora de `ACTIVE`, bot com motivo de pausa e pausa do sistema dão 0 leituras de M0 (exceto quando P1 é a própria conferência que falha) e 0 chamadas do RealBotHoldings.

## Leitura no instantâneo (F1 passo 3; NFR1.1, NFR1.4, NFR1.11)

`pnl.application.RealBotHoldingsReader` (nome proposto) implementa `pnl.api.RealBotHoldings` (C15). Tudo abaixo roda numa transação só, `REPEATABLE READ`, `readOnly`.

**Onde o instantâneo é fixado (revisão R-05).** O `SET LOCAL` de S0 não toma o instantâneo: no PostgreSQL, uma transação `REPEATABLE READ` fixa o instantâneo na **primeira consulta** dela, que é S1. Por isso S1 é obrigatoriamente a primeira consulta da transação: nenhuma leitura de dados (nem da marca, nem de outra tabela) pode vir antes dela dentro da transação, e a marca M0 é lida antes, fora dela (P1). O `RealBotHoldingsIsolationIT` confere a ordem dos comandos pelo log do driver de teste: o primeiro comando depois do `BEGIN` é o `set local statement_timeout`, e o primeiro `select` é o de S1.

| Passo | Leitura | Consulta ou método | Índice |
|---|---|---|---|
| S0 | prazo (não fixa o instantâneo) | `deadline.applyStatementTimeout(jdbc::execute)` | — |
| S1 | **primeira consulta: fixa o instantâneo.** Bots REAL da pessoa (C8); o bot pedido precisa estar na lista, senão `BOT_NOT_OWNED` | `BotDirectory.botsOf(tenant, user, REAL)` | `bot_owner_state_idx (tenant_id, owner_user_id, state, updated_at DESC)` |
| S2 | carteira do bot da pessoa | a leitura da `botwallet.api` que o `RealBotPnl` já usa | `bot_wallet_account_tenant_idx (tenant_id, user_id)` |
| S3 | saldo, posições e lotes abertos desde a época de cada mint (C12, caminho só de quantidade) | `RealLedger.view(tenant, wallet, Optional.empty())`, uma consulta (`VIEW_EPOCH_SQL`); com marca divergente, o `VIEW_SQL` inteiro, contado em `ledger_real_epoch_fallbacks_total` | `real_position_epoch_wallet_idx`, `real_ledger_entry_wallet_created_idx`, `real_entry_slot_pkey` |
| S4 | as ordens que os lotes citam (`order:<id>`), para saber o titular (C13) | ≤ 32 ids distintos: `settledOrder(tenant, user, id)` um a um; > 32: `streamSettledOrders(tenant, user, deadline, ...)` num índice compacto e depois por id só os que faltarem | `real_order_pkey`; `real_order_user_created_idx (user_id, created_at DESC)` |
| S5 | vendas do bot em aberto (C16 com a extensão D1) | `RealExecutionPort.openSells(tenant, bot)`, um comando (abaixo) | `uq_real_order_bot_in_progress`; `real_decision_request_bot_pending_sell_idx` (novo) |
| S6 | cálculo na memória | acumulador do `RealCostMethod` por titular; `openPositions(Holder.bot(botId))`; `available = max(0, own − openSell)` por mint | — |

Antes de cada comando de S1 a S5, `applyStatementTimeout` com o que resta. S3 e o fluxo de S4 conferem o prazo em cada linha (`ReadDeadline.guard`). S6 confere a cada lote aplicado e no fim. O commit é só de leitura. Depois do commit, `deadline.check()` mais uma vez: se o tempo total, incluindo a espera pela conexão do Hikari, passou de 250 ms, o resultado é `Unavailable(BUDGET_EXCEEDED)`, mesmo com os dados lidos. Assim o prazo vale para a leitura inteira (NFR1.7), e não só para os comandos.

**S4 depois do loop-back 1 (decisão F6, 2026-11-25; alinhamento A20).** A CI Linux do `fc65b7f` mostrou que o fluxo de C13 é o custo da semente B: 100% das leituras passaram de 250 ms, com p50 de 440 ms sem prazo. O fundador decidiu que S4 lê as ordens citadas pelos lotes sempre por id, num comando por lote de até 1.000 ids (`RealExecutionPort.settledOrdersById`, `real_order_id = any(:ids)` pela chave primária), com o prazo reaplicado antes de cada lote e conferido entre eles. O limite de 32 e o fluxo saíram do leitor; o cache negativo por leitura continua. A equivalência é conferida pelo `RealBotHoldingsOrderPathParityIT`, contra um oráculo calculado da semente (32 e 33 ordens nas sementes A e B, 5.000 ordens, T12). Na execução vermelha, o oráculo bateu com o caminho antigo, antes da troca. O IT também conta os comandos: no máximo `ceil(n / 1.000)` buscas por id, nenhuma busca avulsa e nenhum fluxo. Os dois parágrafos seguintes ficam como histórico da decisão anterior.

**Por que o limite de 32 ordens em S4.** O caminho de época da U2 lê por id cada ordem que os lotes citam. Isso é barato quando o mint zerou há pouco. Quando a carteira nunca zerou um mint em 2 anos (vários bots no mesmo mint, sempre com algum lote aberto), a janela é o histórico inteiro, cerca de 14.600 ordens na semente de NFR1.5. Isso daria milhares de buscas por chave, uma por comando, o que passaria de 250 ms. O fluxo de C13 lê as ordens da pessoa num comando só, com `fetchSize` 500, como o caminho `GATE`/`CYCLE` de leitura inteira da U2, que já cabia em 250 ms. O valor 32 é uma constante de pacote (`ORDER_PREFETCH_THRESHOLD`), e a medida de NFR1.5 confirma ou ajusta o valor antes do primeiro commit que o usa. Os dois métodos de C13 já existem: o RealExecution não muda além de `openSells` (BR4.3).

**Equivalência dos dois caminhos (revisão R-05).** A T12 ganha `RealBotHoldingsOrderPathParityIT`: o leitor recebe o limite por um construtor de pacote, e o mesmo instantâneo é lido duas vezes, uma forçando a leitura por id (limite acima do número de ordens) e outra forçando o fluxo (limite 0). Casos: exatamente 32 e exatamente 33 ordens distintas nos lotes (os dois lados da troca com o limite real), e as sementes A e B de NFR1.5. Em todos, os dois resultados são iguais por mint (`ownTokenBaseUnits`, `ownCostLamports`, `openSellBaseUnits`, `availableTokenBaseUnits`) e iguais ao RealBotPnl quando não há venda em aberto.

### Consulta de C16 (`openSells`)

```sql
select 'ORDER' as source, real_order_id as source_id, mint, input_base_units as units
  from real_order
 where bot_id = :bot and tenant_id = :tenant and side = 'SELL'
   and state in ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS')
union all
select 'DECISION_REQUEST', decision_request_id, mint, token_base_units
  from real_decision_request
 where bot_id = :bot and tenant_id = :tenant and side = 'SELL' and state = 'PENDING'
```

Os estados, o lado e `PENDING` ficam **literais** no SQL, nunca parâmetros. Só assim o planejador prova que a condição implica o predicado dos índices parciais, também no plano genérico de um comando preparado. A decisão `PENDING` adiada (`not_before` no futuro) conta, como pede a BR1.3: a consulta não filtra `not_before`.

## Índices: decisão

| Tabela | Índice | Decisão |
|---|---|---|
| `real_order` (ordens SELL do bot em andamento) | `uq_real_order_bot_in_progress ON real_order (bot_id) WHERE bot_id IS NOT NULL AND state IN (...)` (`0019`) | **Não precisa de índice novo.** O predicado é o mesmo da consulta; no máximo 1 linha por bot. |
| `real_decision_request` (decisões SELL `PENDING` do bot) | hoje só `real_decision_request_queue_idx (state, created_at)`, `real_decision_request_purge_idx` e a chave única de `decision_id` | **Precisa de índice novo** [Q1]. O `queue_idx` serve `state = 'PENDING'`, mas lê as decisões pendentes de **todas** as pessoas e filtra o bot no heap; numa fila acumulada (consumidor parado ou adiamentos) isso cresce com o número de pessoas. |

**Índice novo:** `real_decision_request_bot_pending_sell_idx ON real_decision_request (bot_id) WHERE state = 'PENDING' AND side = 'SELL'`.

- Fica pequeno: só as decisões de venda ainda na fila, que somem do índice quando viram `PROCESSED` ou `DISCARDED`.
- Não tem `tenant_id`: o `bot_id` é um uuid único; o tenant fica no SQL como filtro (NFR3.1).
- O custo de escrita é um item a mais por decisão SELL gravada e uma remoção lógica quando ela fecha. O volume é de no máximo alguns por minuto no volume de NFR1.8.

**Migração (só expansão):** `db/changelog/changes/0027-real-decision-request-bot-pending-sell-idx.sql`, incluída em `db.changelog-master.yaml` depois da `0026`, no padrão do índice da `0023`.

```sql
--changeset memetrade:0027-real-decision-request-bot-pending-sell-idx runInTransaction:false
DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;
CREATE INDEX CONCURRENTLY real_decision_request_bot_pending_sell_idx
    ON real_decision_request (bot_id) WHERE state = 'PENDING' AND side = 'SELL';
--rollback DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;
```

- `CONCURRENTLY`, porque a fila recebe inserções a cada ciclo; o build não bloqueia as escritas do consumidor nem do ciclo.
- Nunca `CREATE INDEX IF NOT EXISTS`: um build interrompido deixa um índice `INVALID`, e o `DROP` do começo o remove na execução seguinte (mesmo raciocínio do `Migration0023IndexRestartIT`).
- O índice fica num arquivo próprio para que a `0026` continue só com a troca da regra de `bot_cycle` (NFR3.8). O NFR1.12 aceita este índice: é o que o NFR Design pede pela conferência de NFR1.11.

## Conferência do plano (EXPLAIN)

| Onde | Nome | O que confere |
|---|---|---|
| CI, job `backend`, Failsafe | `RealOpenSellsPlanIT` (novo) | Semeia, para 2 pessoas de tenants diferentes: 20.000 decisões fechadas (`PROCESSED` e `DISCARDED`), 6 decisões `PENDING` (3 SELL, 1 adiada, e 3 BUY), 14.600 ordens finais e 1 ordem SELL em andamento do bot medido; roda `ANALYZE`. Faz `EXPLAIN (FORMAT JSON)` do SQL exato de `openSells` (constante de pacote do `JdbcRealExecutionStore`). Exige um nó com `"Index Name": "real_decision_request_bot_pending_sell_idx"` e outro com `"Index Name": "uq_real_order_bot_in_progress"`, e nenhum `Seq Scan` com `"Relation Name"` `real_decision_request` ou `real_order`. |
| CI, job `backend`, Failsafe | `Migration0027IndexRestartIT` (novo) | Com um índice `INVALID` deixado de propósito, a migração o remove e cria um válido (`pg_index.indisvalid = true`); a regra `NOT VALID` da `0026` não é afetada. |
| CI, job `backend`, Failsafe | `RealLedgerNetworkOrderPlanIT` (U2, sem mudança) | Continua conferindo o plano do `view`, que S3 reaproveita. |

Se o planejador escolher outro caminho, a Code Generation ajusta as colunas do índice antes do primeiro commit que aplica a `0027`, e nunca afrouxa o teste.

## Orçamento da leitura (estimativa, a confirmar pela medida)

| Parte | Estimativa no pior caso da semente | Fonte |
|---|---|---|
| Espera da conexão (Hikari) | ≈ 0 ms com 4 ciclos em 36 conexões | NFR1.9 |
| S1, S2, S5 | ≈ 1 a 3 ms no total (consultas por índice) | — |
| S3 (`view` por época) | dominada pela agregação da carteira; a mesma consulta que a barreira já faz hoje dentro do ciclo | U2, `performance-design.md` |
| S4 (por id em lotes de 1.000, F6) | 1 comando por chave primária a cada 1.000 ordens citadas; cerca de 15 comandos no pior caso da semente B | `RealOpenSellsPlanIT` (`real_order_pkey`) |
| S6 (memória) | proporcional às linhas de S3 | — |

Estes números são estimativa [assumption]. O valor que vale é a mediana do p99,9 de 3 execuções do `RealBotHoldingsMeasurementIT` (NFR1.5). Se ela passar de 500 ms (F6), o fato vai ao fundador antes da implantação que liga a flag.

## Medida (NFR1.5, NFR1.6)

`RealBotHoldingsMeasurementIT` (Failsafe, Testcontainers, PostgreSQL 17 com as migrações da aplicação), no padrão do `RealRiskMeasurementIT`:

- **Semente A (de NFR1.5):** 1 pessoa, 1 carteira do bot, 5 bots REAL e 10 mints, 730 dias, 4 ordens por bot por dia (2.920 por bot, 14.600 no total), com os lançamentos; 5% de vendas com excedente, 2% de condicionais, 2% de liquidações rateadas; 3 ordens SELL em andamento (uma `AMBIGUOUS`) e 3 decisões SELL `PENDING` (uma adiada) do bot medido. Com marcas de zeragem como a escrita do ledger as grava.
- **Semente B (pior caso, acréscimo deste estágio):** a mesma, mas com os bots compartilhando os mints de forma que nenhum mint zera em 2 anos. A janela de época é o histórico inteiro, e S4 usa o fluxo. O NFR1.5 permite aumentar a semente [assumption 1 do `performance-requirements.md`].
- **Leitura:** 200 de aquecimento e **10.000 medidas por semente** (revisão R-07). p99,9 pelo posto `ceil(0,999 × 10.000)` = 9.990ª, e p50 (5.000ª), p95 (9.500ª) e p99 (9.900ª) como informação; contagem acima de 250 ms (no máximo 10 para cumprir a meta).
- **Por que 10.000 e não "só informativo".** Com 2.000 medidas, o p99,9 é decidido por 2 pontos, e a mediana de 3 execuções oscilaria com um único atraso do runner. Com 10.000, ele é decidido por 10 pontos. O custo é de segundos por semente: cada leitura leva milissegundos no contêiner. O número continua **sem portão de tempo na CI**: no `team.md`, teste instável é defeito, e um portão de tempo num runner compartilhado seria instável. A decisão de tempo continua no Build and Test: a mediana de 3 execuções acima de 250 ms vai ao fundador antes da implantação que liga a flag. O NFR1.5 pede "2.000 medidas"; 10.000 só aumenta a amostra, como a suposição 1 de `performance-requirements.md` permite.
- **Ciclo (NFR1.2):** 10 de aquecimento e 100 medidas com a flag ligada e desligada, p95 pela 95ª.
- **Saída:** uma linha de log por semente e medida. No Build and Test, a mediana de 3 execuções vai para `docs/perf/u4-own-quantity-cycle-<data>.md`, com o SHA e a máquina. O teste falha só por leitura com erro ou com quantidade errada, nunca pelo tempo. A mesma execução registra a razão de `STALE_SNAPSHOT` (NFR2.17), que deve ser 0.

O `scripts/load-test.sh`, os cenários `scripts/load/*.mjs` e o workflow manual `load-test` não mudam (NFR1.6).

## Travas e conexões (NFR1.4)

- Nenhum `FOR UPDATE`, `FOR SHARE` ou trava consultiva no RealBotHoldings, em `openSells` e nas conferências prévias. Nenhuma chamada de `LockOrder.lockBot`, `WalletLock` ou `RealRiskGate.withPersonLock` nesse caminho. Uma regra ArchUnit confere (T13, ver `security-design.md`).
- O `TransactionTemplate` da leitura exige que não haja transação ativa na thread: `TransactionSynchronizationManager.isActualTransactionActive()` verdadeiro na entrada lança `IllegalStateException` antes de qualquer leitura. Isso é defeito de programação, não indisponibilidade, e o teste de integração confere que nunca acontece no ciclo.
- Teste de integração (`RealBotHoldingsIsolationIT`): com a leitura parada no meio por uma barreira de teste (um `Consumer` de linha que espera um `CountDownLatch`), um saque, uma liquidação e o consumidor da fila da mesma pessoa terminam sem esperar (3 de 3). Dentro da leitura, `show transaction_isolation` = `repeatable read`, `show transaction_read_only` = `on`, e a transação não é a do ciclo.

## Sources

- `nfr-design-questions.md`: Q1 e Q2 e o resumo confirmado [Q1] [Q2].
- `reviews/review-01.md`: R-05 (instantâneo fixado em S1, equivalência dos caminhos de S4) e R-07 (amostra do p99,9).
- `construction/u4-own-quantity-cycle/nfr-requirements/performance-requirements.md` (NFR1.1 a NFR1.7), `scalability-requirements.md` (NFR1.9 a NFR1.12), `tech-stack-decisions.md` (NFR8.5, NFR8.6); decisão do fundador F2 de 2026-11-24.
- `construction/u4-own-quantity-cycle/functional-design/functional-spec.md` (F1, F2, F3, T5, T13), `rules.md` (BR1.1, BR1.3, BR1.6, BR2.1, BR2.2, BR2.5, BR3.1), `entities.md` (ENT-405, ENT-409).
- `construction/u2-real-bot-pnl/nfr-design/performance-design.md` (prazo duro em duas camadas, `REPEATABLE READ`, índice da `0023`, conferência do plano).
- `inception/contract-design/contract-summary.md`: C8, C12, C13, C15, C16.
- Código lido: `bots/worker/CycleRunner.java`, `SlotDispatcher.java`, `RealCycle.java`; `bots/infra/JdbcBotRepository.java` (`activeBots`, `renewMark`, `systemPaused`, `SELECT_BOT` com `change_seq` e `market`), `bots/infra/LockOrder.java`, `bots/infra/JdbcCycleRepository.java` (`tryStart`); `pnl/application/AttributedCostReader.java` (`readFromEpoch`, `guarded`); `shared/jdbc/ReadDeadline.java`; `ledger/infra/JdbcRealLedger.java` (`view`, `VIEW_EPOCH_SQL`); `realexecution/api/RealExecutionPort.java`, `realexecution/infra/JdbcRealExecutionStore.java` (`hasOrderInProgress`); `ledger/api/RealCostMethod.java` (`openPositions(CostHolder)`); migrações `0010` (`bot_owner_state_idx`, `bot_cycle_slot_uq`), `0016` (`bot_wallet_account_tenant_idx`), `0019` (`real_decision_request`, `real_order` e índices), `0022` (`not_before`), `0023` (padrão `CONCURRENTLY`), `0025` (`real_position_epoch_wallet_idx`); `application-worker.yml` (pool de 36).

## Assumptions & Open Questions

1. [assumption] As estimativas da tabela "Orçamento da leitura" não são medidas; o número que vale é o da medida de NFR1.5 no Build and Test.
2. [assumption] O limite de 32 ordens para trocar a leitura por id pelo fluxo de C13 é um ponto de partida. A medida das duas sementes confirma ou ajusta o valor; ele nunca afrouxa o prazo.
3. A semente B é um acréscimo deste estágio para medir o pior caso da janela de época. Ela não diminui a semente A de NFR1.5.
4. [assumption] O planejador do PostgreSQL 17 prova a implicação do predicado de `uq_real_order_bot_in_progress` com a lista de estados escrita igual na consulta. O `RealOpenSellsPlanIT` confere isso; se falhar, a consulta repete o predicado do índice com o mesmo texto.
