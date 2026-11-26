# Alarmes do CloudWatch — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup. Decisões: Q1 a Q4, todas na opção A, com o resumo confirmado. Data: 2026-11-25. Commit de referência: `09de0a8` (`main`).

> **Nada está implantado.** Não existe conta AWS nem ambiente na nuvem; o único ambiente que roda é o local (Docker Compose) e a CI [Q1]. Os alarmes abaixo estão no repositório como código: regras do Prometheus testadas na CI (`promtool`) e gêmeos do CloudWatch no Terraform, validados offline. **Este estágio não muda código, IaC nem workflow.** Cada alarme só fica verificado depois do primeiro `apply` da devnet, com os comandos da seção 7.

Este arquivo registra só o **delta** deste intent sobre o `alarms.md` do `261113-trading-real-bot` (`aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/alarms.md`). Tudo o que não aparece aqui continua como está lá.

## 1. O que é herdado sem mudança

| Item do 261113 | Situação neste intent |
|---|---|
| Caminho das métricas: sidecar `metrics-agent` (CloudWatch Agent) nas tarefas `api-worker` e `txsigner`, EMF em `Eureka/App`, imagem fixada por digest (seção 1 do 261113) | sem mudança. `modules/services/metrics-agent.tf` não mudou; o filtro `keep` e as declarações EMF são gerados da lista de permissão e ganham sozinhos as 6 séries novas (seção 2) |
| Leitura a cada **120 s**; alarmes `breaching` com períodos múltiplos de **240 s**; demais com pelo menos **180 s** (`project.md`, correção de 2026-11-20) | sem mudança; os 3 alarmes novos seguem a regra (seção 3.2) |
| Tópico SNS único `eureka-real-alarms` (chave KMS própria), e-mail do fundador; rotas por severidade e escalonamento por tempo (`incident-response/escalation-matrix.md` do 261113, seções 2 e 6) | sem mudança; os 3 alarmes novos casam com o padrão `real-ops-*` da rota "Alta" (seção 4) |
| Os 62 alarmes ativos e os 2 desligados (`real-risk-slow`, `real-execution-slow`) do 261113, inclusive `metrics-absent-api`, `-worker`, `-txsigner` e os 6 de consumo de SLO | sem mudança de limite nem de janela |
| Lacunas OBS-G1 a OBS-G4 e itens OBS-O1 a OBS-O5 | sem mudança (seção 6) |
| Teste offline `infra/terraform/tests/observability_metrics.tftest.hcl` (todo alarme lê métrica da lista, períodos na regra de 120 s, todos avisam o tópico, inventário igual aos blocos `resource`) | sem mudança de regra; já cobre os 3 alarmes novos |

## 2. Métricas que este intent publica no CloudWatch

Lidas em `infra/terraform/modules/observability/app-metrics.tf` (`app_metric_declarations`). São **6 séries novas** em `Eureka/App`, nenhuma com tenant, pessoa, bot, carteira, mint ou assinatura.

| Métrica | Linha | Dimensões | Séries | Unit |
|---|---|---|---|---|
| `pnl_real_reconciliation_mismatches_total` | `app-metrics.tf:71` | nenhuma (o agente soma as 8 séries de `check`) | 1 | U2 (NFR2.14, D-INF1) |
| `real_own_holdings_reads_total` | `app-metrics.tf:74` | nenhuma | 1 | U4 (NFR2.16) |
| `real_own_holdings_stuck_bots` | `app-metrics.tf:75` | nenhuma | 1 | U4 (NFR2.18, F3) |
| `real_own_holdings_unavailable_total` | `app-metrics.tf:79` | `reason` ∈ `BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED` | 3 | U4 (NFR2.12) |

Ficam **só no Prometheus** (o filtro `keep` descarta): os contadores da U1 (`bots_real_create_refusals_total`, `pnl_mode_block_unavailable_total`), as outras quatro métricas da U2 (`ledger_real_slot_missing_total`, `ledger_real_slot_conflicts_total`, `pnl_real_reconciliation_audit_failures_total`, o histograma `pnl_real_attribution_read_seconds`), os três da U3 (D-INF3) e `real_cycle_sell_capped_total` da U4. Na nuvem, a evidência deles são os logs (`log-queries.md`).

## 3. Alarmes que este intent acrescenta

### 3.1 Tabela

Todos em `infra/terraform/modules/observability/real-ops.tf`, com o gêmeo do Prometheus em `infra/observability/real-ops-alerts.yml` (grupo `real-ops`, agora com **16 regras**). A paridade é conferida por `RealOpsAlarmParityTest` (`hasSize(16)`). Com `var.name = eureka-real`, o nome completo ganha o prefixo `eureka-real-`.

| Alarme (CloudWatch) | Regra (Prometheus) | Métrica e expressão | Limite e janela | Dado ausente | Severidade | Runbook |
|---|---|---|---|---|---|---|
| `real-ops-pnl-reconciliation-mismatch` (`real-ops.tf:198-210`) | `RealOpsPnlReconciliationMismatch` (`real-ops-alerts.yml:144-154`): `sum(increase(pnl_real_reconciliation_mismatches_total[4m])) > 0` | `pnl_real_reconciliation_mismatches_total`, `Sum` | `> 0` em 1 período de 240 s (duas leituras) | `notBreaching` | **crítica** | `docs/runbooks/real-operations.md`, seção 8 "Divergência do P&L REAL" |
| `real-ops-own-holdings-unavailable` (`real-ops.tf:217-265`) | `RealOpsOwnHoldingsUnavailable` (`:155-173`): razão em 10 min `> 0.05` por `15m` | `e1 = IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`; `m1` e `m2` = `real_own_holdings_unavailable_total` com `reason` `BUDGET_EXCEEDED` e `READ_FAILED`; `m3` = `real_own_holdings_reads_total`; `Sum` | `> 0.05` em 2 de 2 períodos de 600 s (cerca de 5 leituras por período) | `notBreaching` | **alta** | seção 10 "Posição própria indisponível" |
| `real-ops-own-holdings-stuck-bots` (`real-ops.tf:270-283`) | `RealOpsOwnHoldingsStuckBots` (`:174-185`): `max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0` | `real_own_holdings_stuck_bots`, `Minimum` | `> 0` em 2 de 2 períodos de 300 s (10 min) | `notBreaching` | **alta** | seção 10 |

- **Destino:** os três usam `alarm_actions = [var.alarm_topic_arn]`, o tópico `eureka-real-alarms`. Nenhum tem `ok_actions` (só o batimento tem).
- **Inventário:** os três estão em `alarm_resource_names` (`app-metrics.tf:162-163`) e em `metric_alarms` (`:190-192`). O teste offline confere que um alarme fora dessa lista falha.
- **Com a flag da U4 desligada** (`MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` em todo ambiente), não há leitura da posição própria: a razão vale 0 pelo `IF` e o gauge fica em 0. Os dois alarmes da U4 ficam `OK` até a virada da flag, que depende da decisão F7 (`slo-config.md`, SLO-U4-1).
- **Worker parado:** os três ficam `OK` por `notBreaching`. Quem avisa é o `metrics-absent-worker` (crítico, `breaching`), herdado.
- **Custo:** 1 + 3 + 1 = 5 métricas de alarme × cerca de US$ 0,10 = **≈ US$ 0,50 por conta e por mês**, já dentro dos ≈ US$ 2,30 do inventário (`environment-provisioning/environment-inventory.md`, seção 3).

### 3.2 Janelas na regra da leitura a cada 120 s

| Alarme | Janela do Prometheus | Janela na nuvem | Regra do `project.md` |
|---|---|---|---|
| divergência do P&L | 240 s | 1 × 240 s | `notBreaching`, ≥ 180 s: atende (duas leituras) |
| razão da posição própria | 600 s, `for: 15m` | 2 × 600 s (`cloud_periods: "2"`) | ≥ 180 s: atende |
| bot preso | 600 s | 2 × 300 s (`cloud_periods: "1"`) | ≥ 180 s: atende (2 a 3 leituras por período) |

A conta é a de `com.memetrade.infra.CloudWatchWindows`, usada pelo `RealOpsAlarmParityTest`.

### 3.3 Limites abaixo da quebra do SLO

Pela regra da fase Operation, cada limite dispara antes de o SLO romper (a matemática está em `slo-config.md`):

- **Divergência do P&L REAL (SLO-U2-1, 99,9% das leituras sem divergência em 30 dias):** o alarme dispara na **primeira** divergência, com 240 s de janela. O orçamento do mês só acaba com 0,1% das leituras divergentes.
- **Razão da posição própria (SLO-U4-1, `BUDGET_EXCEEDED` ≤ 0,1% das leituras em 30 dias):** uma razão de 5% é uma taxa de consumo de 50 vezes. Os 20 minutos do alarme gastam 50 × 1.200 s / 2.592.000 s ≈ **2,3% do orçamento** de 30 dias; nesse ritmo, o orçamento só acabaria em 14,4 h. O numerador do alarme inclui também `READ_FAILED`, o que o torna mais sensível que o SLI. **Limitação:** um consumo lento, com razão entre 0,6% e 5%, esgota o orçamento em 14,4 h a 5 dias sem disparar o alarme. A proposta de um alarme de consumo lento está na seção 5.2 (OBS-G6).
- **Bot preso:** um bot com 3 leituras indisponíveis seguidas por 10 min. Com leituras independentes a 0,1%, um falso alarme tem probabilidade de cerca de 10⁻⁹ por sequência (`u4-own-quantity-cycle/nfr-design/observability-design.md`, premissa F6). Ele cobre o caso que a razão não vê (1 bot é 1,6% das leituras com 63 bots).
- **Latência do P&L (SLO-PNL-1) e atribuição (SLO-U2-2):** **não há sinal na nuvem** para pôr um limite antes da quebra, porque os histogramas são descartados pelo agente (OBS-G4 herdada, OBS-G5 nova). A disponibilidade do api continua coberta pelos alarmes de consumo herdados (`slo-api-availability-burn-1h` e `-6h`).

## 4. Severidade, roteamento e escalonamento

Herdados da `escalation-matrix.md` do 261113. Os três alarmes novos casam com `real-ops-*` (exceto `-release-blocked`), que está na rota **Alta**.

| Alarme | Severidade | Canal | Prioridade inicial | Resposta | Escalonamento |
|---|---|---|---|---|---|
| `real-ops-pnl-reconciliation-mismatch` | crítica | e-mail (e SMS quando o ESC-2 for aplicado) | **P2**: a divergência afeta só a leitura do P&L; ordens, barreira e saque seguem. Vira **P1** se a reconciliação da rede (runbook, seção 2) também mostrar divergência da carteira | reconhecer em até 1 h; achar o evento `REAL_PNL_RECONCILIATION_MISMATCH` pelo `correlationId`; abrir defeito com teste de regressão | fundador (único on-call). Segundo contato: "a definir" (ESC-6). P1 segue o escalonamento por tempo do 261113 (15 min: kill switch se houver dinheiro em risco) |
| `real-ops-own-holdings-unavailable` | alta | e-mail (e SMS com o ESC-2) | **P2**: o bot não vende pelo ciclo; o dinheiro sai pelas condicionais, por "Vender tudo e sacar" e pelo saque | reconhecer em até 1 h; achar o motivo pela consulta `real-posicao-propria-indisponivel`; com `BUDGET_EXCEEDED` sistêmico, olhar o RDS `app` e o pool; se não resolver, **desligar a flag do worker** (`rollback-runbook.md`, R2) | fundador. A flag desligada é a contenção sem o fundador |
| `real-ops-own-holdings-stuck-bots` | alta | e-mail (e SMS com o ESC-2) | **P2** | achar o `botId` pela consulta `real-posicao-propria-bots-presos`; `BOT_NOT_OWNED` sempre vira defeito | fundador |

- **Contato:** o e-mail do tópico é `var.alarm_email`, preenchido fora do repositório (ESC-1). Nenhum endereço é escrito aqui.
- **Revisão pós-incidente:** obrigatória em todo P1 e P2 (regra da fase Operation; `incident-plan.md` do 261113, seção 5).
- **Implantação:** a janela de observação de 10 minutos aborta com qualquer alarme `eureka-real-*` em `ALARM` (`deployment-pipeline/deployment-strategy.md`, 1.3). Os três entram nessa leitura sem mudança.

## 5. Alarmes propostos (decisão do fundador)

Nenhum destes está no repositório. Nenhum é aplicado por este estágio. Cada um pede uma mudança de IaC numa volta à Construction (Infrastructure Design e Code Generation) ou num intent de infraestrutura.

### 5.1 D-INF2: conexões do RDS `app`

| Campo | Valor proposto |
|---|---|
| Origem | `u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` (D-INF2); `environment-provisioning/security-posture-review.md`, 2.2, opção **c** |
| Recurso | `aws_cloudwatch_metric_alarm.rds_app_connections` (nome proposto), `${var.name}-rds-app-connections` |
| Métrica | `AWS/RDS` `DatabaseConnections`, `DBInstanceIdentifier = ${var.name}-app`, `Maximum` |
| Janela | `period = 60`, `evaluation_periods = 3`, `datapoints_to_alarm = 2` (180 s). Métrica básica do RDS, não passa pelo agente; a janela ainda atende ao mínimo de 180 s do `project.md` |
| Limite | 85% do `max_connections`: **95** na `db.t4g.micro` (cerca de 112) ou **190** na `db.t4g.small` (cerca de 225) |
| Dado ausente | `notBreaching` |
| Severidade e destino | alta; tópico `eureka-real-alarms`; rota "Alta" da matriz |
| Runbook | a escrever com o alarme: durante uma implantação, conferir se a tarefa antiga já saiu; fora dela, procurar vazamento de conexões (`hikaricp_connections_pending` no ambiente local) |
| Testes | entra em `alarm_resource_names`, em `metric_alarms` e em `other_metric_sources` (`"AWS/RDS" = ["DatabaseConnections"]`), senão o `observability_metrics.tftest.hcl` falha |
| Custo | **≈ US$ 0,10 por conta e por mês** (1 métrica de alarme); a métrica do RDS é grátis |
| Abaixo da quebra | dispara com 85% do teto, antes de a conexão ser recusada (100%) |
| Decisão | **do fundador**, junto com a classe do banco (opção **b**, `db.t4g.small`, ≈ US$ 11,68 por conta). Proposta da Environment Provisioning: **b + c** antes da primeira implantação na nuvem (`cd-config.md`, seção 6) |

Até a decisão, o fundador acompanha o gráfico `RDS: conexões` do painel Serviços durante a troca (`dashboards.md`, seção 3).

### 5.2 OBS-G6: consumo lento do orçamento da posição própria

| Campo | Valor proposto |
|---|---|
| Recurso | `aws_cloudwatch_metric_alarm.slo_own_holdings_budget_burn_6h` (nome proposto), `${var.name}-slo-own-holdings-burn-6h` |
| Expressão | `IF(FILL(m3, 0) > 0, FILL(m1, 0) / m3, 0)`, com `m1` = `real_own_holdings_unavailable_total{reason=BUDGET_EXCEEDED}` e `m3` = `real_own_holdings_reads_total`, `Sum`, `period = 21600` |
| Limite | `> 0.006` (taxa de consumo 6 sobre o SLO de 0,1%: 5% do orçamento em 6 h, a mesma fórmula do `slo-config.md` do 261113), 1 avaliação, `notBreaching` |
| Severidade | média (rota "só e-mail") |
| Custo | 2 métricas de alarme ≈ **US$ 0,20 por conta e por mês** |
| Quando | antes da virada da flag da U4. Com a flag desligada, não há leitura e o alarme não tem sentido |
| Decisão | do fundador, junto com a F7 |

### 5.3 Efeito no orçamento

| Item | US$/mês por conta |
|---|---|
| Total da mainnet com este intent (inventário) | ≈ 314 a 323 |
| + D-INF2 b + c | + ≈ 11,78 → ≈ 326 a 335 |
| + OBS-G6 | + ≈ 0,20 → ≈ 326 a 335 |
| Orçamento (`project.md`, correção de 2026-11-20) | **350** |

## 6. Lacunas de observabilidade

O precedente não tem um arquivo `observability-gaps.md`; as lacunas ficam aqui, como na seção 5 dele.

### 6.1 Herdadas, sem mudança

| ID | Situação |
|---|---|
| **D-INF2** | sem alarme de conexões do RDS. Proposta na seção 5.1. Bloqueia a primeira implantação na nuvem até a decisão (`cd-config.md`, seção 9, item 6) |
| **OBS-G1** | `real-risk-slow` (p95 da barreira) escrito, em paridade e **desligado** (`p95_alarms_enabled = false`): `real_risk_evaluation_seconds` é histograma. Sem mudança |
| **OBS-G2** | `real-execution-slow` escrito e desligado, pela mesma causa. Sem mudança |
| **OBS-G3** | o log de 5xx não traz a rota. Sem mudança |
| **OBS-G4** | `http_server_requests` e outros histogramas não chegam à nuvem. Sem mudança; agora afeta também o SLO-PNL-1 (OBS-G5) |

### 6.2 Novas neste intent

| ID | Lacuna | Proposta | Dono | Bloqueia |
|---|---|---|---|---|
| **OBS-G5** | A latência do P&L (C4, C5, C6) e a atribuição `PNL` não têm SLI na nuvem: `http_server_requests` e `pnl_real_attribution_read_seconds` são histogramas, e o balanceador não separa rotas. Os blocos `UNAVAILABLE` por `TIMEOUT` (prazo de 800 ms) só aparecem no log. | (a) Aceitar: o SLO é medido no cenário de carga `pnl-by-mode` e na leitura do fundador na devnet (100 pedidos), e os logs mostram os blocos `TIMEOUT`. (b) Um filtro de métrica no log `P&L block unavailable` com `cause=TIMEOUT` (≈ US$ 0,30 da métrica + 0,10 do alarme). Recomendação: **(a)** no piloto, rever em Performance Monitoring | fundador | nada |
| **OBS-G6** | Consumo lento do orçamento da posição própria não dispara alarme (seção 3.3). | Alarme da seção 5.2 | fundador | a virada da flag |
| **OBS-G7** | **Conflito de meta:** `RealRiskSlow` (`real-risk-alerts.yml:70-80`) e o gêmeo desligado `real-risk-slow` (`real-risk.tf:95-103`) ainda usam **150 ms**. O fundador trocou a meta da compra para o **teto de 500 ms** em 2026-11-23 (U2-NFR1.5). Como aviso antecipado, 150 ms fica abaixo da quebra; mas a base desta máquina já era 206 ms, então o aviso dispararia sem motivo. | Ao fechar a OBS-G1, decidir: manter 0,15 s como aviso antecipado ou passar a 0,4 s (abaixo do teto de 500 ms). Mudança em `real-risk-alerts.yml`, `real-risk.tf` e no `RealRiskAlarmParityTest` | fundador | nada (o alarme está desligado) |
| **TGT-E2E-FLAKY** | O `e2e` falhou na tentativa 1 do run `45550427456` (compra REAL do `real-swap` sem confirmação) e passou na tentativa 2. Os logs do validador, do worker e do TxSigner da tentativa que falhou se perderam: o `scripts/verify.sh` desmonta o ambiente (`docker compose down -v`) na saída, e o `ci.yml` não guarda artefato no job `e2e`. | Texto pronto na seção 6.3 | **o responsável aplica** (só ele altera `.github/workflows/`) | nada; cada repetição do `e2e` continua registrada (`cd-config.md`, seção 6, item 3) |

### 6.3 TGT-E2E-FLAKY: texto pronto para o `ci.yml`

**Por que o `ci.yml` sozinho não basta.** O `verify.sh` roda `docker compose ... down -v --remove-orphans` no `trap cleanup EXIT` (`scripts/verify.sh:31-42`). Quando o passo seguinte do workflow roda, os contêineres já não existem, e `docker compose logs` não devolve nada. Por isso a proposta tem duas partes: o `verify.sh` grava os logs antes de desmontar, só quando a variável `VERIFY_LOGS_DIR` existe; e o `ci.yml` liga a variável no job `e2e` e envia o artefato quando o passo falha. Sem a variável (ambiente local e job `smoke`), nada muda.

**Parte 1: `scripts/verify.sh`.** Acrescentar a função antes de `cleanup()` e a chamada dentro do `if` de falha:

```bash
# TGT-E2E-FLAKY (261121-paper-real-unico, observability-setup alarms.md 6.3): when VERIFY_LOGS_DIR is set (the e2e job
# of ci.yml), keep the logs of a failed run before the teardown: the validator, the worker, the TxSigner and the api.
# The logs of the api, the worker and the TxSigner pass the same NFR7.3 scan as the end of this script; a file with a
# forbidden value is replaced by a note. kms-sim and postgres are never kept.
keep_failure_logs() {
  [ -n "${VERIFY_LOGS_DIR:-}" ] || return 0
  mkdir -p "$VERIFY_LOGS_DIR"
  local service file needle
  for service in solana-validator worker txsigner api; do
    file="$VERIFY_LOGS_DIR/${service}.log"
    "${COMPOSE[@]}" logs --no-color --timestamps --tail 20000 "$service" >"$file" 2>&1 || true
    [ "$service" = solana-validator ] && continue
    for needle in "${SMOKE_BETA_WALLET_ADDRESS:-}" "${ADMIN_WALLET_ADDRESSES:-}" "${SMOKE_FEEDBACK_MARKER:-}" \
      "${SMOKE_INVITE_CODE:-}" "${SMOKE_ANONYMIZATION_INVITE_CODE:-}"; do
      if [ -n "$needle" ] && grep -qF -- "$needle" "$file"; then
        printf 'removed: a value the NFR7.3 scan forbids appeared in this log\n' >"$file"
        break
      fi
    done
  done
}
```

```bash
cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    log "failure (exit $status); last api, worker and admin logs:"
    "${COMPOSE[@]}" logs --no-color --tail 80 api worker admin >&2 || true
    keep_failure_logs || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$KEY_DIR"
  log "environment removed and smoke key deleted"
  exit "$status"
}
```

**Parte 2: `.github/workflows/ci.yml`, job `e2e`.** Trocar o último passo (linhas 192-196) por estes dois. A ação é a mesma `actions/upload-artifact` que o `ci.yml` já usa, fixada pelo mesmo SHA (linha 57), então o `WorkflowsTest` continua verde e as `permissions` não mudam (`contents: read`):

```yaml
      - name: Full Playwright run on the smoke environment
        env:
          IMAGE_TAG: ${{ github.sha }}
          BUILD_SHA: ${{ github.sha }}
          # TGT-E2E-FLAKY: scripts/verify.sh keeps the logs of a failed run here before the teardown.
          VERIFY_LOGS_DIR: ${{ runner.temp }}/verify-logs
        run: bash scripts/verify.sh
      # TGT-E2E-FLAKY (261121-paper-real-unico): the logs of the validator, the worker, the TxSigner and the api, and the
      # Playwright results (error-context.md, traces, HTML report) of a failed attempt, kept 7 days.
      - name: Keep the logs of a failed e2e run
        if: failure()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: e2e-failure-logs-attempt-${{ github.run_attempt }}
          path: |
            ${{ runner.temp }}/verify-logs
            apps/web/test-results
            apps/web/playwright-report
          if-no-files-found: warn
          retention-days: 7
```

| Aspecto | Avaliação |
|---|---|
| Nome do artefato | leva `github.run_attempt`, para a tentativa 1 e a tentativa 2 não colidirem |
| Segurança | repositório privado; o artefato só existe no push para `main` (o `e2e` não roda em PR nem em fork). Nenhum segredo: as senhas do banco são geradas por execução e nunca impressas, a chave da carteira de teste fica fora do repositório e é apagada no `cleanup`, e o `kms-sim` e o `postgres` ficam de fora. Os logs do api, do worker e do TxSigner passam pela mesma varredura do NFR7.3. O log do validador traz só endereços das carteiras de teste geradas na execução, sem valor fora dela. |
| Custo | o armazenamento de artefatos conta na cota do plano do GitHub; 7 dias de retenção e só em falha. Tempo de runner: segundos |
| Rollback | apagar os dois trechos; nada mais depende deles |
| Opcional | o mesmo `env` e o mesmo passo no job `smoke`, que roda a mesma suíte em todo PR. Decide o responsável |
| Depois de aplicar | a próxima falha do `e2e` deixa o artefato; a investigação da seção 9.2 do `test-results.md` (ordem `SENT` sem confirmação ou nunca enviada) passa a ter os logs do `ChainWatcher` e do validador |

## 7. Verificação depois do primeiro apply da devnet

Herdada do 261113 (seção 10), com estes passos a mais. Rodar com o papel de administração do fundador, com MFA, na conta da devnet.

```bash
# 1. As 6 séries novas chegaram ao Eureka/App (as da U4 só aparecem com valor 0 até a virada da flag)
for m in pnl_real_reconciliation_mismatches_total real_own_holdings_reads_total real_own_holdings_stuck_bots real_own_holdings_unavailable_total; do
  aws cloudwatch list-metrics --namespace Eureka/App --metric-name "$m" --query 'Metrics[].Dimensions' --output json
done

# 2. Os três alarmes existem, avisam o tópico e estão em OK (flag desligada)
aws cloudwatch describe-alarms --alarm-names \
  eureka-real-real-ops-pnl-reconciliation-mismatch \
  eureka-real-real-ops-own-holdings-unavailable \
  eureka-real-real-ops-own-holdings-stuck-bots \
  --query 'MetricAlarms[].[AlarmName,StateValue,AlarmActions[0]]' --output table

# 3. A rota funciona: forçar o alarme da divergência, conferir o e-mail e devolver o estado
aws cloudwatch set-alarm-state --alarm-name eureka-real-real-ops-pnl-reconciliation-mismatch \
  --state-value ALARM --state-reason "teste do Observability Setup 261121"
aws cloudwatch set-alarm-state --alarm-name eureka-real-real-ops-pnl-reconciliation-mismatch \
  --state-value OK --state-reason "fim do teste"

# 4. Antes da virada da flag na mainnet (pré-condição R-05 da U4): na devnet, com o worker ligado por 1 hora,
#    a expressão e1 tem valor em todo período de 600 s
aws cloudwatch get-metric-data --start-time "$(date -u -d '-1 hour' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" \
  --metric-data-queries '[
    {"Id":"m1","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_unavailable_total","Dimensions":[{"Name":"reason","Value":"BUDGET_EXCEEDED"}]},"Period":600,"Stat":"Sum"},"ReturnData":false},
    {"Id":"m2","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_unavailable_total","Dimensions":[{"Name":"reason","Value":"READ_FAILED"}]},"Period":600,"Stat":"Sum"},"ReturnData":false},
    {"Id":"m3","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_reads_total"},"Period":600,"Stat":"Sum"},"ReturnData":false},
    {"Id":"e1","Expression":"IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)","ReturnData":true}]' \
  --query 'MetricDataResults[0].[Timestamps,Values]'
```

## Sources

- `observability-setup-questions.md` (Q1 a Q4, resumo confirmado) [Q1] [Q2] [Q4].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/alarms.md` (base herdada: seções 1, 2, 3, 5, 6 e 10) e `operation/incident-response/escalation-matrix.md` (seções 2, 6 e 7).
- `operation/environment-provisioning/environment-inventory.md` (seções 2.4, 2.5 e 3) e `security-posture-review.md` (2.2, D-INF2, opções b e c).
- `operation/deployment-pipeline/cd-config.md` (seções 6 e 9), `deployment-strategy.md` (1.3) e `rollback-runbook.md` (R2).
- `construction/u2-real-bot-pnl/infrastructure-design/monitoring-design.md` e `nfr-design/observability-design.md` (alarme da divergência, seção 8 do runbook).
- `construction/u4-own-quantity-cycle/infrastructure-design/monitoring-design.md` e `nfr-design/observability-design.md` (razão F2, bot preso F3, premissa F6, prova R-05).
- `construction/u1-mode-skeleton/` e `u3-pnl-views/` `infrastructure-design/monitoring-design.md` (nenhum alarme; D-INF3).
- `construction/build-and-test/test-results.md` (seções 9.2, 9.3 e 10: TGT-E2E-FLAKY, U4 `Not Met`).
- Código lido no commit `09de0a8`: `infra/observability/real-ops-alerts.yml` (16 regras), `real-risk-alerts.yml` (`RealRiskSlow`), `infra/terraform/modules/observability/real-ops.tf`, `real-risk.tf`, `app-metrics.tf`, `dashboards.tf`, `scripts/verify.sh`, `.github/workflows/ci.yml`, `apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java`, `apps/web/playwright.config.ts`, `docker-compose.yml`, `infra/docker-compose.real.yml`.
- `aidlc/spaces/default/memory/project.md` (correção do CloudWatch Agent a 120 s, janelas de 240 s e 180 s, orçamento de US$ 350) e `team.md` (Way of Working: só o responsável altera `.github/workflows/`; Deployment: fundador único on-call).

## Assumptions & Open Questions

1. [assumption] Preços de referência da `us-east-1` (US$ 0,10 por métrica de alarme padrão). A região ainda não foi escolhida.
2. [assumption] A contagem de 62 alarmes ativos do 261113 continua a base; somados os 3 deste intent, são 65 ativos e 2 desligados. Este estágio não recontou os blocos `for_each`.
3. [assumption] A prioridade P2 da divergência do P&L REAL vem do runbook (seção 8: ordens, barreira e saque não são afetados). Se o fundador preferir P1 sempre, muda só o texto desta tabela.
4. Aberta: D-INF2 (alarme c e classe b). Dono: fundador, antes da primeira implantação na nuvem.
5. Aberta: OBS-G6 (alarme de consumo lento). Dono: fundador, junto com a F7 e antes da virada da flag.
6. Aberta: OBS-G7 (limite de 150 ms do `RealRiskSlow` contra o teto de 500 ms). Dono: fundador, ao fechar a OBS-G1.
7. Aberta: aplicar o texto da seção 6.3 (`verify.sh` e `ci.yml`). Dono: o responsável do projeto. O `verify.sh` é o comando de verificação autorizado por ele, então a mudança também é dele.
8. [assumption] O `upload-artifact` v4 aceita, no mesmo `path`, um caminho absoluto em `runner.temp` e caminhos relativos ao workspace; o artefato guarda a estrutura a partir do ancestral comum.
