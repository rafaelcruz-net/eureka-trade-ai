# Desenho de desempenho — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md`, `reviews/review-02.md` e das decisões do fundador de 2026-11-22 (F-1 a F-4, abaixo).

Este arquivo diz como o P&L REAL por bot cabe no p95 de 1.000 ms de C4 (NFR1.1) e no orçamento da atribuição (NFR1.3, NFR1.4, NFR1.12), sem cache e sem trava de leitura. As decisões seguem as respostas confirmadas Q1, Q2 e Q4 [Q1] [Q2] [Q4] e as decisões do fundador D-NFR1 e D-NFR3.

## Decisões do fundador (2026-11-22, depois da revisão 01)

- **F-1. Ordem de gravação da herança.** Aceita `(created_at, reference, kind_rank, idempotency_key)`, com `COLLATE "C"` em todos os desempates de texto. BR1.3 e C12 são alinhados na Code Generation (A9 de `logical-components.md`).
- **F-2. O teto `real-max-orders` fica.** O comportamento da U1 continua: `memetrade.pnl.by-mode.real-max-orders` (padrão 50.000, de 15.000 a 200.000), a causa `TOO_MANY_ORDERS` e a variável `MEMETRADE_PNL_BYMODE_REALMAXORDERS` da tarefa `api`, sem mudança. O NFR8.1 vale como está: nenhuma mudança na definição de tarefa.
- **F-4. Fotografia do dia adiada (revisão 02, R-11).** Se a rodada do dia não gravou a fotografia de uma carteira por custo `Unknown`, a primeira compra do dia é recusada até a rodada gravar a fotografia. Venda, liquidação e saque seguem. A rodada tenta de novo (a cada 5 min). Um custo `Unknown` nunca produz uma fotografia com a base depois de uma queda. Desenho em `reliability-design.md`; a BR4.4 da U3 `u3-real-risk` do intent `261113-trading-real-bot` é alinhada na Code Generation (A13).
- **F-3. Custo desconhecido explícito.** `RealPosition`, `RealWalletCost` e C11 ganham um tipo selado de custo, `Known` ou `Unknown`, como o `unknown` dos dados de mercado. Nunca 0, nunca `null`. Com custo desconhecido e preço desconhecido, a compra é recusada e o `RealCycle` não decide compra desse token; a venda, a liquidação e o saque seguem.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Leitura principal | Uma passada por pessoa, em fluxo, na ordem de rede: primeiro o índice compacto das ordens (C13), depois os lançamentos (C12), com `fetchSize` 500. | NFR1.1, NFR1.15, NFR1.13 |
| Índice | A `0023` cria, além da chave primária de `real_entry_slot`, **um** índice em `real_ledger_entry`: `real_ledger_entry_wallet_created_idx (bot_wallet_id, created_at)`. | NFR1.18 |
| EXPLAIN | `RealLedgerNetworkOrderPlanIT` (CI) e `scripts/load/pnl-plans.mjs` (carga) exigem o índice pelo nome e nenhum `Seq Scan` em `real_ledger_entry`. | NFR1.6, NFR1.18 |
| Teto `real-max-orders` | **Fica** (F-2). A leitura em fluxo conta as ordens no próprio fluxo e para com `TOO_MANY_ORDERS` acima do teto. | NFR1.6, NFR1.15 |
| Prazo duro | Um instante limite do relógio injetado, conferido **em cada linha** do fluxo, e `set local statement_timeout` com o tempo que resta, reaplicado antes de cada busca de 500 linhas. Sem cancelamento fora de banda. 250 ms em `GATE` e `CYCLE`, 500 ms em `PNL`. | NFR1.3, NFR1.4, NFR1.12 |
| Compra | **Nunca** lê a atribuição: uma posição sem preço já recusa a compra (`POSITION_PRICE_UNKNOWN`) antes de o custo ser usado. Nada da U2 roda sob a `WalletLock`. | NFR1.5, NFR1.26 |
| Rodadas de risco e ciclo | A leitura de custo roda num ponto de salvamento (`PROPAGATION_NESTED`), com 250 ms, só onde o custo é usado: `RealRiskJobs` (`GATE`) e `RealCycle` (`CYCLE`). | NFR1.4 |
| Custo | Tipo selado `RealCost` (`Known` ou `Unknown`) em `RealPosition` e em `RealWalletCost` (F-3). | NFR1.11, NFR3.11 |
| Portão de carga | Blocos REAL `UNAVAILABLE` com causa `TIMEOUT`, **por qualquer parte do bloco**, ≤ 0,5% das leituras de C4, contados por `pnl.mode_block.unavailable` com a etiqueta nova `cause`. | NFR1.6, NFR1.3 |
| Memória de ordenação | `work_mem` local só nos dois caminhos que ordenam o histórico inteiro, com o número de leituras simultâneas limitado. | NFR1.6, NFR1.16 |

## Leitura em fluxo do P&L REAL (F1, C12, C13)

A leitura roda dentro do bloco REAL de C4, na tarefa do pool `pnl-mode-block` da U1, com o prazo `memetrade.pnl.by-mode.block-timeout` (800 ms, contado desde o envio ao pool, sem mudança no `PnlByModeProperties`). O `GET /api/v1/real/pnl` passa a rodar a mesma leitura **pelo mesmo pool** (`ModeBlockExecutor`, mercado REAL, mesmo prazo de 800 ms), e não mais na thread do pedido. Assim, o número de leituras REAL simultâneas no banco fica limitado a `pool-size` (8), o que fecha a conta de memória abaixo. Com a fila cheia, a rota responde o erro técnico que já tem para a indisponibilidade (BR8.7).

### Transação

- Uma transação só de leitura em **REPEATABLE READ**, por pessoa. A U1 usa READ COMMITTED no bloco REAL (`JdbcBlockReads`) porque fazia uma consulta só. A U2 faz duas leituras (ordens e lançamentos), e a conferência exata (BR8.1) exige que as duas vejam a mesma fotografia.
- Uma conexão do pool principal por leitura, do começo ao fim (NFR1.16). O evento de auditoria de uma divergência usa um pool próprio de 2 conexões, depois que esta transação fecha (`security-design.md`).
- Sem `FOR UPDATE`, sem `FOR SHARE`, sem trava consultiva (NFR1.10).
- `set local work_mem = '12MB'` no começo da transação (ver "Memória de ordenação").

### Ordem das leituras e orçamento

| Passo (F1) | Leitura | Limite | Índice |
|---|---|---|---|
| 1 | Bots REAL da pessoa (C8, `botsOf`), inclusive pausados e removidos | limite da atribuição: o menor entre agora + 500 ms e o limite do bloco | `bot_owner_state_idx (tenant_id, owner_user_id, state, updated_at DESC)` |
| 2 | Carteiras do bot da pessoa (`botwallet.api`) | limite do bloco | os índices de `bot_wallet_account` que já existem |
| 3 | Ordens assentadas da pessoa em fluxo (C13, `streamSettledOrders`) → índice compacto `realOrderId → OrderAttribution`; conta as ordens e para com `TOO_MANY_ORDERS` acima de `real-max-orders` (F-2) | limite da atribuição | `real_order_user_created_idx (user_id, created_at DESC)`, filtro por `tenant_id`, `tx_signature` e estado; **sem** `ORDER BY` |
| 4 a 7 | Lançamentos das carteiras na ordem de rede (C12, `streamInNetworkOrder`), aplicados um a um | limite do bloco | `real_ledger_entry_wallet_created_idx` e, na junção, `real_entry_slot_pkey` (ou `Hash Join` com `Seq Scan` em `real_entry_slot`, aceito) |
| 8 | Preços dos mints com posição aberta (`marketdata.api`), uma vez | limite do bloco | os da U1 |
| 9 | Conferência (F3), na memória | limite do bloco, conferido antes de começar | — |

A leitura de atribuição do caminho `PNL` é o conjunto dos passos 1 e 3, medida por `pnl_real_attribution_read_seconds{path="PNL"}`. Orçamento no pior caso permitido: 500 ms de atribuição e 300 ms para o resto, dentro dos 800 ms do bloco (D-NFR1).

### Prazo duro dentro do fluxo (revisão R-01)

O `statement_timeout` sozinho não basta: no protocolo estendido do PostgreSQL ele conta por mensagem `Execute`, e a leitura em fluxo com `fetchSize` 500 manda uma por bloco de 500 linhas; o tempo gasto em Java entre os blocos (índice compacto, acumulador de custo) não conta. Por isso toda leitura em fluxo da U2 (`streamSettledOrders`, `streamInNetworkOrder` e a leitura de custo) recebe um **instante limite** (`ReadDeadline`, nome proposto), calculado com o `Clock` injetado, e o aplica em duas camadas:

1. **Em cada linha.** O `RowCallbackHandler` confere o relógio injetado antes de entregar a linha ao `Consumer`. Passou do limite: lança `ReadDeadlineExceeded` (nome proposto). O `JdbcTemplate` fecha o `ResultSet` e o comando; nenhuma linha a mais é processada.
2. **Em cada busca.** Antes do primeiro `Execute` do comando, e de novo na 500ª linha de cada bloco (contador da linha múltiplo do `fetchSize`, antes de o driver pedir o próximo bloco), a leitura confere o limite e aplica, na mesma conexão e na mesma transação, `set local statement_timeout` com o tempo que resta. O PostgreSQL lê o valor no começo de cada `Execute`, então cada busca de 500 linhas fica limitada ao que resta do orçamento, e não ao valor aplicado no começo. Uma busca que passa do limite volta com SQLSTATE `57014`.

**Sem vigia nem `Statement.cancel()` (revisão 02, R-12).** O cancelamento fora de banda do pgjdbc manda um `CancelRequest` por outra conexão TCP, e o servidor cancela o que estiver rodando quando ele chega; um pedido atrasado poderia cair no comando seguinte do chamador (o `ROLLBACK TO SAVEPOINT`, a gravação da avaliação, as escritas do ciclo) e abortar a transação que o ponto de salvamento queria proteger. Com o `statement_timeout` local, o tempo limite é aplicado pelo próprio backend **só ao `Execute` em curso**: o temporizador é armado no começo dele e desarmado no fim, então nunca dispara num comando posterior. Depois da leitura, o valor anterior do `statement_timeout` é restaurado (ou desfeito com o ponto de salvamento), e o comando seguinte do chamador roda com o tempo dele.

O orçamento vale para o **conjunto** das consultas e do processamento em Java. As duas camadas dão a mesma classificação: `ReadDeadlineExceeded` ou `57014` viram `outcome = TIMEOUT` no temporizador, nunca `OK`. Um teste de unidade com relógio falso confere a camada 1; o `AttributionFailureGateIT` e o `RealBlockFailureIT` ganham o caso "o tempo se esgota entre dois blocos, sem `57014`" (um `Consumer` de teste que avança o relógio falso na linha 501).

### Fluxo do JDBC

- `streamSettledOrders` e `streamInNetworkOrder` usam o padrão de `JdbcRealLedger.streamEntries`: `JdbcTemplate` com `setFetchSize(500)` e um `RowCallbackHandler` que entrega cada linha a um `Consumer`, com a conferência do limite acima. O cursor só funciona fora do autocommit, o que a transação garante (NFR1.15, NFR8.7).
- Na memória fica só o índice compacto das ordens (cerca de 100 bytes por ordem; 14.600 na referência, cerca de 1,5 MB) e as posições abertas por carteira, titular e token.
- Nenhum método novo devolve a lista de lançamentos ou de ordens da pessoa. `settledOrders` (lista) continua para os chamadores atuais (C13).

### Forma da consulta principal (`streamInNetworkOrder`)

Descrita em prosa; a Code Generation escreve o SQL.

1. **Seleção.** As linhas de `real_ledger_entry` do tenant e das carteiras pedidas, de **todos** os tipos (a herança de BR1.3 é calculada sobre todas as linhas). O filtro por carteira usa o índice novo; o filtro por tenant fica no SQL (NFR3.1).
2. **Slot gravado.** Junção à esquerda com `real_entry_slot` pela `tx_signature`. `slotStatus`: `RECORDED` com par, `NO_NETWORK_TX` sem assinatura, `SLOT_MISSING` nos demais casos (BR1.5, C14).
3. **Herança (BR1.3, F-1).** Por carteira, na ordem de gravação `(created_at, reference COLLATE "C", kind_rank, idempotency_key COLLATE "C")`, a linha sem slot gravado recebe o último slot gravado antes dela (0 quando ainda não houve nenhum) e `inherited = true`. É feito com funções de janela sobre a partição da carteira: um contador acumulado das linhas com slot gravado define o grupo, e o slot do grupo é o da primeira linha dele. O índice `(bot_wallet_id, created_at)` entrega as linhas já em `created_at` por carteira; o desempate dentro do mesmo `created_at` é uma ordenação incremental de poucos itens.
4. **Ordem de saída (BR1.1).** Por carteira, depois `effectiveSlot`, depois as linhas com slot gravado antes das herdadas, depois `tx_signature COLLATE "C"` só entre as gravadas (BR1.2), depois `created_at`, `reference COLLATE "C"`, `kind_rank` (`SWAP_FILL_IN`/`SWAP_FILL_OUT` = 0, `NETWORK_FEE` = 1, `RENT` = 2, demais = 3) e `idempotency_key COLLATE "C"`. Nunca `entry_id`.
5. **Tipos pedidos.** O filtro de `kinds` vale depois da herança, na consulta externa (BR1.6).

`COLLATE "C"` em todo desempate de texto torna a ordem independente da collation do banco (contêiner da CI, RDS) (revisão R-05). O oráculo do teste de AC3.6.2 ordena os mesmos textos por bytes (`String.compareTo` sobre texto ASCII, que é o caso de assinaturas Base58, referências e chaves de idempotência), e o teste confere que a ordem de saída do SQL é a do oráculo.

### Memória de ordenação (revisão R-04)

A ordem de saída exige uma ordenação de todas as linhas da pessoa. Nós de memória por execução da consulta principal, no pior caso: 1 `Sort` final (cerca de 9 MB com 43.800 linhas de 200 bytes), 1 `Incremental Sort` da janela (grupos de poucas linhas, abaixo de 1 MB) e, quando o planejador escolhe `Hash Join`, 1 `Hash` de `real_entry_slot`, limitado por `work_mem × hash_mem_multiplier` (padrão 2,0).

| Caminho | Onde | `work_mem` local | Pior caso por leitura | Leituras simultâneas | Pior caso total |
|---|---|---|---|---|---|
| P&L REAL (C4 e `GET /api/v1/real/pnl`) | API, pool `pnl-mode-block` | `12MB` | 12 (`Sort`) + 24 (`Hash`) + 12 (`Incremental Sort`) = 48 MB | 8 (`pool-size`; as duas rotas passam pelo pool) | 384 MB |
| Leitura de custo `GATE` e `CYCLE` | worker, dentro do ponto de salvamento | `12MB` (desfeito com o ponto de salvamento, ou restaurado no sucesso) | 48 MB | 5: 4 `cycle-threads` + 1 trabalho do `RealRiskJobs` | 240 MB |
| `positions` e `view` (só quantidade) | worker e API | padrão (4 MB) | só os `SWAP_FILL_*` de uma carteira são ordenados no fim: 14.600 linhas estreitas, cerca de 1,5 MB | — | — |

Os dois processos dividem o mesmo PostgreSQL: 384 + 240 = 624 MB no pior caso teórico, mais os `shared_buffers`, que no contêiner de teste são os 128 MB padrão: abaixo dos 900 MB que o cenário de carga confere. Na prática cada nó usa só o tamanho dos dados (cerca de 9 MB de ordenação), não o teto. O `RealLedgerNetworkOrderPlanIT` exige `Sort Method` diferente de `external merge` na consulta principal (com `work_mem` 12 MB) e no `view` com o histórico de referência (com o padrão de 4 MB).

## Índices: decisão para a PQ-10

| Objeto | Criado por | Uso |
|---|---|---|
| `real_entry_slot_pkey (tx_signature)` | `0023`, changeset `0023-real-entry-slot` | junção pela assinatura; `ON CONFLICT DO NOTHING` do `recordSlot` |
| `real_ledger_entry_wallet_created_idx (bot_wallet_id, created_at)` | `0023`, changeset `0023-real-ledger-entry-wallet-created-idx` | `streamInNetworkOrder`, `positions`, `view` e a leitura de custo |

**Por que um índice novo.** A Q1 previa usar "o índice `real_ledger_entry (wallet_id, created_at, ...)` já existente, ou um novo se o EXPLAIN mostrar que precisa". Esse índice não existe. Os de hoje são `real_ledger_entry_balance_idx (bot_wallet_id, entry_kind) INCLUDE (lamports)`, `real_ledger_entry_reference_idx (bot_wallet_id, reference)` e `real_ledger_entry_tenant_created_idx (tenant_id, created_at DESC, entry_id DESC)` (`0016`). Nenhum entrega as linhas de uma carteira na ordem de gravação. O NFR1.18 exige também que o EXPLAIN use "o índice da `0023` em `real_ledger_entry`" pelo nome. O índice novo cabe na opção "ou um novo" da Q1.

**Por que essas colunas.** `bot_wallet_id` primeiro (todas as leituras filtram por carteira) e `created_at` (ordem de gravação da herança). Um índice cobrindo dobraria o tamanho do ledger sem permitir `Index Only Scan`. Nenhum índice novo em `real_entry_slot` além da chave primária, nem em `real_order`.

**Criação.** `CREATE INDEX` simples, na transação do Liquibase, no `db-migrate`, antes da troca das imagens (NFR8.4). No volume do piloto (até cerca de 100 mil linhas por ano) leva menos de 1 s; uma confirmação que chega nesse momento espera a trava `SHARE` e segue.

**Se o planejador escolher outro índice.** Se o `RealLedgerNetworkOrderPlanIT` mostrar outro, a Code Generation ajusta as colunas do índice da `0023` antes do primeiro commit que a aplica, e nunca afrouxa o teste.

## Conferência do plano (EXPLAIN)

| Onde | Nome | O que confere |
|---|---|---|
| CI (job `backend`, Failsafe) | `RealLedgerNetworkOrderPlanIT` | Semeia 2 pessoas com o volume de NFR1.14 (1 e 2 anos), roda `ANALYZE` e faz `EXPLAIN (FORMAT JSON)` do SQL exato de `streamInNetworkOrder` (constante de pacote do `JdbcRealLedger`) com os ids de uma pessoa. Exige um nó com `"Index Name": "real_ledger_entry_wallet_created_idx"` sobre `real_ledger_entry`, nenhum `Seq Scan` com `"Relation Name": "real_ledger_entry"` e `Sort Method` diferente de `external merge`. Aceita `Seq Scan` em `real_entry_slot`, `bot` e `bot_wallet_account`. Faz o mesmo para o SQL do `view` de uma carteira. Confere o `fetchSize` efetivo (500). |
| Carga (workflow manual `load-test`, cenário `pnl-by-mode`) | `scripts/load/pnl-plans.mjs`, chamado pelo `load-test.mjs` | No padrão do `u6-plans.mjs`: lê uma pessoa da semente numa consulta própria, monta o EXPLAIN com literais e procura `Index Scan using real_ledger_entry_wallet_created_idx on real_ledger_entry`, `Index Only Scan using real_ledger_entry_wallet_created_idx` ou `Bitmap Index Scan on real_ledger_entry_wallet_created_idx`. Falha com `Seq Scan on real_ledger_entry` ou sem o nome do índice. |
| CI (job `backend`, unidade) | `PnlPlanSqlParityTest` | Confere que o texto da consulta em `scripts/load/pnl-plans.mjs` é o mesmo da constante do `JdbcRealLedger`. |

## O teto `real-max-orders` (F-2)

O teto da U1 fica, com o mesmo padrão, o mesmo domínio, a mesma variável de ambiente e a mesma causa. Na U2 ele é aplicado dentro do fluxo de `streamSettledOrders`: o `RowCallbackHandler` conta as ordens e, na ordem `real-max-orders + 1`, lança `RealPnlUnavailable(TOO_MANY_ORDERS)`; nenhum número parcial sai. O `limit` da consulta da U1 não é mais necessário, porque nada é carregado numa lista. Na referência (14.600 ordens por pessoa) o teto de 50.000 nunca dispara: o cenário `pnl-by-mode` exige 0 blocos `TOO_MANY_ORDERS` (NFR1.6). O teto vale só no caminho `PNL`; os caminhos `GATE` e `CYCLE` ficam limitados pelo prazo de 250 ms.

## Caminho da barreira e do ciclo (F6, NFR1.4, NFR1.5, NFR1.26)

### Só quantidade

`JdbcRealLedger.positions` e `view` passam a ler os `SWAP_FILL_IN` e `SWAP_FILL_OUT` da carteira na chave de ordem de rede, com a mesma junção e a mesma herança, no mesmo comando único do `view` (revisão R-01 do 261113). A parte pura de `RealCostMethod` aplica IN soma, OUT tira `min(vendido, corrente)`. As posições saem com a quantidade e com `cost = RealCost.Unknown(NOT_READ)`: o custo não é lido aqui (F-3). Nenhuma leitura de C13 nem de C8 (NFR1.11). O `view` ganha uma parte a mais no mesmo comando: o número de linhas da carteira (`walletRowCount`), usado pela leitura de custo (ver "Leitura de custo").

Meta: o p95 de `evaluate` de compra (sem posição sem preço) e de venda fica no maior entre 1,10 × a linha de base e a linha de base + 2 ms, com os tetos de 150 ms e 30 ms (NFR1.5).

### Tipo do custo (F-3, revisão R-02)

| Tipo (C11, `ledger.api`) | Forma |
|---|---|
| `RealCost` | selado: `Known(long lamports)` com `lamports >= 0`, ou `Unknown(RealCostUnknownReason reason)` |
| `RealCostUnknownReason` | `NOT_READ` (caminho só de quantidade), `TIMEOUT`, `ERROR`, `UNKNOWN_BOT`, `INCONSISTENT_READ` |
| `RealPosition` | `(String mint, long tokenBaseUnits, RealCost cost)`; o `long costLamports` sai |
| `pnl.api.RealWalletCost` (nome proposto) | `WalletCost costOf(UUID tenantId, UUID userId, UUID botWalletId, CostPath path, long expectedWalletRows)`; `WalletCost` selado: `Known(Map<String, Long> costByMint)` ou `Unknown(RealCostUnknownReason)` |

Nenhum consumidor converte `Unknown` em 0 ou em `null`: o compilador obriga o tratamento dos dois casos (`switch` com padrões sobre o tipo selado). Usuários adaptados:

| Usuário | Mudança |
|---|---|
| `RealGateInputsReader.priced` | `PricedPosition` leva `RealCost`. No `buy`, o custo fica `NOT_READ` e nunca é pedido (a posição sem preço recusa a compra antes). No `value` (rodadas), para cada posição sem preço, pede `RealWalletCost` uma vez e troca `NOT_READ` pelo resultado. |
| `RealRiskPolicy.positionsValue` | devolve um valor selado (`Known(long)` ou `Unknown`): posição com preço vale a cotação; sem preço e com custo `Known` vale o custo; sem preço e com custo `Unknown` deixa o valor da carteira `Unknown`. Na compra o caso não chega aqui (recusa anterior em `positionPrices`). Nas rodadas, valor `Unknown` não grava fotografia nem pausa e segue F-4. |
| `RealRiskJobs.photo` e `judge` | valor `Unknown` no `photo`: grava a marca de fotografia adiada do dia (F-4, `real_risk_day_deferred`), não grava fotografia, conta como falha e tenta de novo na próxima rodada (5 min); a primeira compra do dia fica recusada até a fotografia existir. Valor `Unknown` no `judge`: não pausa nem libera; conta como falha. |
| `RealCycle.inputs` | pede `RealWalletCost` (caminho `CYCLE`) para as posições; custo `Unknown` deixa o `PositionInput` com custo desconhecido, e o ciclo não decide compra desse token; a venda segue pela quantidade. **Saídas:** com custo `Unknown`, as regras de saída que dependem só do preço (preço-alvo absoluto, queda desde a máxima observada, tempo) decidem normalmente; toda regra que depende do custo (parada de perda e tomada de lucro relativas ao preço de entrada) é pulada naquele ciclo, com o motivo `COST_UNKNOWN` registrado no resultado da decisão e no log, e volta no ciclo seguinte com custo `Known`. A venda pedida pela pessoa e a liquidação não dependem disso. |
| `LiquidationJob`, venda da barreira, saque | não leem o custo. |
| `RealPnlController` (`GET /api/v1/real/pnl`) | na fase 2, as posições vêm do RealBotPnl (BR10.3), em que o custo é sempre `Known` ou o bloco inteiro fica indisponível. Na fase 1 a rota continua com o `RealPnlCalculator` da U1, que não usa `RealPosition`. |

Regra ArchUnit nova: só `riskcontrol.real`, `bots.worker` e `pnl` dependem de `RealCost.Known`; `realexecution` (liquidação) e `botwallet` (saque) não dependem de `RealCost`; `RealGateInputsReader.buy` não depende de `RealWalletCost`. Testes obrigatórios novos: compra com uma posição sem preço e custo `Unknown` é recusada sem nenhuma leitura de atribuição, e venda, liquidação e saque seguem (`AttributionFailureGateIT`); a fotografia adiada (F-4, `DaySnapshotDeferredIT`, em `reliability-design.md`); a saída por custo pulada com `COST_UNKNOWN` e a saída por preço mantida (`RealCycleAttributionIT`).

### Leitura de custo com atribuição (caminho `GATE` e `CYCLE`)

Quem pede: só os caminhos que usam o custo (revisão 02, R-13): o `RealGateInputsReader.value` chamado pelas rodadas `RealRiskJobs.photo` e `judge` (caminho `GATE`), quando uma posição está sem preço, e o `RealCycle.inputs` (caminho `CYCLE`). O `RealGateInputsReader.buy` **nunca** pede o custo: uma posição sem preço utilizável já recusa a compra em `positionPrices` (`POSITION_PRICE_UNKNOWN`) antes de `positionsValue`, e com todas as posições com preço o custo não entra no valor; a fotografia `FIRST_EVALUATION` que a compra grava também só usa preços (e segue F-4). A leitura (`AttributedCostReader`, em `pnl.application`, atrás de `pnl.api.RealWalletCost`) faz, para uma carteira:

1. os bots da pessoa (C8);
2. as ordens assentadas da pessoa (C13), para o índice compacto;
3. os lançamentos da carteira na ordem de rede (C12), pedidos com **todos os tipos**, contando todas as linhas recebidas; o acumulador ignora em Java os tipos que não são de negociação;
4. o acumulador de custo por titular (BR2.1, BR2.2), com o custo por token como soma dos titulares (BR2.4).

O orçamento de 250 ms cobre os quatro passos e o processamento em Java, pelo prazo duro em duas camadas. Nenhum desses caminhos segura a `WalletLock`: as rodadas e o ciclo leem fora dela. O temporizador `pnl_real_attribution_read_seconds{path="GATE"|"CYCLE"}` mede os quatro passos.

**Consistência com o `view` (revisão R-08).** Um ponto de salvamento não pode trocar o isolamento da transação (o PostgreSQL só aceita `SET TRANSACTION ISOLATION LEVEL` antes do primeiro comando), então REPEATABLE READ dentro do ponto de salvamento não é possível. A escolha é **provar que o resultado é o de uma fotografia só, ou falhar fechado**:

- O ledger é só de inserção. O `view` devolve, no mesmo comando, o número de linhas da carteira. A leitura de custo conta as linhas da carteira que o fluxo entregou. Números iguais significam o mesmo conjunto de linhas, porque um conjunto lido depois só pode ser maior. Números diferentes (um lançamento entrou entre o `view` e a leitura de custo): `WalletCost.Unknown(INCONSISTENT_READ)`; a rodada daquela carteira ou o ciclo tenta de novo na próxima vez.
- **Condição 1 da prova: ordem e filtro.** O `view` é lido **antes** da leitura de custo, na mesma transação, e o chamador passa o número dele (`expectedWalletRows`) para `RealWalletCost.costOf`. O `walletRowCount` do `view` conta **todas** as linhas da carteira com o mesmo filtro da seleção do fluxo (`tenant_id`, `bot_wallet_id`, `mode = 'REAL'`, todos os tipos), e o fluxo da leitura de custo é pedido com todos os tipos, para contar o mesmo conjunto. Um teste confere que os dois filtros vêm do mesmo trecho de SQL.
- **Condição 2 da prova: a taxa esperada de `INCONSISTENT_READ`.** Só um lançamento da carteira gravado entre o `view` e o fim da leitura de custo (no máximo cerca de 300 ms) dá `INCONSISTENT_READ`. Na referência de NFR1.14 (10 bots, 4 ordens por bot por dia, 2 a 3 lançamentos por ordem), são até cerca de 120 lançamentos por carteira por dia: 120 × 0,3 s ÷ 86.400 s ≈ 0,04% das leituras. No piloto (3 bots), cerca de 0,013%. Duas falhas seguidas da mesma carteira são da ordem de 1 em 10 milhões, e a rodada do dia tenta de novo a cada 5 min. O `RealAttributionMeasurementIT` registra o número de `INCONSISTENT_READ` com lançamentos concorrentes na densidade de referência, e o relatório de medida do Build and Test traz a taxa; acima de 1%, a escolha volta ao fundador. Em produção a leitura conta como `outcome = ERROR` no temporizador, e o log de falha da atribuição leva `reason = INCONSISTENT_READ`.
- Uma ordem ou um bot confirmados entre o passo 1 ou 2 e o passo 3: a confirmação grava o estado da ordem e os lançamentos na mesma transação, então todo lançamento visto no passo 3 tem a ordem já confirmada. Um lançamento `order:<id>` sem a ordem no índice compacto ganha uma leitura pontual da ordem pelo id (e do bot pelo id, no diretório da pessoa) antes de ser tratado como `ORDER_RECORD_MISSING` ou `UNKNOWN_BOT`. Com isso, cada lançamento visto é atribuído como numa fotografia única.

`AttributedCostConsistencyIT`: um lançamento inserido por outra conexão entre o `view` e a leitura de custo dá `INCONSISTENT_READ` (a rodada não grava fotografia; o ciclo não decide compra); uma ordem confirmada entre o passo 2 e o passo 3 é atribuída ao bot certo pela leitura pontual.

**Mecanismo de prazo, no ponto de salvamento.** A leitura roda num ponto de salvamento da transação do chamador (`TransactionTemplate` com `PROPAGATION_NESTED`, como a elegibilidade da U1):

1. Lê e guarda os valores correntes de `statement_timeout` (a transação do ciclo tem 5 s) e de `work_mem`.
2. Abre o ponto de salvamento e calcula o instante limite (relógio injetado + 250 ms).
3. Aplica `set local work_mem = '12MB'` e, antes de cada comando e de cada busca de 500 linhas, `set local statement_timeout` com o tempo que resta; confere o limite em cada linha.
4. **Sucesso:** aplica de novo os valores guardados no passo 1 (o `RELEASE SAVEPOINT` manteria os `SET LOCAL`), libera o ponto de salvamento e devolve o custo.
5. **Falha** (`57014`, `ReadDeadlineExceeded`, outra exceção de acesso a dados, `UNKNOWN_BOT`, `INCONSISTENT_READ`): `ROLLBACK TO SAVEPOINT`, que desfaz o erro do PostgreSQL e os `SET LOCAL`. A transação do chamador continua utilizável. O resultado é `WalletCost.Unknown` com a causa.

**Compra com posição sem preço (NFR1.26, reescopado).** Como a compra não lê a atribuição, ela é recusada com o custo de uma compra de hoje: p95 dentro da meta de NFR1.5 (teto de 150 ms), abaixo dos 400 ms do NFR1.26, que continua como teto. A espera pela trava continua ≤ 1.000 ms (U3-NFR3.3). A meta de 250 ms (NFR1.4) vale só para a leitura de custo das rodadas (`GATE`) e do ciclo (`CYCLE`).

## Medição

| Medida | Onde | Como |
|---|---|---|
| Linha de base da barreira (NFR1.5, NFR1.7) | `RealRiskMeasurementIT`, 3 execuções no último commit antes do código da U2 | Mediana dos 3 p95 de compra e de venda em `docs/perf/u2-barrier-baseline.md`, com SHA e máquina, no repositório **antes** do primeiro commit que muda `RealGateInputsReader`, `JdbcRealLedger.positions` ou `view` (passo 0). |
| Barreira com a U2 (NFR1.5) | `RealRiskMeasurementIT`, 3 execuções na mesma máquina | Mesma condição (21 carteiras, 10 posições, 4 avaliações por segundo, todas com preço). |
| Leitura de custo `GATE` e `CYCLE` (NFR1.4), compra com posição sem preço (NFR1.26) e taxa de `INCONSISTENT_READ` | `RealAttributionMeasurementIT` (novo) | 21 carteiras com 10 posições, 2 sem preço por carteira, 2 anos de ordens na densidade de NFR1.14; 4 leituras de custo por segundo com lançamentos concorrentes na densidade de referência; 10 aquecimentos e 100 medidas por caso; p95 pelo posto mais próximo; registra a taxa de `INCONSISTENT_READ`. A compra com posição sem preço é medida como em NFR1.5 e confere que nenhuma leitura de atribuição acontece. |
| C4 (NFR1.1) e a taxa de `TIMEOUT` (NFR1.3, NFR1.6) | cenário manual `pnl-by-mode` | Semente e tráfego de NFR1.6; portões de D-NFR1, abaixo. |

Na CI os testes de medida só registram o p95 e falham quando uma avaliação falha (NFR1.8). No Build and Test as medianas são comparadas com as metas.

## Portão de carga: cenário `pnl-by-mode` (revisão R-03)

O cenário, a semente, os portões e o texto pronto do `.github/workflows/load-test.yml` estão em `nfr-requirements/performance-requirements.md`, NFR1.6 e NFR1.9 (o texto pronto do YAML está lá, inteiro). Este estágio não repete o texto; ele define como os portões são medidos:

- **Contador.** O contador da U1 `pnl.mode_block.unavailable` (`PnlModeMetrics`, hoje só com `market`) ganha a etiqueta `cause` ∈ {`TIMEOUT`, `REJECTED`, `ERROR`, `RECONCILIATION_MISMATCH`, `TOO_MANY_ORDERS`}. São 2 mercados × 5 causas = 10 séries, todas registradas com 0 na subida (eram 2). `TIMEOUT` cobre **qualquer** parte do bloco: espera na fila além do prazo, prazo esgotado antes de abrir a transação, orçamento da atribuição, prazo do bloco no fluxo dos lançamentos, no rateio, nos preços ou na conferência, `57014` e `ReadDeadlineExceeded`. `REJECTED` é a fila cheia; `ERROR` é qualquer outra falha técnica.
- **Denominador.** As respostas 200 de `GET /api/v1/pnl/by-mode` que o `load-test.mjs` conta depois dos 60 s de aquecimento.
- **Portão de `TIMEOUT`.** O aumento de `pnl_mode_block_unavailable_total{market="REAL",cause="TIMEOUT"}` entre o fim do aquecimento e o fim da carga, lido pelo `load-test.mjs` em `/actuator/prometheus`, dividido pelo denominador, ≤ 0,5%. É esse o portão efetivo da atribuição no caminho `PNL` (revisão R-08 da NFR Requirements); o p95 de `pnl_real_attribution_read_seconds{path="PNL"}` sai no relatório só como valor informado.
- **Nenhuma outra causa.** O aumento de `market="REAL"` com qualquer outra causa é 0. O `load-test.mjs` confere também cada resposta (`real.status`): o número de blocos `UNAVAILABLE` contado pelas respostas é igual ao do contador.
- **Plano:** `scripts/load/pnl-plans.mjs`, como na tabela "Conferência do plano".
- **Fechamento:** o Build and Test da U2 só fecha com uma execução verde no SHA candidato, disparada pelo fundador depois de aplicar o texto de NFR1.9 (NFR1.25, D-NFR3). A redução de pessoas simultâneas segue o NFR1.25 (nunca abaixo de 5, meta de 1 s fixa). C4 acima de 1.000 ms com o índice da `0023` dispara o plano B de NFR8.11, que volta ao fundador.

Os testes da U1 que leem `pnl.mode_block.unavailable` só com `market` (`ModePnlViewTest`, `AbstractPaperRealIT`) passam a somar as séries de cada mercado.

## Sources

- `nfr-design-questions.md`: Q1, Q2 e Q4 e o resumo confirmado [Q1] [Q2] [Q4].
- `reviews/review-02.md` (R-10 a R-14) e a decisão do fundador F-4 (2026-11-22).
- `reviews/review-01.md` (R-01 a R-05, R-08) e as decisões do fundador de 2026-11-22 (F-1 a F-3).
- `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md`: NFR1.1 a NFR1.13, NFR1.25, NFR1.26, D-NFR1, D-NFR3 e o texto pronto do `load-test.yml` (NFR1.9); `scalability-requirements.md`: NFR1.14 a NFR1.18; `observability-requirements.md`: NFR1.23; `reviews/review-02.md`: R-08.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F1, F6.1, F6.2, F9; `rules.md`: BR1.1 a BR1.6, BR2.5, BR2.7, BR10.1, BR10.2; `entities.md`: ENT-201, ENT-204.
- `inception/contract-design/contract-summary.md`: C10, C11, C12, C13, C14, PQ-10.
- `construction/u1-mode-skeleton/nfr-design/performance-design.md` (pool `pnl-mode-block`, prazo do bloco, `real-max-orders`) e `reliability-design.md` (ponto de salvamento).
- Código lido: `ledger/infra/JdbcRealLedger.java`, `ledger/api/RealPosition.java`, `db/changelog/changes/0016-real-bot-wallet.sql`, `0019-real-execution.sql`, `realexecution/infra/JdbcRealExecutionStore.java` (`settled`), `riskcontrol/real/infra/WalletLock.java`, `RealGateInputsReader.java`, `TransactionalRealRiskGate.java`, `RealRiskJobs.java`, `riskcontrol/real/domain/RealRiskPolicy.java` (`positionsValue`), `bots/worker/RealCycle.java`, `realexecution/infra/LiquidationJob.java`, `pnl/web/RealPnlController.java`, `pnl/application/RealBotPnlQueryV1.java`, `pnl/infra/PnlByModeProperties.java`, `pnl/modeview/JdbcBlockReads.java`, `pnl/modeview/PnlModeMetrics.java`, `pnl/api/RealPnlUnavailable.java`, `application.yml` (`cycle-threads: 4`, Hikari), `scripts/load/load-test.mjs`.
- `aidlc/spaces/default/memory/team.md`: Testing Posture, Deployment; Code Style (`unknown` como tipo explícito).

## Assumptions & Open Questions

1. [assumption] Os 200 bytes por linha, os 128 MB de `shared_buffers` do contêiner de teste e o `hash_mem_multiplier` padrão são estimativas; o `RealLedgerNetworkOrderPlanIT` confere o `Sort Method`, e o cenário de carga confere a memória do `postgres`.
2. [assumption] Passar o `GET /api/v1/real/pnl` pelo pool `pnl-mode-block` é decisão deste estágio, para limitar as leituras REAL simultâneas a 8. A forma da resposta não muda; a fila cheia dá o erro técnico que a rota já tem.
3. [assumption] `ReadDeadline`, `ReadDeadlineExceeded`, `AttributedCostReader`, `RealWalletCost`, `RealCost`, `RealCostUnknownReason`, `WalletCost` e `walletRowCount` são nomes propostos. A dependência `riskcontrol.real.infra → pnl.api` e `bots.worker → pnl.api` entra nas regras ArchUnit; a Code Generation confere que a regra de ciclos entre módulos continua verde.
4. [assumption] A leitura pontual de uma ordem ou de um bot no meio do fluxo usa a mesma conexão, com o cursor aberto; o pgjdbc aceita outro comando enquanto um portal está aberto na mesma transação. O `AttributedCostConsistencyIT` confere.
5. A Q1 citou um índice "já existente" que não existe; a decisão (um índice novo na `0023`) está dentro da própria resposta e do NFR1.18.
