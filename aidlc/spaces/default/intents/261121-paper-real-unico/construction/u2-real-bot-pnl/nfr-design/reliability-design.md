# Desenho de confiabilidade — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md`, `reviews/review-02.md` e das decisões do fundador de 2026-11-22 (F-1 a F-4, em `performance-design.md`).

Este arquivo cobre o comportamento sob falha, a exatidão dos valores e a verificação da U2, inclusive a construção em duas fases com o ponto de controle interno [Q2] [Q4]. O mecanismo de prazo (`statement_timeout` e ponto de salvamento) está em `performance-design.md`; aqui está o que cada caminho faz quando ele dispara.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Disponibilidade | Nenhum SLO novo; nenhuma leitura da U2 na verificação de saúde. | NFR4.4 |
| Bloco REAL falhando | C4 responde 200; só o bloco REAL sai `UNAVAILABLE`; o PAPER sai igual. | NFR4.5 |
| Atribuição falhando na barreira | Ponto de salvamento desfeito; a transação do chamador continua utilizável; a quantidade segue, a compra é recusada, a venda, a liquidação e o saque seguem. | NFR3.11, NFR1.20 |
| Conflito de slot | Nunca derruba a confirmação; o primeiro slot fica. | NFR1.21 |
| Leitura repetida | Sem efeito colateral fora do evento limitado. | NFR1.22 |
| Exatidão | Igualdade exata em inteiros; divergência = bloco `UNAVAILABLE`, sem número parcial. | NFR2.1 a NFR2.6 |
| Construção | Duas fases, com o ponto de controle interno entre elas; divisão da U2 se a fase 1 não ficar verde. | NFR7.4 |

## Disponibilidade (NFR4.4, NFR4.5)

- O grupo de prontidão (`readinessState`, `db`, `migrations`) não muda. Nenhuma leitura da U2 entra no health check: um P&L lento nunca tira a API do balanceador.
- O bloco REAL roda na tarefa própria do pool `pnl-mode-block` (U1). Qualquer falha da U2 vira `ModeBlockUnavailable` só daquele bloco. A resposta de C4 é sempre 200.

| Causa | Código do bloco REAL | `cause` em `pnl_mode_block_unavailable_total` | Evento de auditoria | Outro contador |
|---|---|---|---|---|
| Divergência (qualquer das 7 verificações) | `RECONCILIATION_MISMATCH` | `RECONCILIATION_MISMATCH` | 1 por impressão digital (pessoa e tipos de verificação, sem valores; revisão R-05) e hora, gravado depois da transação do bloco (revisão R-01) | `pnl_real_reconciliation_mismatches_total{check}` |
| `UNKNOWN_BOT` | `RECONCILIATION_MISMATCH` | `RECONCILIATION_MISMATCH` | idem, com a verificação `UNKNOWN_BOT` | idem, `check="UNKNOWN_BOT"` |
| Atribuição com erro | causa técnica (`SERVICE_UNAVAILABLE`, como na U1) | `ERROR` | nenhum | `pnl_real_attribution_read_seconds{path="PNL",outcome="ERROR"}` |
| Tempo esgotado em qualquer parte do bloco: atribuição (500 ms), fluxo do ledger, rateio, preços ou conferência (prazo de 800 ms), por `57014` ou pela conferência do instante limite no fluxo | causa técnica | `TIMEOUT` | nenhum | `outcome="TIMEOUT"` quando foi na atribuição |
| Erro no fluxo do ledger | causa técnica | `ERROR` | nenhum | — |
| Mais de `real-max-orders` ordens (F-2) | `TOO_MANY_ORDERS`, como na U1 | `TOO_MANY_ORDERS` | nenhum | — |
| Fila do pool cheia | causa técnica, como na U1 | `REJECTED` | nenhum | — |

Teste `RealBlockFailureIT` com os 7 casos: 200 em todos, bloco PAPER com os mesmos números de antes, a série certa de `pnl_mode_block_unavailable_total{market="REAL",cause}` subindo 1, e o caso "o tempo se esgota entre dois blocos de 500 linhas do fluxo do ledger, sem `57014`" dando `TIMEOUT`.

## Atribuição falhando na barreira e no ciclo (BR2.7, NFR3.11, NFR1.20)

### Por que a transação precisa continuar utilizável

No PostgreSQL, um comando cancelado por `statement_timeout` (SQLSTATE `57014`) deixa a transação abortada. Hoje, uma falha de leitura dentro da barreira vira `RealGateUnavailable` com `transactionUsable() = false`, a barreira marca a transação como só para desfazer e o ciclo inteiro do worker é desfeito (revisão R-02 do 261113). Se a leitura de custo rodasse direto na transação do ciclo, um `TIMEOUT` dela desfaria também as vendas do mesmo ciclo. Por isso ela roda num ponto de salvamento (`PROPAGATION_NESTED`): o `ROLLBACK TO SAVEPOINT` limpa o erro e o `SET LOCAL`, e a transação segue [Q2].

### O que cada caminho faz

| Caminho | Quem pede o custo | Com a leitura falhando |
|---|---|---|
| Compra (`evaluate` BUY, sob a `WalletLock`) | **ninguém** (revisão 02, R-13): uma posição sem preço utilizável recusa a compra em `positionPrices` (`POSITION_PRICE_UNKNOWN`) antes de o custo ser usado | nada a fazer: a compra nunca lê a atribuição e nunca segura a trava à espera dela. Sem fotografia do dia numa carteira com a marca de adiamento, a compra é recusada (F-4, abaixo). |
| Venda (`evaluate` SELL) | ninguém: a venda não toma a trava e não lê o custo | segue pela quantidade do caminho só de quantidade |
| Liquidação (`LiquidationJob`) | ninguém: usa `ledger.positions` (quantidade) | segue |
| Saque (`botwallet`) | ninguém: usa o saldo do ledger | segue, sempre (`project.md` ALWAYS saque disponível) |
| Fotografia do dia e perda diária (`RealRiskJobs.photo`, `judge`) | `RealGateInputsReader.value` (caminho `GATE`) | valor `Unknown` no `photo`: nenhuma fotografia; grava a marca de adiamento do dia (F-4); a rodada conta como falha e tenta de novo em 5 min. `RealGateUnavailable(ATTRIBUTION)` com `transactionUsable = true` (fábrica nova, por exemplo `afterSavepoint`), porque o ponto de salvamento já devolveu a transação, e conta `real_risk_errors_total{dependency="attribution"}`. Valor `Unknown` no `judge`: não pausa nem libera. |
| Ciclo REAL (`RealCycle.inputs`) | o custo das posições para o motor | a quantidade chega; o custo chega como `RealCost.Unknown`; o ciclo não decide compra desse token; a venda segue pela quantidade; as saídas por preço decidem e as saídas por custo são puladas com `COST_UNKNOWN` (`performance-design.md`, tabela de usuários) |

`RealGateUnavailable.Dependency` ganha `ATTRIBUTION`, com o rótulo `attribution` em `real_risk_errors_total`. O alarme `real_risk_errors` que já existe soma todas as dependências; uma atribuição falhando na barreira é falha da barreira e merece o mesmo aviso.

Teste `AttributionFailureGateIT`, na transação de uma rodada e na do ciclo, com erro, `TIMEOUT` por `57014` (consulta com `pg_sleep` acima de 250 ms num duplo da leitura), `TIMEOUT` sem `57014` (o relógio falso passa do limite entre dois blocos de 500 linhas; a leitura termina em até 250 ms do início e o temporizador registra `TIMEOUT`, não `OK`), `UNKNOWN_BOT` e `INCONSISTENT_READ`: depois da falha, a mesma transação ainda grava (prova de que ela continua utilizável), o comando seguinte do chamador não recebe `57014`, e o `statement_timeout` e o `work_mem` voltam ao valor de antes (5 s do ciclo). Com custo `Unknown` e preço desconhecido, a compra é recusada sem nenhuma leitura de atribuição (contador do temporizador sem mudança) e venda, liquidação e saque são aceitos (3 de 3). `RealCycleAttributionIT`: com a leitura de custo falhando, 0 decisões de compra do token afetado, 1 venda aceita, uma saída por preço decidida, uma saída por custo pulada com `COST_UNKNOWN`, e o ciclo termina sem erro.

### Fotografia do dia adiada (decisão F-4 do fundador, revisão 02, R-11)

Antes de F-3, o valor de uma posição sem preço era o custo, sempre disponível, e a rodada do dia gravava a fotografia às 00:00 UTC. Com custo `Unknown`, a rodada não pode gravar. Sem regra nova, a primeira compra do dia, depois que o preço voltasse, gravaria a fotografia `FIRST_EVALUATION` com o valor **depois** da queda, e a perda desde o começo do dia sumiria do limite.

**Regra.**

1. A rodada do dia (`RealRiskJobs.photo`, a cada 5 min) que não consegue o valor de uma carteira por custo `Unknown` grava uma marca de adiamento: uma linha em `real_risk_day_deferred` (`tenant_id`, `bot_wallet_id`, `day`, `reason = COST_UNKNOWN`, `deferred_at`; chave `(bot_wallet_id, day)`; `INSERT ... ON CONFLICT DO NOTHING`).
2. A compra (`evaluate` BUY) sem fotografia de hoje: se existe a marca de adiamento de hoje, a compra é **recusada** (código `POSITION_PRICE_UNKNOWN`, detalhe `DAY_SNAPSHOT_PENDING`) e **não** grava a fotografia `FIRST_EVALUATION`. Sem a marca, vale a BR4.4 de hoje (a primeira avaliação do dia grava a fotografia).
3. Venda, liquidação e saque não olham a fotografia nem a marca e seguem.
4. A rodada tenta de novo a cada 5 min. Numa carteira com a marca, ela só grava a fotografia com custo `Known` para todas as posições, e o valor de cada posição na fotografia é o **maior** entre o valor pela cotação (quando há cotação) e o custo. Assim, um custo `Unknown` nunca produz uma base depois de uma queda: a base nunca fica abaixo do que a regra anterior a F-3 daria (o custo) para a posição que estava sem preço. A fotografia gravada leva `taken_by = DAY_JOB`.
5. Com a fotografia gravada, a compra volta a ser avaliada normalmente, e o `judge` mede a perda desde essa base.

**Dados.** A marca fica numa tabela nova e pequena, dona do `riskcontrol`, na migração `0024-real-risk-day-deferred.sql` (só `CREATE TABLE`, `GRANT SELECT, INSERT` ao papel da aplicação e o mesmo expurgo de `real_risk_day`, pelo `deferred_at`). Ela é aditiva, as imagens anteriores não a leem, e o rollback volta só a imagem. Ela não é tabela de P&L e não guarda resultado de leitura (ADR-004).

**Teste obrigatório `DaySnapshotDeferredIT`.** Posição sem preço e custo `Unknown` às 00:00 UTC: a rodada grava a marca e nenhuma fotografia. O preço volta mais baixo: a primeira compra do dia é recusada com `DAY_SNAPSHOT_PENDING` e não grava fotografia; venda, liquidação e saque seguem. O custo volta a `Known`: a rodada grava a fotografia com a base de cada posição no maior entre cotação e custo; a perda desde o começo do dia aparece no `judge` e, acima do limite, pausa a carteira (`DAILY_LOSS`). Sem a marca, a primeira compra do dia continua gravando `FIRST_EVALUATION`.

## Conflito de slot (BR1.4, NFR1.21)

`recordSlot` roda na transação do gravador:

1. `INSERT ... ON CONFLICT (tx_signature) DO NOTHING`.
2. Uma linha inserida: `RECORDED`.
3. Nenhuma: lê o slot gravado pela chave primária. Igual: `ALREADY_RECORDED`, nada mais. Diferente: `CONFLICT_REFUSED`; o evento `REAL_SLOT_CONFLICT` vai para o outbox na mesma transação e `ledger_real_slot_conflicts_total{writer}` sobe 1 depois do commit.
4. Slot desconhecido na confirmação: `recordSlot` não é chamado; a linha fica `SLOT_MISSING` na leitura.

Nenhum desses casos lança para fora; nenhum comando ali pode falhar por regra de negócio (o `ON CONFLICT` evita o erro de chave duplicada, que abortaria a transação). Um erro técnico do próprio `INSERT` (por exemplo, banco fora) é erro da confirmação inteira, como qualquer outro comando dela, e a confirmação é refeita pelo caminho que já existe (o ChainWatcher repete). Teste `RecordSlotIT` nos três gravadores, com os três resultados, conferindo que a ordem ou o movimento chega ao estado final e os lançamentos ficam gravados.

## Leitura sem efeito colateral (NFR1.22, ADR-004)

- Leitura com `MATCH`: 0 gravações. Com `MISMATCH`: no máximo o evento limitado, numa transação separada.
- Nada é guardado entre leituras. Depois de uma divergência, a leitura seguinte refaz a conferência do zero; quando a causa some, o bloco volta a `PRONTO` sem passo manual.
- Teste `RealPnlIdempotentReadIT`: conta as linhas das tabelas tocadas antes e depois de 3 leituras seguidas.

## Exatidão (NFR2.1 a NFR2.10)

- **Aritmética.** Lamports e unidades base em `long` com `Math.addExact`, `subtractExact` e `multiplyExact`; frações do rateio e do custo médio em `BigInteger`; preços em `BigDecimal`. Um estouro lança e vira bloco `UNAVAILABLE` com causa técnica, nunca um número errado. A regra ArchUnit de ponto flutuante cobre os pacotes novos.
- **Conferência.** Comparação por igualdade (`==` em `long`, `equals` em `BigInteger`), sem tolerância. Em `All`: realizado, taxas, não realizado e total, mais a conservação de custo e quantidade por carteira e token. Em `Range`: realizado e taxas, na mesma passada que confere `All`.
- **Uma só fotografia.** O bloco REAL lê ordens e lançamentos na mesma transação REPEATABLE READ (`performance-design.md`), para que uma ordem confirmada no meio não crie uma diferença falsa.
- **Divergência = defeito.** Bloco `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, sem linha, share ou total REAL em C4, em `botPnl` e em `GET /api/v1/real/pnl` (NFR2.1). Erro técnico e tempo esgotado nunca viram `RECONCILIATION_MISMATCH` nem sobem o contador de divergência (NFR2.2).
- **Sem preenchimento.** As linhas anteriores à `0023` ficam `SLOT_MISSING`, calculadas na leitura, e contam em `ledger_real_slot_missing_total` (NFR2.4).
- **Ordem determinista.** A chave nunca usa `entry_id`; a herança também não: ela usa `(created_at, reference, kind_rank, idempotency_key)` com `COLLATE "C"` em todo desempate de texto (decisão F-1 do fundador). Teste de AC3.6.2: gravar as linhas em outra ordem dá a mesma chave e o mesmo P&L (NFR2.10).
- **Migração aditiva e rollback.** A `0023` só cria objetos novos (tabela, gatilhos, permissões, índice). Prova do NFR2.3 escolhida: `Migration0023AdditiveIT` confere que nenhum objeto que a imagem anterior lê ou escreve foi alterado (lista de colunas, gatilhos e permissões de `real_ledger_entry` e `real_order` iguais antes e depois da migração). Como nada existente muda, isso equivale a subir a imagem anterior contra o banco migrado, e roda na CI sem montar duas versões. Em produção, o rollback volta só a imagem; a tabela e o índice ficam.

### Testes do núcleo financeiro, escritos antes do código (NFR2.7 a NFR2.9)

| Teste | Forma |
|---|---|
| Paridade (BR9.1, NFR2.8) | `RealCostParityTest`, com os três oráculos escritos no próprio teste, sem `RealCostBasis` nem `RealProration`; conferido depois de cada evento e no fim; diferença tolerada 0. |
| Propriedade (BR9.2, NFR2.7) | `RealPnlPropertyTest`, no padrão do `LedgerPropertyTest`: `java.util.Random` com 3 sementes fixas declaradas, pelo menos 1.000 sequências de 10 a 60 eventos por execução; semente e índice na mensagem de falha; tempo na CI ≤ 60 s. |
| Regressão da condicional (BR9.3) | falha contra o `RealPnlCalculator` da U1 antes de passar contra o RealBotPnl. |
| Exemplos (BR9.4) e fronteira do período (BR9.5) | AC3.2.1 a AC3.9.1, AC5.1.1, AC5.3.1, AC5.4.1, AC5.4.2 e os cenários do FR5.4, em unidades base. |

A ordem "teste antes ou no mesmo commit da implementação" é conferida pelo histórico de commits na Code Generation (NFR2.9).

## Construção em duas fases e ponto de controle interno (F9, NFR7.4) [Q4]

### Passo 0 (antes de qualquer código da U2)

A linha de base da barreira (`docs/perf/u2-barrier-baseline.md`, três execuções do `RealRiskMeasurementIT` no último commit antes da U2, NFR1.7) entra num commit próprio. Sem ele, nenhum commit da fase 1 que mude `RealGateInputsReader`, `JdbcRealLedger.positions` ou `view` entra.

### Fase 1 — slot, custo único, caminho só de quantidade e paridade

Os testes do núcleo financeiro vêm antes do código, como no `team.md`.

1. Migração `0023` (os quatro changesets de `security-design.md`) com `RealEntrySlotAppendOnlyIT`, `RealEntrySlotGrantsIT`, `Migration0023AdditiveIT`.
2. `recordSlot` na porta `ledger.api.RealLedger` e nos três gravadores, com `RecordSlotIT`, o evento `REAL_SLOT_CONFLICT` abreviado e com HMAC e `ledger_real_slot_conflicts_total`.
3. `streamInNetworkOrder` com a chave, a herança e `slotStatus`, `ledger_real_slot_missing_total`, `RealLedgerNetworkOrderPlanIT` e o teste de AC3.6.2.
4. `RealCostMethod` puro em `ledger.api` (caminho só de quantidade e acumulador por titular); o tipo selado `RealCost` em `RealPosition` (F-3) e os usuários adaptados (`RealGateInputsReader.priced`, `RealRiskPolicy.positionsValue`, `RealRiskJobs`, `RealCycle.inputs`); `positions` e `view` só de quantidade, na ordem de rede, com `walletRowCount`; o algoritmo de custo do `RealPositions` sai; `LedgerArchitectureTest.theLedgerNeverDependsOnThePnl` sem exceção.
5. `RealCostBasis` (BR2.1 a BR2.4) com os testes escritos antes.
6. A leitura de custo com atribuição para `GATE` (rodadas) e `CYCLE` (`AttributedCostReader`), fora da compra, com o ponto de salvamento, o prazo duro de 250 ms (instante limite por linha e `statement_timeout` reaplicado em cada busca), a fotografia adiada (F-4, migração `0024` e `DaySnapshotDeferredIT`), a conferência de consistência com o `view` (`AttributedCostConsistencyIT`), `ATTRIBUTION` na barreira, `pnl_real_attribution_read_seconds` dos dois caminhos, `AttributionFailureGateIT`, `RealCycleAttributionIT` e `RealAttributionMeasurementIT`.
7. `RealCostParityTest` verde, com AC5.2.1 e AC5.2.2, e os testes da barreira REAL e os do 261113 verdes.

**Ponto de controle interno.** A fase 1 termina quando os sete itens estão no mesmo commit de `main` e o workflow `ci` desse commit está verde (todos os jobs, inclusive `smoke`, ou seja, o `scripts/verify.sh`). A prova segue o `scripts/verify-checkpoint.sh` (local primeiro; se morrer por memória, o run verde do `ci` do mesmo commit). Na fase 1, o C10 continua com o `RealBotPnlQueryV1` da U1: nenhuma tela muda e o P&L mostrado é o da U1.

**Gatilho da divisão.** A primeira passada da fase 1 é o commit que fecha os sete itens mais, no máximo, o commit seguinte de correção que o `team.md` já exige para uma `main` vermelha. Se, depois dele, o `ci` ainda não estiver verde, **ou** se a medição do `RealAttributionMeasurementIT` no Build and Test ficar acima das metas de NFR1.4 ou NFR1.26, a U2 é dividida, como o fundador já decidiu:

- U2a: a fase 1, que segue sozinha para o Build and Test;
- U2b: a fase 2, como Unit nova, dependente da U2a, com este NFR Design valendo para ela sem nova rodada.

O registro da divisão vai para o plano da Code Generation e para o estado do intent, e a U1 continua sem ir para a mainnet até a U2b terminar (NFR8.4).

### Fase 2 — rateio, conferência e o resto

Rateio (BR4) com recebido e taxas; linha "Sem bot" (BR5); taxas (BR6); conferência com o ledger (F3) e o evento limitado (F4, NFR3.15); período (`Range`); leitura de atribuição do caminho `PNL` com 500 ms; troca do `RealBotPnlQueryV1` pela implementação nova de C10, com o teto `real-max-orders` aplicado no fluxo (F-2); `GET /api/v1/real/pnl` pelo pool `pnl-mode-block`; etiqueta `cause` em `pnl.mode_block.unavailable`; textos da tela (BR10.4) e motivos do contrato (BR5.6), no mesmo commit da troca da fórmula (NFR7.5); métricas de divergência e de falha da auditoria; regra e alarme `RealOpsPnlReconciliationMismatch`; seção do runbook; cenário `pnl-by-mode` e o texto pronto do `load-test.yml` entregue ao responsável (NFR1.9); `RealPnlPropertyTest`; os alinhamentos de texto (`logical-components.md`).

## Verificação (NFR7.1 a NFR7.6)

- Volume `Standard`: testes de unidade e de integração de cada BR1.1 a BR10.5, com caminho feliz e pelo menos 2 casos de erro ou de borda por componente.
- Piso de 80% de linhas no backend (JaCoCo, unitários e integração mesclados) e no `apps/web`; `apps/admin` e `apps/txsigner` não mudam.
- Os 8 testes obrigatórios por risco que a U2 toca (NFR7.3) estão mapeados em `security-design.md` e acima.
- `bash scripts/verify.sh` verde nos jobs `smoke` e `e2e`, com o spec `e2e/zz-paper-real-unico` conferindo a linha do bot REAL e que nenhum total soma os modos (NFR7.6).

## Sources

- `nfr-design-questions.md`: Q2 e Q4 e o resumo confirmado [Q2] [Q4].
- `reviews/review-01.md` (R-01 a R-03, R-08) e as decisões do fundador de 2026-11-22 (F-1 a F-3).
- `construction/u2-real-bot-pnl/nfr-requirements/reliability-requirements.md`: NFR4.4, NFR4.5, NFR1.20 a NFR1.22, NFR2.1 a NFR2.10, NFR7.1 a NFR7.6; `security-requirements.md`: NFR3.11, NFR3.15; `performance-requirements.md`: NFR1.7.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F1, F3, F4, F6, F7, F8, F9; `rules.md`: BR1.4, BR1.5, BR2.5, BR2.7, BR8.1 a BR8.7, BR9.1 a BR9.5.
- `inception/contract-design/contract-summary.md`: C10, C12, C13, C14.
- `construction/u1-mode-skeleton/nfr-design/reliability-design.md` (ponto de salvamento, `25P02`, `SET LOCAL` desfeito com o ponto de salvamento).
- Código lido: `riskcontrol/api/RealGateUnavailable.java` (`transactionUsable`, `Dependency`), `riskcontrol/real/infra/TransactionalRealRiskGate.java` (falha fechada, só para desfazer), `RealGateInputsReader.java`, `RealRiskJobs.java`, `bots/worker/RealCycle.java`, `realexecution/infra/LiquidationJob.java`, `ledger/infra/JdbcRealLedger.java`, `ledger/domain/LedgerPropertyTest.java` (padrão das sementes).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Ordering, Volume, Piso, testes obrigatórios), Walking Skeleton (verificação dos pontos de controle), Way of Working (CI vermelha corrigida no commit seguinte), Deployment (rollback só da imagem).

## Assumptions & Open Questions

1. [assumption] A fase 1 inclui, dentro do `RealCostBasis`, o rateio de **quantidade** que o custo de uma venda sem bot precisa (maior resto pela quantidade, BR4.1, BR4.3, BR4.4), porque o custo da carteira é a soma dos titulares (BR2.4) e a leitura de custo da barreira existe na fase 1 (F9, item 2). O rateio do recebido e das taxas, as shares, a origem e a linha "Sem bot" ficam na fase 2, como a Q4 diz.
2. [assumption] A definição de "primeira passada" (o commit dos sete itens mais um commit de correção) e o segundo gatilho (medição acima das metas) são deste estágio; eles concretizam a regra da Q4 sem mudar a decisão do fundador.
3. [assumption] A fábrica de `RealGateUnavailable` com `transactionUsable = true` é necessária porque o construtor de hoje marca a transação como não utilizável sempre que a causa é um erro do banco, o que é falso depois do `ROLLBACK TO SAVEPOINT`.
