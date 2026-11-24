# Desenho de confiabilidade — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-02, R-04, R-05) e da decisão do fundador F3.

A U4 falha fechada: sem a posição própria, o bot não vende pelo ciclo naquele slot e tenta de novo no próximo; o dinheiro continua saindo pelas condicionais, por "Vender tudo e sacar" e pelo saque. A flag sai desligada, liga numa implantação separada e desliga sem migração. Este arquivo cobre NFR2.1 a NFR2.9, NFR2.14, NFR5.1, NFR5.2, NFR6.1, NFR6.2 e NFR7.1 a NFR7.6, com as respostas Q1 a Q4 [Q1] [Q2] [Q3] [Q4].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Disponibilidade | Nenhum SLO novo; a falha persistente tem o alarme por razão (`observability-design.md`). | NFR2.1 |
| Falha da leitura | Toda falha vira `Unavailable(motivo)` dentro da transação só de leitura, que é desfeita. Nada é lançado ao ciclo, e a transação do ciclo ainda nem abriu. | NFR2.3 |
| Repetição | Nenhuma repetição dentro do ciclo e nenhum disjuntor: o ciclo seguinte (5 min) lê de novo; nenhum dado de dinheiro guardado entre ciclos (só a contagem em memória das falhas seguidas por bot, que alimenta o gauge da F3 e nunca muda a decisão). | NFR2.3 |
| Defesa em profundidade | O corte (`OwnSellCap`) recusa `Unavailable`, marca mudada e disponível zero antes de qualquer `recordDecision`. | NFR2.4 |
| Exatidão | Unidades base em `long` com operações exatas, custo `piso(ownCost × available ÷ own)` em `BigInteger`, quantidade da decisão em `BigDecimal`. | NFR2.6, NFR2.9 |
| Migração | `0026` em dois changesets com `lock_timeout` de 5 s; `0027` com índice `CONCURRENTLY`. Falha por `lock_timeout` barra a implantação antes das imagens novas e é repetida pelo runbook. | NFR2.7 |
| Flag | Ligar: API, depois worker, cada um numa implantação só da flag, com as duas consultas só de leitura antes e depois. Desligar: ordem inversa. | NFR2.8 |
| Bot preso | O gauge `real_own_holdings_stuck_bots` e o alarme da F3 avisam de um bot com 3 ou mais leituras indisponíveis seguidas, que a razão não vê (`observability-design.md`). | NFR2.18 (F3) |
| Runbook | 4 seções novas em `docs/runbooks/real-operations.md` (10 a 13). | NFR2.14 |

## Tratamento de falhas da leitura (NFR2.3)

`RealBotHoldingsReader.holdings(tenant, user, bot, budget)`:

```text
deadline = ReadDeadline.after(clock, budget)          # antes de pedir a conexão
try:
  result = readOnlyRepeatableRead.execute(tx -> snapshot(tenant, user, bot, deadline))
  deadline.check()                                   # tempo total, com a espera da conexão
  return result                                      # Available(holdings, readAt)
catch BotNotOwned:                                   return Unavailable(BOT_NOT_OWNED)
catch RuntimeException e when ReadDeadline.isTimeout(e) or deadline passed:
                                                     return Unavailable(BUDGET_EXCEEDED)
catch RuntimeException e:                            return Unavailable(READ_FAILED)
```

| Causa | Motivo | Exemplo |
|---|---|---|
| `ReadDeadlineExceeded`, SQLSTATE `57014`, ou prazo passado ao fim | `BUDGET_EXCEEDED` | leitura lenta, fila de conexões |
| bot fora de `botsOf(tenant, user, REAL)` | `BOT_NOT_OWNED` | bot removido entre o despacho e a leitura, ou defeito |
| `DataAccessException`, `CannotGetJdbcConnectionException`, `ArithmeticException` (estouro exato), qualquer outra `RuntimeException` | `READ_FAILED` | banco fora, erro de SQL, marca de época inconsistente |

- O `TransactionTemplate` desfaz a transação só de leitura em qualquer exceção. Um `Error` da JVM não é capturado e segue como hoje (o ciclo registra `FAILED` pelo `CycleFailureRecorder`).
- O resultado volta ao `CycleRunner` **antes** de `transactions.execute(...)`. A transação do ciclo nunca viu a falha, então não precisa de ponto de salvamento (é a diferença em relação à leitura de custo da U2, que roda dentro do ciclo).
- Com `Unavailable`: todos os mints da carteira vão para `heldNotSellable` e saem de `request.tokens()`, o contexto da decisão grava `OWN_QUANTITY_UNAVAILABLE` com o motivo, `real_own_holdings_unavailable_total{reason}` sobe 1 e sai 1 log `WARN` (`ERROR` para `BOT_NOT_OWNED`). Uma compra de outro token no mesmo ciclo segue como hoje (BR2.6).
- **Sem disjuntor.** A leitura é por bot, a cada 5 min, e uma falha só tira a venda do ciclo daquele bot. Um disjuntor pouparia 0,21 leitura por segundo e esconderia a recuperação; o alarme por razão cobre a falha sistêmica, e o alarme do bot preso (F3) cobre a falha persistente de um bot ou de uma carteira.
- **T5, escrito antes do código:** para cada um dos 3 motivos, 0 exceções chegam ao `CycleRunner`, a nota fica no contexto, os mints saem dos tokens, 0 decisões SELL gravadas, o contador sobe 1 e sai 1 log; o ciclo seguinte chama o RealBotHoldings de novo. Mais: com `pg_sleep` forçado numa consulta, a leitura termina em ≤ 300 ms com `BUDGET_EXCEEDED`; com relógio falso em 251 ms, `BUDGET_EXCEEDED` (NFR1.7).

## Defesa em profundidade no corte (NFR2.4)

`OwnSellCap` (pura, `security-design.md`) é o último ponto antes de `RealDecisionStep.submit`:

| Entrada | Resultado do ciclo | Métrica |
|---|---|---|
| `Unavailable(r)` ou `NotRead` | `REAL_OWN_QUANTITY_UNAVAILABLE`, nota com `r` | nenhuma a mais (a leitura já contou) |
| M1 ≠ M0 | `REAL_OWN_QUANTITY_UNAVAILABLE`, nota `STALE_SNAPSHOT` | `real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}` +1 |
| venda = 0 | `REAL_NO_OWN_QUANTITY` | `{outcome="NO_OWN_QUANTITY"}` +1 |
| 0 < venda < pedido | o resultado do `RealDecisionStep` (`REAL_RECORDED`, ...) com a venda cortada | `{outcome="CAPPED"}` +1 |
| venda = pedido | o resultado do `RealDecisionStep` | nenhuma |

Testes de unidade: em todos os casos de recusa, 0 chamadas de `step.submit` (e, portanto, de `recordDecision`), com 1 incremento do `outcome` certo (NFR2.11).

Além do corte, `NO_OWN_QUANTITY` sobe 1 por mint que F2 tirou dos tokens com disponível 0 (BR1.5), como pede o NFR2.11.

## Venda em aberto que falha e efeito não lido (NFR2.5)

- **BR1.8:** o laço que fecha registros de abertura no `CycleRunner.request` pula os mints de `heldNotSellable`. T3 confere que o registro de abertura de um mint com venda em aberto continua aberto.
- **BR3.4:** o `OrderSettlement` grava o ledger e muda o estado da ordem na mesma transação; o instantâneo vê a venda em aberto ou no ledger, nunca nos dois. Se a leitura do efeito na rede falha, a ordem continua `SENT`, conta como em aberto e o disponível fica 0. T8 confere os dois casos.

## Exatidão (NFR2.6, NFR2.9)

- Quantidade própria e em aberto: `long` com `Math.addExact`, `Math.subtractExact`; `available = Math.max(0, own − open)`.
- Custo da posição de saída: `BigInteger.valueOf(ownCost).multiply(available).divide(own)` (piso, porque os três são ≥ 0), convertido para `long` com `longValueExact`. E5: 40.000.000 lamports.
- Pedido do corte: `quantity.movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact()`.
- R4 da regra ArchUnit: 0 `float`/`double` nas classes novas.
- **T12, paridade:** sem vendas em aberto, quantidade e custo do RealBotHoldings = os do RealBotPnl em 100% dos bots e mints da semente. **T1, T18, T19:** a soma dos bots mais "Sem bot" bate com o ledger REAL por igualdade exata; `pnl_real_reconciliation_mismatches_total` continua 0.

## Migração `0026` (NFR2.7)

`db/changelog/changes/0026-bot-cycle-own-quantity-outcomes.sql`, incluída em `db.changelog-master.yaml` depois da `0025`:

```sql
--changeset memetrade:0026-bot-cycle-outcome-not-valid
SET LOCAL lock_timeout = '5s';
ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck,
    ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL OR outcome IN (<as 12 de hoje>,
        'REAL_NO_OWN_QUANTITY', 'REAL_OWN_QUANTITY_UNAVAILABLE')) NOT VALID;
--rollback <a lista de hoje, NOT VALID, seguida de VALIDATE> (ver abaixo)

--changeset memetrade:0026-bot-cycle-outcome-validate
SET LOCAL lock_timeout = '5s';
ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck;
--rollback SELECT 1;
```

- **Um `ALTER TABLE` só com as duas ações.** O `DROP` e o `ADD ... NOT VALID` pegam o `ACCESS EXCLUSIVE` uma vez, pelo tempo da troca do catálogo, sem varrer a tabela. As 12 de hoje são as da `0020` (`NO_ORDER`, `FILLED`, `NOT_FILLED`, `REJECTED`, `DUPLICATE`, `CONFLICT`, `REAL_RECORDED`, `REAL_ORDER_IN_PROGRESS`, `REAL_LIQUIDATION_ACTIVE`, `REAL_BOT_NOT_OWNED`, `REAL_REJECTED`, `REAL_UNAVAILABLE`).
- **`VALIDATE` num changeset próprio.** O Liquibase roda cada changeset numa transação. No mesmo changeset, o `VALIDATE` varreria a tabela segurando o `ACCESS EXCLUSIVE` do `ADD`. Separado, ele pega só `SHARE UPDATE EXCLUSIVE` e não impede os inserts dos ciclos.
- **Rollback.** O changeset 2 volta com `SELECT 1`, porque uma validação não se desfaz. O changeset 1 volta com a lista de hoje: `SET LOCAL lock_timeout = '5s'`, um `ALTER TABLE ... DROP CONSTRAINT ..., ADD CONSTRAINT ... NOT VALID` e um `ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck`. A validação na mesma transação segura o bloqueio forte durante a varredura. É aceito porque o rollback de migração só roda em ambiente local e de teste: em produção nenhuma migração é revertida, só as imagens voltam (`team.md`). A volta só é segura antes de existir uma linha com um dos valores novos; com a flag desligada nenhuma é gravada.
- **Compatível para trás.** A imagem anterior só grava valores da lista antiga, que a regra nova aceita. A imagem nova grava os dois valores novos. `Migration0026IT` confere os dois sentidos e que o número de linhas de `bot_cycle` não muda.

### Falha por `lock_timeout` e repetição

- Se uma transação longa segura `bot_cycle` por mais de 5 s, o changeset 1 falha com SQLSTATE `55P03` (`lock_not_available`). A transação dele é desfeita (a regra antiga continua inteira), o Liquibase não grava a linha em `DATABASECHANGELOG`, o processo `db-migrate` sai com código diferente de zero e a implantação para antes de as imagens novas subirem. As imagens antigas continuam servindo.
- Se o changeset 2 falha por tempo, a regra fica `NOT VALID`, mas já vale para toda linha nova; a repetição roda só o changeset 2.
- **Passo de repetir** (runbook, seção 12): ler o `55P03` no log do `db-migrate`; conferir em `pg_stat_activity` a transação que segurava a tabela e esperar que ela termine (nunca matar à força a do ciclo ou a da fila); disparar de novo a mesma implantação com o mesmo SHA.
- **`Migration0026LockTimeoutIT`:** com uma transação aberta que segura `bot_cycle` (`LOCK TABLE bot_cycle IN ACCESS SHARE MODE`), o changeset 1 falha em ≤ 6 s, a regra antiga continua (um insert com `REAL_NO_OWN_QUANTITY` é recusado) e não há linha do changeset em `DATABASECHANGELOG`. Depois de fechar a transação, a segunda execução aplica os dois changesets.

### Migração `0027`

O índice parcial da fila (`performance-design.md`) usa `CREATE INDEX CONCURRENTLY` com `runInTransaction:false`. Um build interrompido deixa o índice `INVALID` e nenhuma linha em `DATABASECHANGELOG`; a execução seguinte o remove (`DROP INDEX CONCURRENTLY IF EXISTS`) e cria de novo (`Migration0027IndexRestartIT`). O `CONCURRENTLY` espera as transações abertas terminarem; as do consumidor e do ciclo duram segundos. O runbook ganha a nota na mesma seção 12.

## Flag: ligar, conferir e desligar (NFR2.8, BR5.2, BR5.5)

**(a) Saída do código.** O commit da U4 sai com `MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` nos dois mapas do Terraform (NFR8.4).

**(b) Pré-condições.** A Performance Validation do 261113 com resultado aprovado, a medida de NFR1.5 dentro da meta (ou a decisão do fundador sobre ela) e a aprovação do fundador. O fundador acompanha a janela de observação e os 30 minutos seguintes (`team.md`).

**(c) Ordem para ligar**, primeiro na devnet e depois na mainnet com o mesmo SHA, cada passo uma implantação só da flag, nunca com código:

1. Implantação 1: só `api_environment` (o aviso aparece; o ciclo ainda é o de hoje).
2. Conferência antes da implantação 2 (runbook, seção 11), numa sessão só de leitura com um instantâneo só para as consultas (revisão R-04):

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT now() AS t1;   -- anotar
SELECT count(*) FROM real_decision_request WHERE side = 'SELL' AND state = 'PENDING';
SELECT count(*) FROM real_order WHERE side = 'SELL' AND bot_id IS NOT NULL
   AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS');
COMMIT;
```

   Os dois precisam dar 0. Se não, o fundador espera (uma ordem `AMBIGUOUS` segue o runbook do 261113 até se resolver) e confere de novo.
3. Implantação 2: só `worker_environment`.
4. Depois que a última tarefa antiga sai da troca rolling, repetir as duas contagens e listar as decisões SELL gravadas por tarefa antiga entre `t1` e agora. A nota de quantidade própria no contexto da decisão só é gravada pela imagem com a flag ligada:

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
-- as duas contagens do passo 2, de novo, e:
SELECT r.decision_request_id, r.bot_id, r.created_at
  FROM real_decision_request r JOIN decision d ON d.decision_id = r.decision_id
 WHERE r.side = 'SELL' AND r.created_at >= :t1
   AND NOT jsonb_exists(d.context, 'ownQuantity');
COMMIT;
```

   `jsonb_exists` no lugar do operador `?`, que um cliente JDBC trata como marcador de parâmetro (revisão R-04). Com `REPEATABLE READ`, as três consultas veem o mesmo instante.

   Cada linha fica na nota da implantação. O efeito é o do T19: vende como hoje, o excesso vai para o rateio da U2 e a conferência fecha.

**(d) Desligar** (rollback do comportamento): ordem inversa, primeiro o worker e depois a API, em implantações só da flag. O ciclo seguinte volta ao de hoje, sem migração; as notas e os resultados gravados ficam como histórico (BR5.4). O saque continua disponível durante todo o processo.

**(e) Rollback de imagem.** As imagens anteriores funcionam com a `0026` (regra mais larga) e a `0027` (índice não usado). Nenhuma migração é revertida em produção.

## Runbook (NFR2.14)

`docs/runbooks/real-operations.md` ganha quatro seções, depois da "9. Índice da migração 0023":

| Seção | Conteúdo |
|---|---|
| 10. Posição própria indisponível | Os alarmes `RealOpsOwnHoldingsUnavailable` (falha sistêmica) e `RealOpsOwnHoldingsStuckBots` (bot preso, F3) e os gêmeos no CloudWatch. Para o bot preso: achar no log os `WARN` com `streak` ≥ 3 e o `botId`; a carteira inteira com defeito aparece como 3 bots da mesma pessoa. Depois de uma reinicialização do worker, o gauge recomeça do zero e o bot preso volta a aparecer em cerca de 25 min. Nesse estado o bot não vende pelo ciclo; a saída é pelas condicionais ou por "Vender tudo e sacar", que nunca leem a atribuição. Como achar o motivo e o bot no log (`event=REAL_OWN_HOLDINGS_UNAVAILABLE`). Abrir defeito para `READ_FAILED` persistente ou qualquer `BOT_NOT_OWNED`. Com `BUDGET_EXCEEDED` sistêmico: conferir `hikaricp_connections_pending`, a carga do banco e `ledger_real_epoch_fallbacks_total`. Se não resolver, desligar a flag do worker (passo d). |
| 11. Virada da flag de quantidade própria | Os passos (b) a (e) acima, com as três consultas só de leitura. |
| 12. Migração 0026 ou 0027 barrada | `55P03` e o passo de repetir; índice `INVALID` da `0027` e a repetição. |
| 13. Revisão de `STALE_SNAPSHOT` | A consulta semanal de NFR2.17 e o critério (> 1%, ou 3 `STALE_SNAPSHOT` seguidos de um bot); ver `observability-design.md`. |

Cada seção traz o caminho de escalonamento: o fundador é o único on-call; um defeito vira issue no repositório com o `correlationId` do ciclo.

## Aviso na tela: acessibilidade e estados (NFR5.1, NFR5.2, NFR6.1, NFR6.2)

O aviso é um `<p role="status">` em `apps/web/src/features/bots/BotList.tsx`, com o texto exato da BR6.1 como conteúdo (nome acessível = texto), ao lado do aviso do simulado que já existe e com os mesmos tokens de cor dele (contraste já conferido ≥ 4,5:1 no tema escuro; o par de cores e a razão ficam registrados no Build and Test). Sem ícone obrigatório, sem significado só por cor e sem rolagem lateral em 360 px (o parágrafo quebra linha). Ele aparece quando `realOwnQuantitySell === true` e o filtro é "Todos" ou "Real"; com o campo ausente (API anterior) ou `false`, a lista é a de hoje, sem erro. Testes: T15, T16 e T17 (Jest e Testing Library, escritos antes do código pela Q2) e o spec de ponta a ponta da lista em 360 px (`scrollWidth ≤ clientWidth`).

## Verificação (NFR7.1 a NFR7.6)

- **NFR7.1:** T1 a T20 entram num commit anterior ao código que os faz passar, ou no mesmo commit antes dele, e falham sem o código da U4. A Q2 vale também para T15 a T17.
- **NFR7.2:** Test Strategy `Standard`; cada classe nova com caminho feliz e pelo menos dois casos de erro ou de borda. Testes novos deste desenho: `OwnSellCapTest`, `RealBotHoldingsIsolationIT`, `RealBotHoldingsTenantIT`, `RealBotHoldingsMeasurementIT`, `RealOpenSellsPlanIT`, `Migration0026IT`, `Migration0026LockTimeoutIT`, `Migration0027IndexRestartIT`, `OwnQuantitySellDefaultIT`, `OwnQuantityExitsIT`, `OwnQuantityLogRedactionTest`, `OwnQuantityMetricsTest`, `RenewMarkOnSellIT`, `OwnQuantityArchitectureTest`, `RealOpsOwnHoldingsAlertRuleIT`, `CiPromtoolDigestTest`, `AlertRuleTestAnnotationsTest`, `OwnHoldingsStreaksTest`, `RealBotHoldingsOrderPathParityIT`.
- **NFR7.3:** JaCoCo da API ≥ 80% e Jest do `apps/web` ≥ 80%, sem exclusão nova.
- **NFR7.4:** os testes de risco do `team.md` que a U4 toca continuam verdes (isolamento com tarefa em segundo plano, separação REAL/PAPER, idempotência, pausa, REAL desligado por padrão, ledger sem edição, saque, uma transação por ordem).
- **NFR7.5:** `bash scripts/verify.sh` verde com a flag desligada; nenhum spec novo de ponta a ponta exigido.
- **NFR7.6:** T20 por Testcontainers dentro do `mvnw verify` (desenho em `observability-design.md`).

## Sources

- `nfr-design-questions.md`: Q1 a Q4 e o resumo confirmado [Q1] [Q2] [Q3] [Q4].
- `reviews/review-01.md` (R-02, R-04, R-05) e a decisão do fundador F3 de 2026-11-24.
- `construction/u4-own-quantity-cycle/nfr-requirements/reliability-requirements.md` (NFR2.1 a NFR2.9, NFR2.14, NFR5.1 a NFR6.2, NFR7.1 a NFR7.6) e `observability-requirements.md` (NFR2.11, NFR2.17); decisões do fundador F1 e F2.
- `construction/u4-own-quantity-cycle/functional-design/rules.md` (BR1.5, BR1.8, BR2.3, BR2.6, BR3.4, BR5.1 a BR5.5, BR6.1 a BR6.3, BR7.1, BR7.2) e `functional-spec.md` (F2, F3, F5, F6, E5, T1 a T20).
- `construction/u2-real-bot-pnl/nfr-design/reliability-design.md` (leitura que falha com a transação utilizável).
- `aidlc/spaces/default/memory/team.md` (Deployment: Flags, Rollback, expand-contract, fundador on-call; Testing Posture) e `project.md` (saque sempre disponível; ponto flutuante proibido).
- Código lido: `db/changelog/changes/0010-bots-decision.sql` (`decision.context jsonb`), `0020-real-bot-cycle.sql` (lista de 12 valores), `0023-real-entry-slot.sql` (padrão `CONCURRENTLY`), `0019-real-execution.sql`; `pnl/application/AttributedCostReader.java`; `shared/jdbc/ReadDeadline.java`; `bots/worker/CycleRunner.java`; `decision/infra/DecisionJson.java`; `docs/runbooks/real-operations.md` (seções 1 a 9).

## Assumptions & Open Questions

1. **Refinamento do NFR2.7.** O NFR2.7 pede que "cada changeset" tenha um rollback que recria a lista de hoje e valida. Uma validação não se desfaz, então o changeset 2 volta com `SELECT 1` e o changeset 1 carrega a lista de hoje com a validação. O efeito do rollback dos dois é o mesmo que o requisito descreve.
2. A coluna do contexto da decisão é `decision.context`, do tipo `jsonb` (migração `0010`), por isso a consulta usa `jsonb_exists` direto, sem conversão. A chave `ownQuantity` é a que o `DecisionJson` passa a gravar nas decisões REAL com a flag ligada (`logical-components.md`, A10).
3. [assumption] A dispensa do disjuntor vale para o volume do piloto (63 bots); com muito mais bots, a decisão volta ao NFR Design.
