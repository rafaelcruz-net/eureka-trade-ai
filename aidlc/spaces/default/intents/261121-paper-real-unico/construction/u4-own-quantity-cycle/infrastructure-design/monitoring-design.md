# Desenho de monitoramento — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-03, R-05) e da decisão do fundador F4.

Este arquivo leva o `observability-design.md` do NFR Design para a plataforma [Q2]. A U4 acrescenta:

- três contadores (sete séries) e um gauge no `/actuator/prometheus` do worker;
- duas regras no Prometheus e os dois gêmeos no CloudWatch:
  - a razão de falhas (decisão do fundador F2);
  - o bot preso (decisão do fundador F3);
- três métricas na lista de permissão do agente, que publicam cinco métricas no CloudWatch;
- quatro seções no runbook de operações do REAL.

Ela não acrescenta painel, consulta salva, tracing nem SLO operacional.

## Métricas e KPIs

| Métrica (Prometheus) | Fonte | Limite | Por que importa |
|---|---|---|---|
| `real_own_holdings_reads_total` (sem etiqueta) | worker, porta 8082 | denominador da razão | Conta as chamadas ao RealBotHoldings. Ciclos sem leitura não contam (NFR2.16). **No CloudWatch**, sem dimensões. |
| `real_own_holdings_unavailable_total{reason}` (`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`) | worker | numerador da razão: `BUDGET_EXCEEDED + READ_FAILED` acima de 5% | A falha sistêmica da leitura da posição própria (NFR2.10, NFR2.12). **No CloudWatch**, com a dimensão `reason` (3 métricas). |
| `real_own_holdings_stuck_bots` (gauge sem etiqueta) | worker | `> 0` por 10 min | Bots com 3 ou mais leituras indisponíveis seguidas, que a razão não vê (NFR2.18, F3). **No CloudWatch**, sem dimensões. |
| `real_cycle_sell_capped_total{outcome}` (`CAPPED`, `NO_OWN_QUANTITY`, `STALE_SNAPSHOT`) | worker | `STALE_SNAPSHOT` acima de 1% em 24 h na revisão semanal | Mostra o corte de venda em ação e o instantâneo vencido (NFR2.11, NFR2.17). **Só no Prometheus.** |
| `hikaricp_connections_pending{pool="memetrade-worker-pool"}` (já existe) | worker | 0 em duas leituras seguidas no cenário de carga | Diagnóstico de `BUDGET_EXCEEDED` sistêmico (runbook, seção 10). |

As oito séries novas existem com valor 0 desde a subida do worker (`OwnQuantityMetricsIT`). A API expõe 0 delas.

### Coleta e publicação

| Ambiente | Como a métrica é lida | O que muda na U4 |
|---|---|---|
| Local e CI | `/actuator/prometheus` direto (`OwnQuantityMetricsIT`); as regras são testadas pelo `promtool` (T20) | Nada na infraestrutura |
| Nuvem, leitura | o sidecar `metrics-agent` lê as portas 8081 (`api`) e 8082 (`worker`) a cada 120 s | Nada |
| Nuvem, configuração do agente | `CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT`, gerados da lista para `api-worker` e `txsigner` | Uma revisão nova de cada família (`infrastructure-specification.md`, "Sidecar"). A do `txsigner` entra na próxima implantação regular dele e não é pré-condição da U4. |
| Nuvem, publicação | Só a lista de `app-metrics.tf` chega a `Eureka/App`. Os contadores viram delta por leitura e o gauge passa como está. | `reads` e `stuck_bots` no grupo `[[]]`; `unavailable` num grupo novo `[["reason"]]` |
| Nuvem, o que fica de fora | `real_cycle_sell_capped_total` | O filtro `keep` a descarta; ela fica só no Prometheus local |

## Alertas

| Alerta | Condição | Severidade | Encaminha para |
|---|---|---|---|
| `RealOpsOwnHoldingsUnavailable` (Prometheus) | `sum(increase(real_own_holdings_unavailable_total{reason=~"BUDGET_EXCEEDED\|READ_FAILED"}[10m])) / sum(increase(real_own_holdings_reads_total[10m])) > 0.05` por `15m` | `high` | fundador (único on-call), runbook seção 10 |
| `real_ops_own_holdings_unavailable` (CloudWatch) | `e1 = IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)` `> 0.05`, `period = 600`, 2 de 2, `notBreaching` | alta | tópico SNS único (`var.alarm_topic_arn`) |
| `RealOpsOwnHoldingsStuckBots` (Prometheus) | `max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0` | `high` | fundador, runbook seção 10 |
| `real_ops_own_holdings_stuck_bots` (CloudWatch) | `real_own_holdings_stuck_bots`, `Minimum`, `period = 300`, 2 de 2, `> 0`, `notBreaching` | alta | tópico SNS único |
| `metrics_absent` (já existe, `job = worker`) | nenhum `process_uptime_seconds` em 240 s, `breaching` | crítica | tópico SNS único. Cobre o worker parado, caso em que os dois alarmes acima ficam `OK`. |

### Regras do Prometheus (`infra/observability/real-ops-alerts.yml`)

As duas entram no grupo `real-ops`, depois de `RealOpsPnlReconciliationMismatch`, nesta ordem:

```yaml
      - alert: RealOpsOwnHoldingsUnavailable
        # 261121-paper-real-unico U4 (NFR2.12, founder's F2): systemic failure of the own-holdings read.
        expr: >
          sum(increase(real_own_holdings_unavailable_total{reason=~"BUDGET_EXCEEDED|READ_FAILED"}[10m]))
          / sum(increase(real_own_holdings_reads_total[10m])) > 0.05
        for: 15m
        labels: { severity: high }
        annotations:
          summary: >-
            The own holdings of the REAL bots are unavailable; see docs/runbooks/real-operations.md, section 10.
          threshold: "0.05"
          window_seconds: "600"
          for_seconds: "900"
          cloud_periods: "2"
```

```yaml
      - alert: RealOpsOwnHoldingsStuckBots
        # 261121-paper-real-unico U4 (NFR2.18, founder's F3): one bot or wallet stuck below the 5% of the ratio.
        expr: max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0
        labels: { severity: high }
        annotations:
          summary: >-
            A REAL bot has 3 or more own-holdings reads unavailable in a row; see docs/runbooks/real-operations.md,
            section 10.
          threshold: "0"
          window_seconds: "600"
          cloud_periods: "1"
```

- Com a flag desligada, a razão dá `NaN`, e `NaN > 0.05` é falso. O gauge fica em 0.
- `BOT_NOT_OWNED` e `STALE_SNAPSHOT` ficam fora do numerador.
- O `summary` dobrado (`>-`) junta as linhas com um espaço. O arquivo de teste do `promtool` repete o texto exato nas `exp_annotations`, e o `AlertRuleTestAnnotationsTest` confere que os dois são iguais.

### Gêmeos no CloudWatch (`real-ops.tf`)

Os atributos e as quatro `metric_query` estão em `infrastructure-specification.md`, "Mudanças no Terraform". A conta das janelas segue a correção do `project.md` (o agente lê a cada 120 s; alarme que não dispara com dado ausente usa períodos de pelo menos 180 s):

| Alarme | Janela do Prometheus | `cloud_periods` | `cloudWindow(w, false) × cloud_periods` | `period × evaluation_periods` | Leituras por período |
|---|---|---|---|---|---|
| razão | 600 s (com `for: 15m`) | 2 | 600 × 2 = 1.200 | 600 × 2 = 1.200 | cerca de 5 |
| bot preso | 600 s | 1 | 600 × 1 = 600 | 300 × 2 = 600 | 2 a 3 |

- Na razão, o `FILL` dos numeradores impede que um período sem ponto de um motivo esconda a falha do outro. O `IF` com `FILL(m3, 0)` dá 0 sem leituras, então a flag desligada ou nenhum bot REAL ativo nunca alarmam.
- No bot preso, o `Minimum` por período só passa do limite se todas as leituras do período forem maiores que 0. É o mesmo que o `min_over_time` da regra.
- **Prova antes da mainnet (R-05).** O teste offline confere o texto da expressão pela saída `alarm_expressions`. A prova de que o `IF`/`FILL` recebe ponto em todo período só é possível na devnet e é pré-condição da virada na mainnet (`cicd-pipeline.md`, Virada, passo 7).

### Paridade e testes das regras

| Teste | O que muda |
|---|---|
| `RealOpsAlarmParityTest` | `hasSize(14)` vira `hasSize(16)`. O registro `Rule` ganha `cloudPeriods` (anotação `cloud_periods`, 1 quando ausente). Entram dois testes novos, `theOwnHoldingsAlertIsARatioWithItsTwinInCloudWatch` e `theStuckBotsAlertHasItsTwinInCloudWatch` (`observability-design.md`). |
| `RealOpsOwnHoldingsAlertRuleIT` (T20, Failsafe) | `promtool test rules /rules/tests/real-ops-own-holdings-unavailable.test.yml` num contêiner com a imagem `prom/prometheus@sha256:...` lida do passo `Alert rules (promtool)` do `ci.yml`. Tem 10 casos. |
| `CiPromtoolDigestTest`, `AlertRuleTestAnnotationsTest` | São testes de unidade: um extrai o digest único do `ci.yml`, o outro compara as anotações do teste com as da regra. |
| `promtool check rules` do `ci.yml` | Sem mudança. `real-ops-alerts.yml` já está no laço e passa a conferir as 16 regras. |

Arquivo novo: `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml`, com `rule_files: [../real-ops-alerts.yml]` e `evaluation_interval: 1m`. Ele traz os casos 1 a 10 do `observability-design.md`.

### Seções novas do runbook (`docs/runbooks/real-operations.md`)

Elas entram depois da "9. Índice da migração 0023". Cada seção traz o escalonamento: o fundador é o único on-call, e um defeito vira issue no repositório com o `correlationId` do ciclo.

| Seção | Conteúdo |
|---|---|
| 10. Posição própria indisponível | **Os dois alarmes e os gêmeos.** O que significam: o bot não vende pelo ciclo naquele slot, e o dinheiro sai pelas condicionais, por "Vender tudo e sacar" e pelo saque, que não leem a atribuição.<br>**Razão:** achar o motivo no log (`event=REAL_OWN_HOLDINGS_UNAVAILABLE`, campo `reason`). Com `BUDGET_EXCEEDED` sistêmico, conferir `hikaricp_connections_pending`, a carga do RDS `app` e `ledger_real_epoch_fallbacks_total`. Com `READ_FAILED` persistente, abrir defeito.<br>**Bot preso:** achar os `WARN` com `streak` ≥ 3 e o `botId`. Uma carteira inteira com defeito aparece como 3 bots da mesma pessoa. Depois de uma reinicialização do worker, o gauge recomeça do zero, e o bot volta a aparecer em cerca de 25 min.<br>**`BOT_NOT_OWNED`:** sempre vira defeito.<br>**Se não resolver:** desligar a flag do worker (`cicd-pipeline.md`, "Desligar"). |
| 11. Virada da flag de quantidade própria | As pré-condições (inclusive a prova do alarme na devnet antes da mainnet), a mudança do `tfvars` do ambiente (F4), os passos de ligar e de desligar, o caminho manual do fundador enquanto o `deploy.yml` não existe, as três consultas só de leitura e o registro na nota da implantação (`cicd-pipeline.md`, "Virada da flag"). |
| 12. Migração 0026 ou 0027 barrada | O `55P03` do `db-migrate`; a transação que segurava `bot_cycle` em `pg_stat_activity`, sem nunca matar à força a do ciclo ou a da fila; a repetição da mesma implantação com o mesmo SHA; o índice `INVALID` da `0027` e a repetição; a trava do changelog presa (`cicd-pipeline.md`, "Falha da migração"). |
| 13. Revisão de `STALE_SNAPSHOT` | Toda semana e depois de cada implantação que liga a flag, rodar no Prometheus `sum(increase(real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}[24h])) / sum(increase(real_own_holdings_reads_total[24h]))`. Acima de 1%, ou com 3 `STALE_SNAPSHOT` seguidos de um bot (`event=REAL_OWN_SELL_STALE`), abrir um defeito por bot. |

As anotações `summary` das duas regras apontam para a seção 10.

## SLIs e SLOs

| SLI | Meta | Janela de medida |
|---|---|---|
| Saúde da leitura: `(BUDGET_EXCEEDED + READ_FAILED) / leituras` | ≤ 5%; acima disso por 15 min, alerta. Nenhum SLO operacional novo. | 10 min avaliados a cada 1 min (Prometheus); 2 × 600 s (CloudWatch) |
| Bots presos: `real_own_holdings_stuck_bots` | 0; acima de 0 por 10 min, alerta | 10 min (Prometheus); 2 × 300 s (CloudWatch) |
| Tempo da leitura: p99,9 do `RealBotHoldingsMeasurementIT` | ≤ 250 ms (portanto ≤ 0,1% de `BUDGET_EXCEEDED`) | mediana de 3 execuções no Build and Test; pré-condição da virada (NFR1.5) |
| `STALE_SNAPSHOT` / leituras | ≤ 1% | 24 h, revisão semanal (NFR2.17) |

Nenhum histograma novo: o agente descarta histogramas, e o prazo aparece como `BUDGET_EXCEEDED` (NFR8.14).

## Logs e rastreamento

| Item | Decisão |
|---|---|
| Agregação | Os grupos `/ecs/...` do `api-worker` continuam, com a mesma retenção. Nenhuma consulta salva nova (`log-queries.tf` continua com 5). |
| Eventos | `REAL_OWN_HOLDINGS_UNAVAILABLE` (`WARN`, ou `ERROR` com `BOT_NOT_OWNED`), `REAL_OWN_SELL_STALE`, `REAL_OWN_SELL_NO_QUANTITY`, `REAL_OWN_SELL_CAPPED` (`INFO`) e `REAL_OWN_HOLDINGS_STREAKS_FULL` (`WARN`); em inglês, com `correlationId` (o `cycleId`) e o uuid do bot (NFR3.9). |
| Proibido no log | Endereço de carteira, assinatura, token, `Authorization`, nonce e SQL (`OwnQuantityLogRedactionTest`). |
| Rastreamento | Nenhuma biblioteca nova. O `cycleId` no MDC liga o log, `bot_cycle` e a decisão gravada (NFR3.10). |

## Painéis

Nenhum painel novo nem mudança nos 3 painéis de `dashboards.tf` (NFR2.13). A asserção `length(dashboard_names) == 3` do teste offline continua igual.

## Sources

- `infrastructure-design-questions.md`: Q2 e o resumo confirmado [Q2].
- `reviews/review-01.md` (R-03, R-05) e a decisão do fundador F4 de 2026-11-24.
- `construction/u4-own-quantity-cycle/nfr-design/observability-design.md` (contadores, gauge da F3, regras, gêmeos com `FILL`/`IF`, paridade `hasSize(16)`, T20 com 10 casos, `STALE_SNAPSHOT`, SLI), `reliability-design.md` (runbook, seções 10 a 13), `logical-components.md` (A1, A17, A18), `scalability-design.md` (custo).
- `construction/u4-own-quantity-cycle/nfr-requirements/observability-requirements.md` (NFR1.13, NFR2.10 a NFR2.13, NFR2.16, NFR2.17, NFR3.9, NFR3.10).
- `construction/u2-real-bot-pnl/infrastructure-design/monitoring-design.md` (formato, coleta e publicação).
- Código lido: `infra/observability/real-ops-alerts.yml` (grupo `real-ops`, 14 regras), `infra/terraform/modules/observability/real-ops.tf`, `app-metrics.tf` (`metrics_absent`, inventário, `alarm_metric_reads`), `modules/services/metrics-agent.tf`, `.github/workflows/ci.yml` (passo `Alert rules (promtool)`), `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`, `apps/api/src/test/java/com/memetrade/infra/CloudWatchWindows.java`, `docs/runbooks/real-operations.md`.
- `aidlc/spaces/default/memory/project.md` (CloudWatch Agent a 120 s; janelas de 240 s e 180 s) e `team.md` (fundador único on-call).

## Assumptions & Open Questions

1. A prova real do alarme de expressão é **pré-condição da virada na mainnet** (R-05; `cicd-pipeline.md`, Virada, passo 7). Na devnet, com a flag ligada no worker, o fundador confere por 1 hora que `real_own_holdings_reads_total` tem ponto em todo período de 600 s, que um contador parado publica delta 0 e que `e1` dá um valor em todo período (0 sem falhas). O resultado vai para a nota da implantação. Sem essa prova, a mainnet não liga.
2. [assumption] O `promtool` da imagem fixada no `ci.yml` aceita `exp_annotations` (o recurso existe desde o Prometheus 2.5).
3. Os 20 minutos de despejo e o teto de 10.000 entradas do mapa do gauge vêm do NFR Design. O monitoramento não depende deles além do atraso de cerca de 25 min depois de uma reinicialização, que o runbook registra.
