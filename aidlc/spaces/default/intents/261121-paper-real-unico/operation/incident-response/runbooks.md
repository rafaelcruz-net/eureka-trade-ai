# Biblioteca de runbooks — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Incident Response. Decisões: `incident-response-questions.md`, Q1 a Q4, todas na opção A, com o resumo confirmado. Data: 2026-11-25. Commit de referência: `09de0a8` (`main`).

> **Nada está implantado.** Não existe conta AWS nem ambiente na nuvem; o único ambiente que roda é o local (Docker Compose) e a CI. Os nomes de alarmes, métricas, consultas e grupos de log vêm do Terraform validado offline e dos artefatos do Observability Setup deste intent. Cada comando AWS só pode ser conferido depois do primeiro `apply` da devnet. **Este estágio não muda código, IaC nem workflow.**

## Base: a biblioteca do 261113, por referência

Vale **inteira** a biblioteca `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/runbooks.md`:

| Parte do 261113 | Situação aqui |
|---|---|
| "Regras que valem em todos os runbooks" (1 a 8) | sem mudança; este intent soma as regras 9 a 11 abaixo |
| Seção 0, preparação do terminal (`alarms`, `alarm_history`, `metric`, `logsq`, `services`, `service_events`) | sem mudança; os runbooks abaixo usam essas funções |
| Procedimentos comuns C1 (kill switch REAL), C2 (REAL pelas flags), C3 (silenciar alarmes), C4 (registrar o incidente), C5 (religar o REAL) | sem mudança |
| RB-01 a RB-15 | sem mudança. Este intent continua a numeração: **RB-16 a RB-21** |
| Tabela de cobertura alarme → runbook (64 alarmes) | sem mudança; os 3 alarmes novos e os 2 propostos estão na seção "Cobertura" abaixo |
| Lacunas IR-G1 a IR-G8 | sem mudança; a **IR-G3** pesa em quatro dos seis runbooks deste intent. Lacunas novas: IR-G9 a IR-G11 |

Cada runbook abaixo aponta para a seção do runbook do repositório (`docs/runbooks/real-operations.md`) ou do `rollback-runbook.md` deste intent quando ela já existe, sem repetir o texto. Aqui fica o que o runbook do repositório não diz: gatilho, prioridade, diagnóstico na nuvem com o limite da IR-G3, verificação, rollback e escalonamento.

## Regras deste intent, somadas às regras 1 a 8 do 261113

9. **O saque nunca é desligado.** Nenhum passo destes runbooks bloqueia o saque para a carteira principal registrada, "Vender tudo e sacar" ou as condicionais (`project.md`, Mandated). Isso vale também durante o desligamento da flag da U4 e durante o rollback da imagem.
10. **PAPER e REAL nunca são somados.** Nenhum diagnóstico, nota de incidente, mensagem ou painel soma os dois modos num total geral (`project.md`, Forbidden). Cada valor é escrito na unidade do próprio modo: PAPER em US$, REAL em SOL ou lamports.
11. **A flag da U4 desliga primeiro no worker, depois na API** (`rollback-runbook.md` deste intent, R2a e R2b). A ordem inversa é recusada pela validação do Terraform. Desligar a flag é uma implantação só da flag, com `plan`, `apply` e MFA do fundador, nunca junto com código e nunca automática (Q3).

**Diagnóstico na nuvem (IR-G3).** Não existe caminho de leitura SQL do banco para o fundador na nuvem: o papel de implantação nega `ecs:ExecuteCommand` e `ssm:StartSession`, e nenhuma família avulsa roda SQL de leitura. Por isso, na nuvem, os runbooks diagnosticam pela administração (`/api/v1/admin/real/*`), pelas métricas de `Eureka/App`, pelos logs e pela trilha de auditoria lida pela administração. As consultas SQL de `docs/runbooks/real-operations.md` (seções 9, 11 e 12) só valem no ambiente local e na CI. A proposta para fechar a IR-G3 é a opção A do `environment-provisioning/security-posture-review.md` (2.1), decisão do fundador antes da virada da flag da U4 [Q3].

**Consultas salvas deste intent.** As seis consultas de `observability-setup/log-queries.md` (seção 3) **ainda não estão** em `infra/terraform/modules/observability/log-queries.tf`. Até o texto da seção 4 daquele arquivo ser aplicado, a função `logsq` do 261113 não as encontra: cole o texto da consulta no console do Logs Insights, no grupo `/ecs/eureka-real/api-worker`.

---

## RB-16 — Divergência na reconciliação do P&L REAL (U2)

| Campo | Conteúdo |
|---|---|
| Gatilho | Alarme `eureka-real-real-ops-pnl-reconciliation-mismatch` (crítico; `pnl_real_reconciliation_mismatches_total` `> 0` em 240 s). Gêmeo local: `RealOpsPnlReconciliationMismatch`. Sintoma: o bloco REAL da visão por modo aparece em "Erro" com o código `RECONCILIATION_MISMATCH`, e `GET /api/v1/real/pnl` responde 503 |
| Severidade | **P1** ao detectar [Q1]. **Rebaixa para P2** quando a reconciliação da rede da carteira (`real-operations.md`, seção 2) estiver `CLEAN`: aí é defeito do cálculo do P&L, e o dinheiro está seguro. **Continua P1** se a reconciliação da rede também divergir; nesse caso o RB-02 do 261113 passa a mandar. O rebaixamento é anotado na linha do tempo |
| Runbook existente | `docs/runbooks/real-operations.md`, **seção 8** "Divergência do P&L REAL" (passos 1 a 4) e seção 2 (reconciliação da rede) |

**O que já aconteceu sozinho.** O bloco REAL saiu `UNAVAILABLE`, sem número parcial; o ledger continua sendo a verdade. O bloco PAPER continua aparecendo. Ordens, barreira e saque não leem o P&L e seguem normais.

**Primeiros 5 minutos.**
1. `alarms`. Conferir se `real-ops-divergence-open` também está em ALARM: se estiver, é divergência da carteira, e vale o RB-02 do 261113 (kill switch C1 se houver saída de fundos sem pedido).
2. C4: abrir o registro como P1.

**Diagnóstico.**
```bash
metric pnl_real_reconciliation_mismatches_total Sum
alarm_history real-ops-pnl-reconciliation-mismatch
alarm_history real-ops-divergence-open
```
- Logs Insights: consulta `eureka-real/pnl-real-divergencias` (`log-queries.md`, seção 3), últimas 6 h. Ela mostra `checks`, `writer`, `path`, `outcome`, `reason` e `correlationId`, e nunca a mensagem inteira. `REAL_PNL_MISMATCH_AUDIT_FAILED` indica que o evento de auditoria não foi gravado.
- Administração → "Divergências REAL" e a pessoa afetada: a reconciliação da rede está `CLEAN` ou com divergência aberta?
- As diferenças em lamports por verificação ficam só no evento de auditoria `REAL_PNL_RECONCILIATION_MISMATCH` (`real-operations.md`, seção 8, passo 1), lido pela trilha de auditoria. **Limite da IR-G3:** na nuvem, não há leitura SQL do evento; se a administração não mostrar o `details` do evento, a análise das diferenças é feita reproduzindo a sequência no ambiente local, a partir do `correlationId`.
- `UNKNOWN_BOT`: confira a ordem pelo id, nunca o bot de outra pessoa (`real-operations.md`, seção 8, passo 3).

**Mitigação.**
- Reconciliação da rede `CLEAN` (defeito do cálculo): nada a conter no dinheiro. Abrir o defeito com teste de regressão (`real-operations.md`, seção 8, passo 4). Se a divergência começou logo depois de uma implantação, voltar a imagem (`rollback-runbook.md`, R3 a R7).
- Reconciliação da rede com divergência: RB-02 do 261113.
- **Nunca compensar o ledger por causa desta divergência.** A compensação é só da seção 2, com causa conhecida.

**Verificação.** `pnl_real_reconciliation_mismatches_total` sem crescimento por 30 min com leituras acontecendo; o alarme volta a OK; o bloco REAL volta a mostrar valores para a pessoa afetada (o fundador confere pela tela, com a carteira dele); "Divergências REAL" vazia.

**Rollback.** Só da imagem de `api-worker` (e de `web`, se trocada junto), pelo `rollback-runbook.md` deste intent, R3 a R7. As migrações `0023` e `0024` ficam. Nenhum dado é desfeito: o P&L é calculado a partir do ledger, que é só de inserção.

**O que NÃO fazer.** Nunca editar o ledger, `real_entry_slot` ou `real_position_epoch` (os gatilhos recusam). Nunca compensar para "fazer bater". Nunca mostrar um número parcial no lugar do bloco. Nunca colar no registro as diferenças por pessoa ou o endereço da carteira.

**Escalonamento.** Fundador, único on-call. P1 segue o escalonamento por tempo da `escalation-matrix.md` (seção 4). Revisão pós-incidente obrigatória (`incident-plan.md`, seção 5), também quando rebaixado para P2.

---

## RB-17 — Posição própria indisponível ou bot preso (U4)

| Campo | Conteúdo |
|---|---|
| Gatilho | `eureka-real-real-ops-own-holdings-unavailable` (alta; razão `BUDGET_EXCEEDED + READ_FAILED` sobre as leituras `> 5%` em 2 × 600 s) e `eureka-real-real-ops-own-holdings-stuck-bots` (alta; pelo menos um bot com 3+ leituras indisponíveis seguidas por 10 min). Gêmeos locais: `RealOpsOwnHoldingsUnavailable`, `RealOpsOwnHoldingsStuckBots` |
| Severidade | **P2** [Q1]. O bot não vende pelo ciclo (falha fechada), mas o dinheiro continua saindo pelas condicionais, por "Vender tudo e sacar" e pelo saque. Vira **P1** se o saque ou "Vender tudo e sacar" também falharem (RB-08 do 261113) ou se aparecer venda pela quantidade de outro bot (`rollback-runbook.md`, seção 6) |
| Runbook existente | `docs/runbooks/real-operations.md`, **seção 10** "Posição própria indisponível" (passos 1 a 7), **seção 11** "Desligar" e **seção 13** (`STALE_SNAPSHOT`); `rollback-runbook.md` deste intent, **R2a e R2b** |

**Com a flag desligada isso não acontece.** Hoje `MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` em todo ambiente (condição 1 do `cd-config.md`, seção 6), e a virada depende da decisão F7 (`test-results.md`, seção 10). Sem leitura, a razão vale 0 e o gauge fica em 0. **Um destes alarmes em ALARM com a flag supostamente desligada é anomalia por si:** primeiro passo é conferir a flag.

**Primeiros 5 minutos.**
1. Conferir a flag na revisão em serviço (comando da condição 1 do `cd-config.md`, seção 6):
   ```bash
   aws ecs describe-task-definition --task-definition "$(aws ecs describe-services --cluster "$CLUSTER" --services api-worker \
     --query 'services[0].taskDefinition' --output text)" \
     --query "taskDefinition.containerDefinitions[?name=='api' || name=='worker'].[name, environment[?name=='MEMETRADE_REAL_OWN_QUANTITY_SELL'].value | [0]]" \
     --output text
   ```
   Esperado hoje: `api false` e `worker false`. Outro valor sem uma virada registrada: tratar como implantação não autorizada (RB-10 do 261113, P1).
2. Conferir que o saque e "Vender tudo e sacar" funcionam (RB-08 do 261113 se não).
3. C4.

**Diagnóstico.**
```bash
metric real_own_holdings_reads_total Sum
metric real_own_holdings_unavailable_total Sum Name=reason,Value=BUDGET_EXCEEDED
metric real_own_holdings_unavailable_total Sum Name=reason,Value=READ_FAILED
metric real_own_holdings_unavailable_total Sum Name=reason,Value=BOT_NOT_OWNED
metric real_own_holdings_stuck_bots Maximum
```
- Logs Insights: `eureka-real/real-posicao-propria-indisponivel` (motivo a cada 10 min) e `eureka-real/real-posicao-propria-bots-presos` (`botId` com `streak` 3+). O `correlationId` é o `cycleId`.
- Painel "Visão REAL", linha "P&L por modo e quantidade própria", widgets 4 a 6 (`dashboards.md`, seção 2).
- `BUDGET_EXCEEDED` sistêmico: painel "Serviços", gráfico "RDS: conexões" e CPU do RDS `app` (D-INF2). **Limite da nuvem:** `hikaricp_connections_pending` e `ledger_real_epoch_fallbacks_total` ficam só no Prometheus local (`real-operations.md`, seção 10, passo 3); na nuvem, o sinal é o RDS e o log.
- `BOT_NOT_OWNED` (log `ERROR`) é sempre defeito, nunca ataque externo (`real-operations.md`, seção 10, passo 5).
- **Limite da IR-G3:** as conferências SQL da seção 11 (`real_decision_request`, `real_order`, `decision`) não rodam na nuvem.

**Mitigação.**
1. Causa transitória conhecida (por exemplo, carga momentânea no RDS): acompanhar 20 min; os alarmes voltam a OK sozinhos.
2. Causa não resolvida e muitos bots sem vender pelo ciclo: **desligar a flag, primeiro o worker** (`rollback-runbook.md`, R2a): `real_own_quantity_sell_worker = false` no `tfvars` da conta, `terraform plan` com uma única diferença, `apply` com MFA e implantação só da flag. O ciclo seguinte volta ao comportamento de antes da U4, sem migração.
3. **Depois, a API** (R2b): `real_own_quantity_sell_api = false`, com o mesmo procedimento. Nunca a API antes do worker.
4. Durante todo o processo o saque, "Vender tudo e sacar" e as condicionais continuam disponíveis.

**Verificação.** A revisão `PRIMARY` do `api-worker` com `worker false` (e depois `api false`); `real_own_holdings_reads_total` para de subir no ciclo seguinte; os dois alarmes em OK em até 20 min; o aviso some de `/bots` depois de R2b.

**Rollback.** O desligamento da flag é a própria volta. Voltar também a imagem: R2a junto com R4 (`rollback-runbook.md`, seção 3). Religar a flag **não** é rollback: é a virada inteira de novo (`real-operations.md`, seção 11, "Ligar"), sob a condição 1 e com a IR-G3 decidida.

**O que NÃO fazer.** Nunca aumentar o orçamento de 500 ms para calar o alarme (mudança de meta, decisão F7 do fundador). Nunca religar a flag sem a decisão F7 e a medida oficial. Nunca editar `bot_cycle`, decisões ou ordens. Nunca desligar a API antes do worker.

**Escalonamento.** Fundador. A flag desligada é a contenção que não depende dele: o sistema já falha fechado sem vender pela quantidade errada. Revisão pós-incidente obrigatória (P2).

---

## RB-18 — Bloco PAPER ou REAL indisponível nas telas (U1/U3)

| Campo | Conteúdo |
|---|---|
| Gatilho | **Sem alarme na nuvem** (D-INF3 e OBS-G5: os contadores da U1 e da U3 ficam só no Prometheus). Sinais: relato da pessoa ("Erro" num bloco, com "Tentar de novo"); consulta `eureka-real/pnl-blocos-indisponiveis` com contagem crescente; indiretamente `slo-api-availability-burn-1h`/`-6h` se o problema virar 5xx |
| Severidade | **P3** [Q1]: a outra metade continua aparecendo, nenhum dinheiro está em jogo. Vira **P2** se os dois blocos falharem para todas as pessoas por mais de 15 min (provável banco fora → RB-11 do 261113, que pode ser P1). Bloco REAL com `RECONCILIATION_MISMATCH` → RB-16 |
| Runbook existente | nenhum dedicado no `docs/runbooks/`. O comportamento está em `construction/u1-mode-skeleton/nfr-design/reliability-design.md` (tabela de falhas de C4) e `construction/u3-pnl-views/nfr-design/reliability-design.md` (C4 com `market`, C6 por lado). Lacuna IR-G10 |

**O que já aconteceu sozinho.** A rota respondeu 200: só o bloco que falhou saiu `UNAVAILABLE`, com `code` e `correlationId`. O outro bloco aparece completo. A tela mostra o estado "Erro" só no bloco afetado, e "Tentar de novo" relê só ele. **Os modos nunca são somados**, nem quando um deles falta: não existe total geral para "completar".

**Primeiros 5 minutos.** Saber se é uma pessoa, um bloco ou todos. `alarms` e `services`. Se os dois blocos falham para todos: RB-11 do 261113.

**Diagnóstico.**
- Logs Insights: `eureka-real/pnl-blocos-indisponiveis` (por tipo, `market` e `cause`, por hora) e `eureka-real/seguir-correlation-id` com o `correlationId` mostrado no bloco.
- Leitura das causas (`u1-mode-skeleton/nfr-design/reliability-design.md`):
  - `TIMEOUT` (prazo de 800 ms) ou fila do pool `pnl-mode-block` cheia: carga ou banco lento. Painel "Serviços", RDS `app` (D-INF2).
  - `TOO_MANY_ORDERS`: carteira acima de `real-max-orders`; é o limite desenhado, não falha. Anotar e encerrar.
  - `RECONCILIATION_MISMATCH`: RB-16.
  - os dois blocos `UNAVAILABLE`: banco fora.
- `eureka-real/pnl-recusas-e-auditoria`: `PNL_READ_NOT_FOUND` com `TENANT_MISMATCH` mostra tentativa de ler bot de outra conta; não é falha da tela, mas é anotado.
- **Limite da nuvem:** `pnl_mode_block_unavailable_total` e os contadores da U3 ficam só no Prometheus; na nuvem, a evidência é o log `WARN` (D-INF3). A latência por rota também não chega (OBS-G5).

**Mitigação.** Não há flag: as telas de P&L por modo, o P&L REAL por bot e as visões da U3 voltam só pela imagem (`rollback-runbook.md`, R5). Se começou depois de uma implantação, voltar a imagem de `api-worker` e de `web` (R3 a R7). Se é carga, acompanhar e registrar para o Performance Monitoring.

**Verificação.** O fundador entra com a carteira dele e confere as telas de Bots e de P&L: os dois blocos com valores, cada um na própria unidade (Simulado em US$, Real em SOL), sem total geral; a consulta `pnl-blocos-indisponiveis` sem contagem nova na hora seguinte.

**Rollback.** Só da imagem (R4 e R5); as variáveis da U1 (`MEMETRADE_PNL_BYMODE_*`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT`) podem ficar na revisão.

**O que NÃO fazer.** Nunca "completar" um bloco que falta com o outro modo, nem mostrar a soma. Nunca mostrar `unknown` como zero. Nunca aumentar o pool ou o prazo direto na definição de tarefa fora do Terraform.

**Escalonamento.** Fundador, no horário normal (P3). Revisão pós-incidente opcional em P3; obrigatória se subir para P2.

---

## RB-19 — Total geral PAPER + REAL em tela, API ou relatório

| Campo | Conteúdo |
|---|---|
| Gatilho | **Sem alarme** (lacuna IR-G9). Sinais: relato de uma pessoa ou observação do fundador; falha do spec de ponta a ponta `zz-paper-real-unico` ou dos testes de propriedade "nenhum terceiro número" e de componente "sem total geral" na CI (`u1-mode-skeleton/nfr-design/security-design.md`; `u3-pnl-views/nfr-design/reliability-design.md`, NFR7.2) |
| Severidade | **P1** [Q1]: é violação de regra Forbidden (`project.md`: "NEVER somar os modos PAPER e REAL num total geral, em tela, API ou relatório") e mistura dinheiro simulado com dinheiro real diante da pessoa |
| Runbook existente | `rollback-runbook.md` deste intent, **R5** ("Telas de P&L por modo: só pela imagem") e seção 6 (P1: voltar mais um SHA); `docs/runbooks/real-operations.md`, seção 7 (rollback pelas imagens) |

**Primeiros 5 minutos.**
1. Confirmar com os próprios olhos: o fundador abre a tela relatada (Bots, P&L, comparação) com a carteira dele e confere se existe algum número que seja a soma de PAPER e REAL, ou um valor de um modo na unidade do outro.
2. Anotar o SHA em serviço (`services`) e o horário da última implantação. C4, P1.
3. O dinheiro não está em risco por causa disso: não é preciso kill switch, salvo se outro alarme de dinheiro estiver em ALARM.

**Diagnóstico.**
- Na resposta de C4 (`GET /api/v1/pnl/by-mode`), C5 ou C6, vista pelas ferramentas do navegador com a sessão do fundador: existe só o bloco PAPER e o bloco REAL, cada um com os próprios valores, e nenhum terceiro número? Se o terceiro número vem da API, é defeito do servidor; se só a tela soma, é defeito do `web`.
- `git log` entre o SHA anterior e o SHA em serviço, nas pastas de P&L de `apps/api` e `apps/web`.
- Relatórios e exportações: conferir se a exportação também soma.
- Nenhuma consulta SQL é necessária (e a IR-G3 não deixaria): o defeito é de apresentação, e o ledger não muda.

**Mitigação.** **Voltar a imagem** pelo `rollback-runbook.md`: R3 (SHA anterior) e R4 com `web` e `api-worker`, sem `db-migrate`; R5 confere as telas. Se a versão anterior também somar, voltar mais um SHA (seção 6 do `rollback-runbook.md`). O saque e "Vender tudo e sacar" continuam disponíveis em todos os passos.

**Verificação.** O fundador confere de novo as telas de Bots, P&L e comparação, e as exportações: cada bloco mostra só o próprio modo, na própria unidade, sem total geral; o aviso fixo do bloco Simulado continua lá. Smoke verde (R6). Na CI do SHA corrigido, o spec `zz-paper-real-unico` e os testes "nenhum terceiro número" verdes.

**Rollback.** O próprio procedimento é a volta da imagem. A correção volta pelo caminho normal: teste de regressão que reproduz a soma (`team.md`, Defeitos), CI verde, devnet e mainnet.

**O que NÃO fazer.** Nunca "esconder" o total por CSS ou por configuração e deixar a imagem com defeito. Nunca corrigir somando de outro jeito. Nunca citar valores de pessoas na nota do incidente.

**Escalonamento.** Fundador. Se pessoas além do fundador viram a tela: comunicação pelo modelo M5 do `incident-plan.md` (seção 4). Revisão pós-incidente obrigatória (P1), com a pergunta "por que a CI não pegou?".

---

## RB-20 — Migração `0023` a `0027` travada (`lock_timeout`, índice `INVALID` ou trava do changelog)

| Campo | Conteúdo |
|---|---|
| Gatilho | Tarefa `db-migrate` com saída diferente de zero durante a implantação (`deploy.yml` vermelho no passo da migração, ou o passo 5 do caminho manual do `cd-config.md`, 2.2); log com `55P03` (`canceling statement due to lock timeout`), índice `INVALID` ou trava do changelog presa |
| Severidade | Não é incidente de produção na primeira falha: **a troca de imagens não começou e a versão anterior continua no ar** [Q1]. **P3** na primeira e na segunda falha. **P2** com três falhas seguidas iguais, ou com a trava do changelog presa (as implantações param) (`rollback-runbook.md`, seção 4) |
| Runbook existente | `rollback-runbook.md` deste intent, **seção 4** "Falha de migração durante a implantação (não é rollback)"; `docs/runbooks/real-operations.md`, **seção 9** (índice da `0023`, trava do changelog) e **seção 12** (`0026` ou `0027` barrada) |

**Primeiros 5 minutos.** Conferir que nenhum serviço foi trocado: `services` (revisões em serviço iguais às de antes). A implantação para aqui; nada a voltar.

**Diagnóstico.**
```bash
aws logs filter-log-events --log-group-name /ecs/$NAME/migrate --start-time <epoch ms do início> \
  --filter-pattern '?55P03 ?INVALID ?changeset'
aws ecs list-tasks --cluster "$CLUSTER" --family "$NAME-db-migrate" --desired-status RUNNING
```
- `55P03` na `0026`: a transação do changeset foi desfeita, a regra antiga continua inteira.
- Índice `INVALID` na `0023` ou na `0027`: o `DROP INDEX CONCURRENTLY IF EXISTS` da repetição apaga o resto.
- **Limite da IR-G3:** as conferências `to_regclass`/`pg_index.indisvalid` (seções 9 e 12), `pg_stat_activity` (seção 12) e `databasechangeloglock` (seção 9, passo 5) **não rodam na nuvem**. O diagnóstico fica no log do `db-migrate`.

**Mitigação.** Seção 4 do `rollback-runbook.md`, passos 2 a 4: esperar de 1 a 5 minutos (nunca matar a transação do ciclo ou da fila) e repetir a implantação com o **mesmo SHA**. Três falhas iguais: parar. Trava do changelog presa: **na nuvem não há caminho hoje** (passo 5): a implantação fica parada até existir a família de leitura e manutenção do fundador (IR-G3, opção A). Nunca aplicar migração à mão fora do Liquibase.

**Verificação.** `db-migrate` com saída 0; a implantação segue até o smoke verde (R6); nenhuma tarefa `db-migrate` em `RUNNING`.

**Rollback.** Não há rollback: nenhuma imagem foi trocada. As migrações já aplicadas ficam (só de expansão; `rollback-runbook.md`, R4). Em produção nenhuma migração é revertida, e nenhuma restauração por PITR desfaz uma implantação.

**O que NÃO fazer.** Nunca rodar `--rollback` do Liquibase na devnet ou na mainnet. Nunca matar a transação do ciclo ou do consumidor da fila. Nunca criar o índice à mão. Nunca usar `IF NOT EXISTS` para "passar".

**Escalonamento.** Fundador. P2 (três falhas ou trava presa): registrar e levar a IR-G3 à decisão. Revisão pós-incidente obrigatória em P2.

---

## RB-21 — Falha intermitente do `e2e` (TGT-E2E-FLAKY)

| Campo | Conteúdo |
|---|---|
| Gatilho | Job `e2e` do workflow `ci` vermelho no push para `main` com o `smoke` do mesmo commit verde, ou verde numa nova tentativa. Assinatura conhecida: `e2e/real-swap/swap.spec.ts:224` esperando 7,5 min uma compra REAL confirmada no validador local, com falhas em cascata nos specs que dependem do estado de entrada (run `45550427456`, tentativa 1) |
| Severidade | **Defeito de CI, não incidente de produção** [Q1]: fica fora da escala P1 a P3 e não aciona a matriz de plantão. Teste instável é defeito e nunca é ignorado em silêncio (`team.md`, Testing Posture). Ele **bloqueia toda implantação** até o run do SHA estar verde e a repetição registrada (condição 3 do `cd-config.md`, seção 6) |
| Runbook existente | `construction/build-and-test/test-results.md`, **seção 9.2** (ações 1 a 5) e seção 10; `observability-setup/alarms.md`, **seção 6.3** (texto pronto para guardar os logs da tentativa que falhou); `cd-config.md`, seção 6, condição 3 |

**Primeiros passos.**
1. Conferir a assinatura da falha:
   ```bash
   gh run view <id> --json attempt,conclusion,headSha,jobs \
     --jq '{attempt, conclusion, headSha, failed: [.jobs[] | select(.conclusion=="failure") | .name]}'
   gh run view <id> --attempt <n> --log-failed | grep -n "swap.spec.ts"
   ```
2. Mesma assinatura (compra REAL do `real-swap` sem confirmação): registrar a repetição na issue do defeito (`gh issue list --search "real-swap in:title"`; abrir se não existir) e só então repetir o job. Assinatura diferente: tratar como falha nova, sem repetir às cegas.
3. `main` vermelha: a correção vem no commit seguinte, antes de qualquer outra mudança (`project.md`, Mandated). Uma repetição verde da mesma assinatura, registrada, é o run verde que vale; uma falha nova não.

**Diagnóstico.** `test-results.md`, seção 9.2: a ordem ficou `SENT` sem confirmação (validador lento, `ChainWatcher`) ou nem foi enviada? E por que o `u2-audit-trail/smoke.spec.ts:19` falhou junto? Hoje os logs do validador, do worker e do TxSigner da tentativa que falhou se perdem, porque o `scripts/verify.sh` desmonta o ambiente na saída e o job `e2e` não guarda artefato. O texto pronto da seção 6.3 do `alarms.md` resolve isso.

**Mitigação.** Não há mitigação em produção. A ação é do defeito: aplicar o texto da seção 6.3 do `alarms.md` (só o responsável altera `scripts/verify.sh` e `.github/workflows/`), esperar a próxima falha com os logs, achar a causa e escrever o teste de regressão antes da correção.

**Verificação.** Defeito fechado com a causa identificada e um teste de regressão; uma sequência de runs do `e2e` no push para `main` sem a assinatura.

**Rollback.** Do texto da seção 6.3: apagar os dois trechos (nada depende deles).

**O que NÃO fazer.** Nunca marcar o alvo como `Met`, pular o spec, aumentar o tempo de espera para "passar" ou tirar o `e2e` da CI. Nunca usar um commit sem run verde em ponto de controle ou implantação (`project.md`, Mandated).

**Escalonamento.** O responsável do projeto (o próprio fundador), dono do defeito e o único que altera `.github/workflows/`. Revisão pós-incidente não se aplica (não é P1/P2); a análise fica na issue do defeito.

---

## Cobertura: alarme e sinal → runbook (delta deste intent)

Nomes completos com o prefixo `eureka-real-`. "Sev." é a severidade do alarme (`alarms.md`), que decide o canal; "Prioridade" é a do incidente (`incident-plan.md`, seção 1), que decide a resposta.

| # | Alarme ou sinal | Estado | Sev. | Runbook | Prioridade |
|---|---|---|---|---|---|
| 65 | `real-ops-pnl-reconciliation-mismatch` | no repositório | crítica | RB-16 (RB-02 se a rede divergir) | P1; P2 com a rede `CLEAN` |
| 66 | `real-ops-own-holdings-unavailable` | no repositório; OK enquanto a flag estiver desligada | alta | RB-17 | P2 |
| 67 | `real-ops-own-holdings-stuck-bots` | no repositório; OK enquanto a flag estiver desligada | alta | RB-17 | P2 |
| P-1 | `rds-app-connections` (D-INF2, opção c) | **proposto**, não aplicado | alta | RB-18 (carga) e RB-11 do 261113; runbook próprio a escrever com o alarme (`alarms.md`, 5.1) | P2 durante a troca; P3 fora dela |
| P-2 | `slo-own-holdings-burn-6h` (OBS-G6) | **proposto**, não aplicado; só faz sentido depois da virada da flag | média | RB-17 | P3 |
| — | relato de bloco em "Erro"; consulta `pnl-blocos-indisponiveis` | sem alarme (D-INF3, OBS-G5) | — | RB-18 | P3 |
| — | relato de total geral; spec `zz-paper-real-unico` ou teste "nenhum terceiro número" vermelho | sem alarme (IR-G9) | — | RB-19 | P1 |
| — | `db-migrate` com saída diferente de zero | sem alarme (o job da implantação é o sinal) | — | RB-20 | P3; P2 com 3 falhas ou trava presa |
| — | job `e2e` vermelho | CI | — | RB-21 | defeito de CI |

**Resultado: os 3 alarmes novos e os 2 propostos têm runbook.** Com os 64 do 261113, são 67 de 67 alarmes no repositório com runbook.

## Lacunas encontradas neste intent

| ID | Lacuna | Efeito | Dono | Antes de |
|---|---|---|---|---|
| IR-G3 (herdada) | Sem leitura SQL do banco na nuvem | RB-16 sem as diferenças por SQL; RB-17 sem as conferências da seção 11; RB-20 só com a repetição; trava do changelog presa para as implantações | fundador decide (opção C agora, A antes da virada; `security-posture-review.md`, 2.1) | virada da flag da U4 |
| **IR-G9** | O total geral PAPER + REAL (RB-19) não tem alarme nem verificação sintética na nuvem: só a CI (spec e testes de propriedade) e o olho de quem usa | P1 detectado por relato | fundador: candidata uma conferência manual das telas na janela de observação de cada implantação (já é R5 do `rollback-runbook.md`); um teste sintético autenticado na nuvem fica para depois | convidados |
| **IR-G10** | O bloco indisponível (RB-18) não tem seção em `docs/runbooks/` nem métrica na nuvem (D-INF3, OBS-G5) | diagnóstico só pelo log | próxima volta da U1/U3 ou Performance Monitoring | nada |
| **IR-G11** | As seis consultas salvas deste intent ainda não estão em `log-queries.tf` (`log-queries.md`, seção 4) | RB-16 a RB-18 precisam colar a consulta no console | responsável, na mesma volta de IaC da D-INF2 | primeira implantação na nuvem |
| OBS-G5, OBS-G6, OBS-G7, D-INF2, TGT-E2E-FLAKY | registradas no `alarms.md` (seção 6) | ver lá | ver lá | ver lá |

## Sources

- `incident-response-questions.md` (Q1 a Q4, resumo confirmado) [Q1] [Q2] [Q3] [Q4].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/runbooks.md` (regras, seção 0, C1 a C5, RB-01 a RB-15, cobertura, IR-G1 a IR-G8).
- `operation/observability-setup/alarms.md` (seções 3 a 6, inclusive 6.3), `slo-config.md` (SLO-U2-1, SLO-U4-1, política do orçamento), `log-queries.md` (seções 2 a 4) e `dashboards.md` (seção 2).
- `operation/deployment-pipeline/rollback-runbook.md` (R0 a R9, seções 4 e 6) e `cd-config.md` (seções 6 e 8).
- `operation/environment-provisioning/security-posture-review.md` (2.1 IR-G3, 2.2 D-INF2).
- `construction/build-and-test/test-results.md` (seções 9.2 e 10).
- `construction/u1-mode-skeleton/nfr-design/reliability-design.md` e `security-design.md`; `construction/u3-pnl-views/nfr-design/reliability-design.md`; `construction/u2-real-bot-pnl/` e `u4-own-quantity-cycle/` `nfr-design/reliability-design.md` e `infrastructure-design/infrastructure-specification.md`; `u3-pnl-views/infrastructure-design/monitoring-design.md` (D-INF3).
- `docs/runbooks/real-operations.md` (seções 2, 7 a 13).
- `team.md` (Deployment, Testing Posture, Way of Working) e `project.md` (Mandated e Forbidden).

## Assumptions & Open Questions

1. [assumption] A prioridade da divergência do P&L REAL segue a resposta Q1 (P1) e não a tabela do `alarms.md` (seção 4, P2) nem o `rollback-runbook.md` (seção 6, P2). As duas leituras foram conciliadas: P1 ao detectar, rebaixada para P2 quando a reconciliação da rede está `CLEAN`. Se o fundador preferir P2 desde o início, muda só a tabela do RB-16.
2. [assumption] As prioridades da migração travada (P3, P2 com três falhas) e o `e2e` como defeito de CI fora da escala P vêm do `rollback-runbook.md` (seção 4) e da Q1, que não deu um P a esses dois modos.
3. [assumption] A administração mostra o `details` do evento de auditoria `REAL_PNL_RECONCILIATION_MISMATCH`; se não mostrar, o RB-16 depende da reprodução local até a IR-G3 fechar.
4. Aberta: IR-G3 (decisão do fundador antes da virada da flag).
5. Aberta: IR-G9, IR-G10 e IR-G11, com donos na tabela de lacunas.
6. [assumption] Os comandos AWS reaproveitam as funções da seção 0 do 261113 e os nomes com `var.name = eureka-real`; nada foi conferido numa conta.
