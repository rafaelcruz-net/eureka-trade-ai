# Runbook — operações do REAL: liberação, divergência, kill switch, restauração e rollback

Intent `261113-trading-real-bot`, Unit U5 (`u5-real-operations`). Fontes: `functional-design/business-rules.md` (BR1 a BR7), `nfr-design/reliability-design.md`, `nfr-design/security-design.md`, `infrastructure-design/infrastructure-specification.md` e `infrastructure-design/monitoring-design.md` (alertas de `infra/observability/real-ops-alerts.yml`).

Quem executa: o fundador, com o papel ADMIN, pela entrada administrativa. Nenhum passo deste runbook pede chave, segredo, frase de recuperação ou transação assinada. As rotas abaixo ficam sob `/api/v1/admin/real` e toda mudança exige o cabeçalho `Idempotency-Key` (um UUID novo por ação; repetir a mesma chave com o mesmo corpo devolve a mesma resposta) e um motivo de 1 a 500 caracteres, que vai para a trilha de auditoria.

Regra de ouro: **o saque para a carteira principal registrada nunca é bloqueado** — nem pelo kill switch, nem por divergência (só limitado ao que a rede confirma), nem pela restauração, nem sem liberação. Nenhum passo deste runbook desliga o saque.

## 1. Liberação e portões

**Como funciona.** `POST /users/{userId}/release` com `{ "network": "<LOCALNET|DEVNET|MAINNET>", "reason": "..." }` registra a liberação em `QUARANTINE`. Ela só vale depois de 24 horas e com todos os portões verdes. O trabalho `gates` do worker (a cada 5 min) confere os portões das liberações que passaram da quarentena e das que estão `BLOCKED`:

| Motivo de bloqueio | O que significa | O que fazer |
|---|---|---|
| `CI_RED` | o workflow `ci.yml` do commit da imagem (`BUILD_SHA`) no `main` não terminou verde | corrigir a CI; o portão volta sozinho na próxima rodada |
| `CI_UNREACHABLE` | a leitura do GitHub falhou (rede, token vencido, 5 s de prazo) | ver a seção 5 (token); conferir `MEMETRADE_GITHUB_REPOSITORY` |
| `DIVERGENCE_OPEN` | existe divergência aberta | resolver a divergência (seção 2) |
| `KILL_SWITCH_ON` | o kill switch REAL está ligado | desligar quando o incidente acabar (seção 3) |
| `NETWORK_MISMATCH` | a liberação é de outra rede que não a do ambiente | revogar e registrar de novo com a rede certa |

**Regras de entrada (recusas com código estável):** `NO_BOT_WALLET` (a pessoa não tem carteira do bot ativa), `TERMS_V3_MISSING` (falta o aceite dos termos vigentes), `RELEASE_EXISTS` (já existe liberação não revogada nessa rede), `FOUNDER_FIRST` (na MAINNET, a carteira do fundador — `MEMETRADE_REAL_FOUNDER_USER_ID` — é liberada antes de qualquer convidado), `PILOT_FULL` (o piloto da MAINNET já tem 20 convidados).

**Revogar.** `DELETE /users/{userId}/release` com `{ "reason": "..." }`. Vale na hora, sob o lock da pessoa: as ordens não enviadas dela são canceladas (`REAL_DISABLED`); as enviadas seguem até o fim; o saque continua.

**Alertas.** `RealOpsReleaseBlocked` (liberação `BLOCKED` há mais de 1 h) e `RealOpsJobStalledGates`.

## 2. Divergência e compensação

**O que significa.** A reconciliação (trabalho `reconciliation`, a cada 15 min por carteira, e depois de cada ordem confirmada) compara, no slot finalizado, o saldo de SOL e de cada token da carteira do bot na rede com o ledger REAL. Se não bate, abre uma divergência, pausa a carteira com `DIVERGENCE` e limita o saque ao que a rede confirma. Ela fica `DEFERRED` enquanto houver ordem `SENT` ou `AMBIGUOUS` ou depósito ainda não lido, e `UNAVAILABLE` quando o RPC não responde.

**Passos.**
1. `GET /divergences` mostra a divergência com a diferença medida (rede menos ledger) em lamports e em unidades base de cada mint. `GET /users/{userId}` mostra a pessoa, o saldo e as 20 ordens mais recentes.
2. Descubra a causa antes de compensar: depósito de token sem ordem (airdrop, spam), taxa não lançada, defeito. Consulte a carteira do bot (endereço público, mostrado por extenso na visão) num explorador.
3. Se a diferença é legítima, compense: `POST /divergences/{divergenceId}/compensation` com `{ "items": [{ "asset": "SOL" ou "<mint>", "amount": "<diferença com sinal>" }], "reason": "..." }`. Cada item tem de ser **exatamente** a diferença medida do ativo; outro valor é `422 COMPENSATION_MISMATCH`, e uma divergência que já não está aberta é `409 DIVERGENCE_NOT_OPEN`. A compensação é um lançamento novo no ledger (o ledger é só de inserção) e deixa a divergência `COMPENSATED`.
4. O worker roda a reconciliação `AFTER_COMPENSATION`. Se vier `CLEAN`, a divergência fica `RESOLVED` e a pausa `DIVERGENCE` sai. Se continuar diferente, a divergência volta a valer com a nova medida: repita a análise.
5. Se a diferença **não** é legítima (indício de ataque ou defeito), não compense: ligue o kill switch (seção 3) e abra incidente.

**Alertas.** `RealOpsDivergenceOpen` (crítico), `RealOpsReconciliationDeferred` (carteira sem medida há mais de 2 h), `RealOpsJobStalledReconciliation`.

## 3. Kill switch REAL

**Ligar.** `PUT /kill-switch` com `{ "reason": "..." }`. Na hora: nenhuma ordem nova REAL de ninguém é aceita, as ordens não enviadas de todos são canceladas (`KILL_SWITCH`) logo depois do registro e a cada minuto enquanto ficar ligado; as enviadas seguem; posições ficam como estão; o saque continua. A visão de cada pessoa mostra a pausa `KILL_SWITCH`.

**Desligar.** `DELETE /kill-switch` com `{ "reason": "..." }`. Nenhum bot é religado; as outras pausas continuam. Ligar quando já está ligado (ou desligar quando já está desligado) não muda nada (`changed: false`).

**Alertas.** `RealOpsKillSwitchOn` (ligado há 30 min, para não esquecer) e `RealOpsJobStalledKillSwitchCancel`.

## 4. Restauração do banco

**O que significa.** Depois de restaurar um backup, o banco pode não conhecer transações que já estão na rede. Ao subir, o worker abre a pausa global `RESTORE_CHECK_PENDING` e confere, para cada carteira, as assinaturas da rede mais novas que o cursor de depósitos. Se todas são conhecidas, a pausa sai sozinha. Se alguma é desconhecida, a pausa vira `RESTORE` (todos os bots REAL parados; o saque continua) e os depósitos voltam a ser lidos.

**Passos.**
1. Acompanhe `real_restore_pause_active{phase}` e o alerta `RealOpsRestorePause`.
2. Com `RESTORE`, espere a reconciliação `AFTER_RESTORE` de todas as carteiras. Divergências abertas seguem a seção 2.
3. Quando todas as carteiras vierem `CLEAN` depois da restauração, confirme: `POST /restore/confirmation` com `{ "reason": "..." }`. Se alguma ainda não estiver limpa, a resposta é `409 RESTORE_NOT_CLEAN`; sem restauração aberta, `404`.

**Alertas.** `RealOpsRestorePause` e `RealOpsJobStalledRestoreCheck` (só enquanto a conferência está pendente).

## 5. Troca do token do GitHub

O portão de CI lê o workflow `ci.yml` do commit da imagem no `main` com um token fine-grained **só de leitura de Actions** deste repositório, guardado no segredo `github-actions-read-token` (Secrets Manager) e entregue só ao worker como `MEMETRADE_GITHUB_ACTIONS_TOKEN`. Troca a cada 90 dias, ou na hora em caso de vazamento:

1. No GitHub, crie um token novo com "Actions: read" só neste repositório e validade de 90 dias.
2. Grave o valor novo no segredo `github-actions-read-token` (à mão; o Terraform nunca escreve o valor).
3. Force uma nova implantação do worker e confira, no log, que o portão leu a CI (nenhum `CI_UNREACHABLE` novo).
4. Revogue o token antigo no GitHub.

Em vazamento: revogue primeiro, depois troque. O token nunca aparece em log (o `toString` do adaptador não o mostra).

## 6. Duplicatas na migração 0021

A migração 0021 começa por uma pré-condição: no máximo uma liberação não revogada por pessoa e rede. Se houver duplicata, a migração inteira para **antes de qualquer mudança** (`SQL Precondition failed`) e a API não sobe com a imagem nova.

1. Com a imagem anterior ainda no ar, liste as duplicatas: `select user_id, network, count(*) from real_release where revoked_at is null group by user_id, network having count(*) > 1`.
2. Revogue a mais antiga de cada par pela rota do administrador da versão anterior (ou, na falta dela, registrando `revoked_at = now()` e o motivo no incidente).
3. Implante de novo; a pré-condição passa.

## 7. Rollback

A 0021 é só de expansão: as imagens da U4 funcionam sobre o esquema novo. Por isso o rollback normal é **das imagens**, não do banco.

1. Primeiro desligue o REAL: `memetrade.real.enabled=false` e `txsigner.real.enabled=false`, ou ligue o kill switch REAL. O saque continua disponível durante todo o rollback.
2. Volte as imagens da API, do worker e do TxSigner para a tag anterior (marcadas pelo SHA do commit, `BUILD_SHA`).
3. **Só no ambiente local e de teste:** se for indispensável voltar o banco, rode o rollback dos changesets da 0021 na ordem inversa. Na devnet e na mainnet, migração nunca volta (`operation/deployment-pipeline/rollback-runbook.md`, passo 5): volte só as imagens. **O CHECK do saque é a exceção:** se algum saque já foi redigido por uma anonimização (`withdrawal.redacted_at` preenchido), o rollback de `0021-withdrawal-redaction` para com a mensagem `withdrawal rows were redacted` — mantenha o CHECK da 0021 (as imagens anteriores funcionam com ele) e volte só as imagens.
4. Confira o smoke e, com o REAL de volta, a reconciliação `CLEAN` das carteiras.

## 8. Divergência do P&L REAL

Intent `261121-paper-real-unico`, Unit U2 (`u2-real-bot-pnl`), NFR2.14. Fonte: `infrastructure-design/monitoring-design.md`.

**O que significa.** Uma leitura do P&L REAL de uma pessoa não bateu com o ledger numa das 8 verificações (`REALIZED`, `FEES`, `UNREALIZED`, `COST_CONSERVATION`, `QUANTITY_CONSERVATION`, `EVENT_SUMS`, `UNOWNED_WITHOUT_REASON`, `UNKNOWN_BOT`). O bloco REAL saiu `UNAVAILABLE` com o código `RECONCILIATION_MISMATCH`, sem número parcial, e o `GET /api/v1/real/pnl` respondeu o erro técnico (503). Ordens, barreira e saque não são afetados: a divergência afeta só a leitura do P&L.

**Alertas.** `RealOpsPnlReconciliationMismatch` (Prometheus) e `real_ops_pnl_reconciliation_mismatch` (CloudWatch), críticos. O contador `pnl_real_reconciliation_mismatches_total` sobe em toda leitura com divergência; o evento de auditoria fica limitado a 1 por hora por pessoa e impressão digital. Sem leitura por 240 s o alarme volta a `OK` e volta a `ALARM` na leitura seguinte.

1. Ache o evento de auditoria `REAL_PNL_RECONCILIATION_MISMATCH` pelo `correlationId` do bloco ou pela `fingerprint` (em `details`). Leia as verificações que falharam (`checks`) e as diferenças em lamports (`diff.<verificação>`), que ficam só no evento, nunca no log nem na resposta.
2. No CloudWatch Logs Insights, busque `event=REAL_PNL_RECONCILIATION_MISMATCH` com o mesmo `correlationId`. `auditWritten=failed` indica que o evento não foi gravado: veja `event=REAL_PNL_MISMATCH_AUDIT_FAILED` (a `category` diz se foi `CONNECTION_TIMEOUT`, `DEADLINE`, `REJECTED` ou `ERROR`) e `pnl_real_reconciliation_audit_failures_total` em `/actuator/prometheus`.
3. Compare com a reconciliação da rede (seção 2): divergência aberta da carteira aponta para o ledger; reconciliação `CLEAN` aponta para defeito do cálculo. `UNKNOWN_BOT` é uma ordem da pessoa com um bot que não é bot REAL dela: confira a ordem pelo id, nunca o bot alheio.
4. Abra o defeito com um teste de regressão que reproduz a sequência (`team.md`, Defeitos). **Não compense o ledger por causa desta divergência:** a compensação é só da seção 2.

**Escalonamento.** O fundador é o único on-call (`team.md`, Deployment). **Seção relacionada:** seção 9.

## 9. Índice da migração 0023

Intent `261121-paper-real-unico`, Unit U2. Fonte: `infrastructure-design/cicd-pipeline.md`, "Falha no meio da criação do índice". A migração `0023` cria `real_ledger_entry_wallet_created_idx` com `CREATE INDEX CONCURRENTLY` (`runInTransaction:false`), depois de um `DROP INDEX CONCURRENTLY IF EXISTS`.

1. O `db-migrate` termina com código diferente de zero; a atualização rolling não começa e a imagem antiga continua no ar.
2. Confira no banco, com o login dono (`DB_OWNER_USER`): `select to_regclass('real_ledger_entry_wallet_created_idx') is not null as exists, (select indisvalid from pg_index where indexrelid = to_regclass('real_ledger_entry_wallet_created_idx')) as valid;`. `to_regclass` devolve `null` sem erro quando o índice não existe. `exists = true` e `valid = false` é o resto de uma criação interrompida; `exists = false` quer dizer que a falha veio antes do índice.
3. Rode a tarefa `db-migrate` de novo. O changeset não foi marcado como aplicado; o `DROP INDEX CONCURRENTLY IF EXISTS` apaga o resto e o índice é criado de novo.
4. Se a falha se repetir, leia a causa no grupo de log do `db-migrate` (por exemplo, trava, disco ou conexão) antes de tentar outra vez. Nunca crie o índice à mão fora do Liquibase.
5. Se a trava do changelog ficar presa depois de o processo morrer (`select locked, lockgranted, lockedby from databasechangeloglock;` mostra `locked = true` e nenhuma tarefa `db-migrate` está rodando no ECS), libere só ela, com o login dono: `update databasechangeloglock set locked = false, lockgranted = null, lockedby = null where id = 1;`. Depois repita o passo 3. O comando é o mesmo que o `releaseLocks` do Liquibase executa; a imagem não traz a linha de comando do Liquibase.

## 10. Posição própria indisponível

Intent `261121-paper-real-unico`, Unit U4 (`u4-own-quantity-cycle`), NFR2.12, NFR2.14 e NFR2.18. Fontes: `nfr-design/observability-design.md` e `nfr-design/reliability-design.md`.

**O que significa.** Com a flag `memetrade.real.own-quantity-sell` ligada, o ciclo de cada bot REAL lê a posição própria do bot (o RealBotHoldings) antes da transação do ciclo, com prazo de 250 ms. Quando a leitura falha, o bot **não vende pelo ciclo** naquele slot (falha fechada) e tenta de novo no slot seguinte (5 min). Compras de outros tokens seguem como sempre. O dinheiro continua saindo pelas condicionais, por "Vender tudo e sacar" e pelo saque, que nunca leem a posição própria.

**Alertas.**

- `RealOpsOwnHoldingsUnavailable` (Prometheus) e `real_ops_own_holdings_unavailable` (CloudWatch), `high`: mais de 5% das leituras de 10 minutos indisponíveis por `BUDGET_EXCEEDED` ou `READ_FAILED`, por 15 minutos (no CloudWatch, dois períodos de 600 s com `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`). É a falha sistêmica.
- `RealOpsOwnHoldingsStuckBots` (Prometheus) e `real_ops_own_holdings_stuck_bots` (CloudWatch, `Minimum`, 2 períodos de 300 s), `high`: pelo menos um bot com 3 ou mais leituras indisponíveis seguidas, por 10 minutos. É o bot preso, que a razão não vê (1 bot em 63 é 1,6% das leituras; a carteira inteira, 3 bots, é 4,8%).

1. Ache o bot e o motivo no CloudWatch Logs Insights: `event=REAL_OWN_HOLDINGS_UNAVAILABLE`, com os campos `botId`, `reason`, `elapsedMs` e `streak`. Para o bot preso, procure os `WARN` com `streak` 3 ou mais; três bots da mesma pessoa com `streak` alto indicam a carteira inteira com defeito. O `correlationId` é o `cycleId` do ciclo, o mesmo de `bot_cycle` e da decisão gravada.
2. Uma linha com `precheck=NOT_READ` é a corrida entre a conferência prévia e a trava: o bot rodou sem a leitura e não vendeu. Ela não conta nos contadores; só é problema se aparecer em série para o mesmo bot.
3. `BUDGET_EXCEEDED` sistêmico (muitos bots ao mesmo tempo): confira `hikaricp_connections_pending{pool="memetrade-worker-pool"}` (fila de conexões do worker), a carga do RDS (CPU, I/O e conexões) e `ledger_real_epoch_fallbacks_total` (leituras do ledger que caíram para o histórico inteiro). O prazo inclui a espera da conexão.
4. `READ_FAILED` persistente: abra um defeito com o `correlationId` do ciclo e o `botId`.
5. Qualquer `BOT_NOT_OWNED` (log `ERROR`) é defeito: o despachante só entrega bots ativos do dono. Abra o defeito na hora; não é ataque vindo de fora.
6. Depois de uma reinicialização do worker, o gauge `real_own_holdings_stuck_bots` volta a 0 e um bot preso volta a aparecer em cerca de 25 minutos (mais 3 leituras indisponíveis e os 10 minutos do alarme). Nesse intervalo, valem a razão e o log por bot.
7. Se a causa não for resolvida e muitos bots seguirem sem vender pelo ciclo, desligue a flag **só do worker** (seção 11, passo "Desligar"). O ciclo seguinte volta ao comportamento de antes da U4, sem migração. O saque continua disponível durante todo o processo.

**Escalonamento.** O fundador é o único on-call (`team.md`, Deployment). Defeito vira issue no repositório com o `correlationId` do ciclo. **Seções relacionadas:** 11 e 13.

## 11. Virada da flag de quantidade própria

Intent `261121-paper-real-unico`, Unit U4, BR5.2, BR5.4, BR5.5 e NFR2.8 (decisão do fundador F4). Fonte: `nfr-design/reliability-design.md`, "Flag: ligar, conferir e desligar".

**Pré-condições (todas).** A Performance Validation do intent `261113-trading-real-bot` aprovada; a medida do NFR1.5 dentro da meta (mediana do p99,9 de 3 execuções do `RealBotHoldingsMeasurementIT` até 250 ms, em `docs/perf/`) ou a decisão do fundador sobre ela; a aprovação do fundador; o código da U4 já em serviço com a flag desligada; e, na mainnet, a prova de que o alarme da razão funcionou na devnet. O fundador acompanha a janela de observação e os 30 minutos seguintes (`team.md`).

**Onde a flag muda (F4).** As variáveis `real_own_quantity_sell_api` e `real_own_quantity_sell_worker` do `tfvars` de cada conta, fora do repositório (o padrão `false` fica versionado em `infra/terraform/main.tf`). A validação do Terraform recusa o worker ligado com a API desligada. Cada passo é uma implantação **só da flag**, nunca junto com código: primeiro na devnet, depois na mainnet com o mesmo SHA. Enquanto o `deploy.yml` não existir, o caminho é o do fundador: `terraform plan` e `terraform apply` com MFA da máquina dele, conferindo no `plan` que só a variável `MEMETRADE_REAL_OWN_QUANTITY_SELL` do contêiner certo muda; o registro é o `plan`, o `apply`, o CloudTrail e a nota da implantação.

**Ligar.**

1. Implantação 1: só `real_own_quantity_sell_api = true`. O aviso aparece em `/bots`; o ciclo ainda é o de antes.
2. Conferência antes da implantação 2, numa sessão só de leitura com um instantâneo único para as consultas:

   ```sql
   BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
   SELECT now() AS t1;   -- anotar
   SELECT count(*) FROM real_decision_request WHERE side = 'SELL' AND state = 'PENDING';
   SELECT count(*) FROM real_order WHERE side = 'SELL' AND bot_id IS NOT NULL
      AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS');
   COMMIT;
   ```

   As duas contagens precisam dar 0 (as decisões adiadas contam). Se não, espere e confira de novo; uma ordem `AMBIGUOUS` segue o runbook do intent `261113-trading-real-bot` até se resolver.
3. Implantação 2: `real_own_quantity_sell_worker = true` (a API continua `true`).
4. Depois que a última tarefa antiga do worker sai da troca rolling, repita as duas contagens e liste as decisões SELL gravadas por tarefa antiga entre `t1` e agora (a nota `ownQuantity` só é gravada pela imagem com a flag ligada):

   ```sql
   BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
   -- as duas contagens do passo 2, de novo, e:
   SELECT r.decision_request_id, r.bot_id, r.created_at
     FROM real_decision_request r JOIN decision d ON d.decision_id = r.decision_id
    WHERE r.side = 'SELL' AND r.created_at >= :t1
      AND NOT jsonb_exists(d.context, 'ownQuantity');
   COMMIT;
   ```

   Use `jsonb_exists` e não o operador `?`, que um cliente JDBC trata como marcador de parâmetro. Cada linha vai para a nota da implantação: essa venda sai como antes da U4, e o excesso vai para o rateio "vendido por outro bot"; a conferência com o ledger fecha (T19).
5. Na primeira hora, confira `real_own_holdings_reads_total` subindo e a razão da seção 13.

**Desligar** (ordem inversa, implantações só da flag): primeiro `real_own_quantity_sell_worker = false`, depois `real_own_quantity_sell_api = false`. O ciclo seguinte volta ao comportamento de antes, sem migração; as notas e os resultados já gravados ficam como histórico. O saque continua disponível durante todo o processo.

**Rollback de imagem.** As imagens anteriores funcionam com a `0026` (regra mais larga) e a `0027` (índice não usado); nenhuma migração é revertida em produção. Uma imagem anterior à U4 ignora a variável. Antes de voltar para a imagem da U4 depois de um rollback, deixe `real_own_quantity_sell_worker = false` no `tfvars` e refaça a virada desde o passo 1.

**Escalonamento.** O fundador é o único on-call. **Seções relacionadas:** 7 e 10.

## 12. Migração 0026 ou 0027 barrada

Intent `261121-paper-real-unico`, Unit U4, NFR2.7. Fonte: `nfr-design/reliability-design.md`, "Migração 0026" e "Migração 0027".

**0026 (regra de `bot_cycle.outcome`).** Os dois changesets usam `SET LOCAL lock_timeout = '5s'`.

1. O `db-migrate` termina com código diferente de zero e o log mostra `55P03` (`canceling statement due to lock timeout`). A transação do changeset foi desfeita: a regra antiga continua inteira, e o Liquibase não gravou a linha em `DATABASECHANGELOG`. A atualização rolling não começou; as imagens antigas continuam servindo.
2. Ache a transação que segurava `bot_cycle`: `select pid, state, xact_start, query from pg_stat_activity where datname = current_database() and xact_start < now() - interval '5 seconds' order by xact_start;`. **Nunca mate à força a transação do ciclo nem a do consumidor da fila**; espere que ela termine (elas duram segundos).
3. Dispare de novo a mesma implantação com o mesmo SHA. Se o changeset 1 já entrou e só o 2 (`VALIDATE`) falhou, a regra fica `NOT VALID` mas já vale para toda linha nova; a repetição roda só o changeset 2.

**0027 (índice `real_decision_request_bot_pending_sell_idx`).** Igual à seção 9: um `CREATE INDEX CONCURRENTLY` interrompido deixa o índice `INVALID` e nenhuma linha do changeset.

1. Confira com o login dono: `select to_regclass('real_decision_request_bot_pending_sell_idx') is not null as exists, (select indisvalid from pg_index where indexrelid = to_regclass('real_decision_request_bot_pending_sell_idx')) as valid;`.
2. Rode a tarefa `db-migrate` de novo: o `DROP INDEX CONCURRENTLY IF EXISTS` apaga o resto e o índice é criado de novo. O `CONCURRENTLY` espera as transações abertas terminarem (as do ciclo e da fila duram segundos).
3. Trava do changelog presa: o passo 5 da seção 9.

**Escalonamento.** O fundador é o único on-call. **Seção relacionada:** 9.

## 13. Revisão de `STALE_SNAPSHOT`

Intent `261121-paper-real-unico`, Unit U4, NFR2.17. Fonte: `nfr-design/observability-design.md`, "`STALE_SNAPSHOT` sem alarme".

**O que significa.** A marca do bot mudou entre a leitura da posição própria e a trava do ciclo (outra decisão do bot foi gravada no meio, ou o bot foi editado). O ciclo não vende naquele slot e termina `REAL_OWN_QUANTITY_UNAVAILABLE` com `STALE_SNAPSHOT`; isso evita vender duas vezes a mesma quantidade. Não tem alarme.

1. Toda semana, e depois de cada implantação que liga a flag, rode no Prometheus:

   ```text
   sum(increase(real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}[24h]))
     / sum(increase(real_own_holdings_reads_total[24h]))
   ```

2. Acima de 1%, ou com 3 `STALE_SNAPSHOT` seguidos do mesmo bot (`event=REAL_OWN_SELL_STALE`, campos `botId`, `markBefore` e `markUnderLock`), abra um defeito por bot com o `correlationId` dos ciclos.

**Escalonamento.** O fundador é o único on-call. **Seção relacionada:** 10.
