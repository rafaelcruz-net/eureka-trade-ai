# Desenho de escala — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado com a decisão do fundador F3 (gauge e alarme do bot preso).

A escala não muda [Q1] [Q4]. A U4 troca, no ciclo REAL com a flag ligada, a leitura de custo da carteira pelo caminho `CYCLE` da U2 pela leitura da posição própria do bot, e acrescenta três consultas por chave antes dela. Não entra processo, fila, cache, tabela nem gatilho de escala. Este arquivo cobre NFR1.8, NFR1.9, NFR1.11, NFR1.12 e NFR8.1 a NFR8.4.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Volume | 63 bots REAL, um ciclo a cada 5 min: cerca de 0,21 leitura por segundo no worker, no máximo 250 ms cada. | NFR1.8 |
| Conexões | No máximo 1 conexão por ciclo de cada vez: as conferências prévias (autocommit), depois a da leitura, devolvida no commit, depois a do ciclo. No máximo 4 ciclos em paralelo (`cycle-threads`). Pool do worker sem mudança (36). | NFR1.9 |
| Memória do worker (F3) | O mapa de falhas seguidas por bot tem no máximo 10.000 entradas (cerca de 1 MB) e despejo por 20 min sem leitura. | NFR2.18 (F3) |
| Memória | A leitura por época substitui a leitura de custo do caminho `CYCLE` da U2; o teto de memória do ciclo não sobe. | NFR1.9, NFR1.11 |
| Crescimento | Por bot e pela janela desde a época de cada mint, não pelo número de pessoas; leitura em fluxo no pior caso. | NFR1.11 |
| Dados | 0 tabelas e 0 colunas novas; 1 índice parcial novo (`0027`); 2 valores novos de `bot_cycle.outcome`. | NFR1.12 |
| Nuvem | 0 serviços AWS novos; 5 métricas, 1 alarme de expressão e 1 alarme padrão no CloudWatch, cerca de US$ 1,90 por conta e por mês. | NFR8.1, NFR8.2 |
| Ambientes | Flag ligada primeiro na devnet e depois na mainnet, com o mesmo SHA; a devnet continua fechada aos usuários. | NFR8.3, NFR8.4 |

## Volume e conexões (NFR1.8, NFR1.9)

| Recurso | Hoje (ciclo REAL) | Com a flag ligada |
|---|---|---|
| Conexões ao mesmo tempo por ciclo | 1 (a transação do ciclo) | 1 de cada vez: 3 comandos prévios em autocommit, a transação só de leitura, a transação do ciclo |
| Ciclos em paralelo | 4 (`memetrade.bots.cycle-threads`) | 4 |
| Conexões do pool do worker | 36 (`memetrade-worker-pool`, `application-worker.yml`) | 36, sem mudança |
| Leituras de custo com atribuição por ciclo | 1 (`AttributedCostReader`, caminho `CYCLE`, num ponto de salvamento) | 0 (BR1.6); a leitura da posição própria toma o lugar dela |

**Ocupação média.** 0,21 leitura por segundo × até 0,25 s = no máximo 0,05 conexão ocupada em média pela leitura. Como a leitura de custo do caminho `CYCLE` sai, a ocupação somada do ciclo fica na ordem da de hoje.

**Critério.** `hikaricp_connections_pending{pool="memetrade-worker-pool"}` continua 0 em duas leituras seguidas no cenário do `load-test-plan.md` do 261113 (o workflow manual não muda, NFR1.6). O `RealBotHoldingsIsolationIT` confere que a conexão da leitura é devolvida antes de a do ciclo ser pedida: a contagem de conexões ativas do pool de teste nunca passa de 1 por ciclo durante a execução.

## Memória (NFR1.9, NFR1.11)

| Caminho | `work_mem` | Pior caso por leitura | Leituras simultâneas |
|---|---|---|---|
| Hoje: leitura de custo `CYCLE` (U2) | `12MB` local, desfeito com o ponto de salvamento | 48 MB (`Sort` + `Hash` + `Incremental Sort`) | 4 |
| U4: `view` por época (S3) | padrão (4 MB) | o da consulta de época da U2 | 4 |
| U4: `VIEW_SQL` inteiro (volta por marca divergente) | `12MB` local na transação só de leitura (`SET LOCAL` é aceito numa transação `READ ONLY`) | 48 MB | 4 |
| U4: índice compacto do fluxo de C13 (S4 acima de 32 ordens) | — | cerca de 100 bytes por ordem; 14.600 ordens ≈ 1,5 MB de heap | 4 |

O pior caso da U4 (4 × 48 MB = 192 MB no PostgreSQL) é igual ao do caminho `CYCLE` de hoje, que ele substitui com a flag ligada. Com a flag desligada, nada muda.

## Crescimento com o histórico (NFR1.11)

- A consulta de época (S3) lê de cada mint só os lotes desde a última vez em que a carteira zerou o mint (`real_position_epoch`). O custo cresce com o tamanho da janela, não com o histórico total.
- O pior caso é um mint que nunca zera (bots diferentes compartilhando o mint, sempre com lote aberto). Então a janela é o histórico inteiro do mint, e S4 passa a ler as ordens da pessoa em fluxo (`fetchSize` 500, `ReadDeadline` por linha e por busca). A semente B do `RealBotHoldingsMeasurementIT` mede esse caso (`performance-design.md`).
- As vendas em aberto (S5) leem no máximo 1 ordem em andamento por bot (índice único parcial) e as decisões SELL `PENDING` do bot (índice parcial novo). Esse conjunto não cresce com o histórico.
- `RealOpenSellsPlanIT` confere: nenhum `Seq Scan` em `real_order` nem em `real_decision_request`.

## Dados (NFR1.12)

| Objeto | Mudança | Crescimento |
|---|---|---|
| `bot_cycle` | regra `bot_cycle_outcome_ck` com 2 valores a mais (`REAL_NO_OWN_QUANTITY`, 20 caracteres; `REAL_OWN_QUANTITY_UNAVAILABLE`, 29) na coluna `varchar(32)`; sem reescrita | 1 linha por ciclo, como hoje (≈ 18.144 por dia no volume de NFR1.8) |
| `decision` (contexto JSON) | no máximo 1 nota por mint da carteira e 1 nota do corte | alguns bytes por decisão REAL |
| `real_decision_request` | índice parcial `real_decision_request_bot_pending_sell_idx` | só as decisões SELL ainda `PENDING`: dezenas de entradas no pior caso |

0 tabelas novas, 0 colunas novas, nenhuma cópia da posição própria guardada (ADR-004, NFR8.12).

## Escala horizontal

Nada muda. O despachante continua com a trava `bots-slot-dispatch` do ShedLock e um único worker ativo. A leitura não guarda estado entre ciclos; um segundo worker por engano encontraria o slot já reclamado (`bot_cycle_slot_uq`) e, mesmo se dois ciclos do mesmo bot rodassem, a marca do bot (`security-design.md`) impediria a segunda venda.

## Custo e ambientes (NFR8.1 a NFR8.4)

- **NFR8.1:** a leitura roda no contêiner `worker` da tarefa `api-worker`. 0 serviços AWS novos.
- **NFR8.2:** entram na lista de `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf`:
  - `real_own_holdings_unavailable_total` num grupo novo `{ dimensions = [["reason"]], metrics = [...] }` (3 séries, 3 métricas);
  - `real_own_holdings_reads_total` no grupo `[[]]` que já existe (1 métrica).

  - `real_own_holdings_stuck_bots` (gauge da decisão do fundador F3) no grupo `[[]]` (1 métrica).

  São 5 métricas personalizadas, cerca de US$ 1,50, mais o alarme de expressão com 3 métricas (cerca de US$ 0,30) e o alarme padrão do bot preso (cerca de US$ 0,10). O total é cerca de US$ 1,90 por conta e por mês, ou seja, cerca de US$ 0,40 a mais que no requisito (NFR8.2: US$ 1,50), pela F3. Fica dentro dos US$ 350. `real_cycle_sell_capped_total` fica só no Prometheus. O desenho do alarme está em `observability-design.md`.
- **NFR8.3:** a flag é ligada primeiro na devnet (testes do fundador) e depois na mainnet com o mesmo SHA. Nenhuma configuração nova abre a devnet.
- **NFR8.4:** `MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` em `api_environment` e em `worker_environment` de `infra/terraform/main.tf` (`security-design.md`, Flag). O teste offline do Terraform confere os dois valores.

## Sources

- `nfr-design-questions.md`: Q1, Q3 e Q4 e o resumo confirmado [Q1] [Q3] [Q4].
- `reviews/review-01.md` (R-02) e a decisão do fundador F3 de 2026-11-24.
- `construction/u4-own-quantity-cycle/nfr-requirements/scalability-requirements.md` (NFR1.8 a NFR1.12, NFR8.1 a NFR8.4) e `tech-stack-decisions.md` (NFR8.8, NFR8.12).
- `construction/u2-real-bot-pnl/nfr-design/performance-design.md` e `scalability-design.md` (memória de ordenação, `work_mem`, caminho `CYCLE`).
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/performance-validation/load-test-plan.md` (63 bots, pool do worker).
- `aidlc/spaces/default/memory/project.md` (orçamento de US$ 350, CloudWatch Agent a cada 120 s) e `team.md` (Deployment).
- Código lido: `application-worker.yml` (`memetrade-worker-pool`, 36), `application.yml` (`cycle-threads: 4`), `bots/infra/BotsConfig.java`, `bots/worker/SlotDispatcher.java`, `ledger/infra/JdbcRealLedger.java` (`VIEW_EPOCH_SQL`, `VIEW_WORK_MEM`), `pnl/application/AttributedCostReader.java`, `infra/terraform/modules/observability/app-metrics.tf`, `infra/terraform/main.tf`, migrações `0010`, `0019`, `0020`, `0025`.

## Assumptions & Open Questions

1. [assumption] O pior caso de memória da volta para o `VIEW_SQL` inteiro (48 MB por leitura) usa o mesmo cálculo da U2; a U4 não muda a consulta.
2. [assumption] Os preços do CloudWatch (US$ 0,30 por métrica, US$ 0,10 por métrica de alarme) são os públicos; a região ainda não foi definida e não muda a decisão.
