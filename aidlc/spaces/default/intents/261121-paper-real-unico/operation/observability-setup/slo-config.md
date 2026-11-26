# SLOs, SLIs e orçamentos de erro — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Observability Setup, Q2 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado.** Não existe ambiente na nuvem [Q1]. Os SLOs abaixo são as metas já aprovadas nas Units, escritas com percentual e janela [Q2]. **Nenhuma meta nova.** Onde a nuvem não tem sinal, o SLO é medido onde a meta já é conferida hoje (CI, cenário de carga ou medição do fundador na devnet), e isso fica escrito em cada linha.

Este arquivo soma ao `slo-config.md` do `261113-trading-real-bot`. Herdados sem mudança: os três SLOs com alarme de consumo (disponibilidade do api 99,5%, disponibilidade do TxSigner 99,5% e frescor dos dados de mercado 99%, todos em 30 dias corridos), os seis alarmes `slo-*-burn-1h` e `-6h`, a fórmula da taxa de consumo e as metas de p95 da seção 4 daquele arquivo. A disponibilidade do api pelo balanceador continua cobrindo as rotas de P&L deste intent.

## 1. SLOs deste intent

| ID | Unit | SLO | Meta | Janela | Orçamento de erro | Estado hoje |
|---|---|---|---|---|---|---|
| **SLO-PNL-1** | U1, U3 | Latência das leituras do P&L: C4 `GET /api/v1/pnl/by-mode`, C5 `GET /api/v1/bots/{botId}/pnl/lines`, C6 `GET /api/v1/pnl/compare` | **95%** das leituras em **≤ 1.000 ms** (p95) | 30 dias corridos em operação; por execução no cenário de carga (600 s depois de 60 s de aquecimento) | 5% das leituras acima de 1.000 ms | IT da CI `Met` (C4 181 ms; U3 até 148 ms no `09de0a8`). Cenário `pnl-by-mode` **`Unverified`** (U2-NFR1.9 `Not Met`: falta o cenário no `load-test.yml`). Na nuvem, sem SLI por rota (OBS-G5) |
| **SLO-PNL-2** | U1, U2, U3 | Blocos REAL da visão por modo entregues (sem `UNAVAILABLE` por `TIMEOUT` do prazo de 800 ms) | **99,5%** das respostas 200 de C4 | 30 dias corridos; por execução no cenário de carga | 0,5% das respostas 200 de C4 (D-NFR1) | `Unverified` (mesmo cenário). Na nuvem, só a contagem pelo log |
| **SLO-U2-1** | U2 | Leituras do P&L REAL reconciliadas com o ledger (sem `RECONCILIATION_MISMATCH`) | **99,9%** das leituras REAL | 30 dias corridos | 0,1% das leituras; **e toda divergência é um defeito**, com ou sem orçamento | CI verde (cenários determinísticos, `RealPnlPropertyTest`). Na nuvem, numerador em `Eureka/App`; alarme na primeira divergência |
| **SLO-U2-2** | U2 | Leitura de atribuição no caminho `PNL` | **95%** das leituras em **≤ 500 ms** (p95), e blocos `TIMEOUT` dentro do SLO-PNL-2 | por execução no cenário de carga; 30 dias no Prometheus local | 5% das leituras acima de 500 ms | `Unverified` (cenário não executado). Caminhos `GATE` e `CYCLE`: p95 ≤ 250 ms, `Met` (18 ms) |
| **SLO-U2-3** | U2 | Barreira REAL: avaliação de compra (sem a espera pela trava) | **95%** das avaliações em **≤ 500 ms** (teto da compra, decisão do fundador de 2026-11-23); venda ≤ 30 ms | mediana de 3 execuções do `RealRiskMeasurementIT` no Build and Test; 30 dias em operação quando houver sinal | 5% das avaliações acima do teto | `Met` na CI (compra 15 ms, venda 4 ms). Na nuvem, sem sinal (OBS-G1; alarme desligado com limite antigo, OBS-G7) |
| **SLO-U4-1** | U4 | Tempo da leitura da posição própria (`RealBotHoldings`) | **99,9%** das leituras em **≤ 500 ms** (p99,9), ou seja, `BUDGET_EXCEEDED` ≤ **0,1%** das leituras (decisão F6) | medida oficial: 10.000 leituras × 3, Linux, com ciclos; em operação, 30 dias corridos | 0,1% das leituras com `BUDGET_EXCEEDED` | **`Not Met` na semente B** (62,7% cortadas com 500 ms) **até a decisão F7**. Semente A cumpre (0/300). Medida oficial `Unverified`. **A flag `memetrade.real.own-quantity-sell` está desligada** em todos os ambientes: em operação não há leitura, e o SLO fica dormente |

Os SLOs são de piloto: o fundador é o único usuário do REAL na mainnet até a abertura aos convidados (`team.md`, Deployment). Nenhum é SLA.

## 2. SLIs: consultas

### SLO-PNL-1: latência das leituras do P&L

Ambiente local, CI e cenário de carga (Prometheus; `http.server.requests` tem `percentiles-histogram` ligado em `application.yml`). O `uri` é o modelo da rota do Spring:

```promql
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{
  uri=~"/api/v1/pnl/by-mode|/api/v1/bots/\\{botId\\}/pnl/lines|/api/v1/pnl/compare", status="200"}[5m])))
```

- O cenário de carga (`load-test.mjs`) mede também o p95 por leitura rotulada no cliente (C4 `RANGE` 30 e 366 dias, C5 primeira e seguinte, C6 `ALL`, `RANGE` 30 e 366 dias), porque o histograma por `uri` não separa os períodos (U3, NFR1.6).
- Os buckets padrão do Micrometer não têm um limite exato em 1,0 s. Por isso o SLI é o quantil, e não a razão `le="1.0"` / total.
- **Na nuvem:** sem SLI por rota (OBS-G4 e OBS-G5). Indicadores possíveis: `AWS/ApplicationELB TargetResponseTime` p95 do grupo-alvo do api (todas as rotas, só como sinal geral) e a leitura de C4 pelo fundador na devnet (100 pedidos, limite de 1.000 ms, registrada em `docs/perf/`).

### SLO-PNL-2: blocos REAL entregues

```promql
sum(increase(pnl_mode_block_unavailable_total{market="REAL", cause="TIMEOUT"}[30d]))
/ sum(increase(http_server_requests_seconds_count{uri="/api/v1/pnl/by-mode", status="200"}[30d]))
```

Meta: ≤ 0,005. As outras causas (`REJECTED`, `ERROR`, `TOO_MANY_ORDERS`) devem ser 0 no cenário de carga. **Na nuvem:** a contagem vem do log, pela consulta salva `pnl-blocos-indisponiveis` (`log-queries.md`). O denominador não está no CloudWatch; a razão só existe no Prometheus.

### SLO-U2-1: reconciliação do P&L REAL

```promql
sum(increase(pnl_mode_block_unavailable_total{market="REAL", cause="RECONCILIATION_MISMATCH"}[30d]))
/ sum(increase(http_server_requests_seconds_count{uri="/api/v1/pnl/by-mode", status="200"}[30d]))
```

O numerador usa os blocos de C4, porque `pnl_real_reconciliation_mismatches_total` conta uma vez por **verificação** que falhou (até 8 por leitura) e não por leitura. **Na nuvem**, só o contador de verificações chega ao CloudWatch. Ele é um limite superior do número de leituras divergentes, então a medida fica conservadora:

```bash
# Verificações divergentes nos últimos 30 dias (meta operacional: 0; qualquer valor > 0 já disparou o alarme)
aws cloudwatch get-metric-statistics --namespace Eureka/App --metric-name pnl_real_reconciliation_mismatches_total \
  --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" --period 2592000 --statistics Sum
```

### SLO-U2-2: atribuição no caminho `PNL`

O histograma tem um limite em 0,5 s (`pnl_real_attribution_read_seconds`: 0,1; 0,25; 0,5; 0,8; 1 e 2 s), então o SLI pode ser a razão exata:

```promql
sum(increase(pnl_real_attribution_read_seconds_bucket{path="PNL", le="0.5"}[10m]))
/ sum(increase(pnl_real_attribution_read_seconds_count{path="PNL"}[10m]))
```

Meta: ≥ 0,95. Taxa de `TIMEOUT`: `sum(increase(pnl_real_attribution_read_seconds_count{path="PNL", outcome="TIMEOUT"}[10m])) / sum(increase(pnl_real_attribution_read_seconds_count{path="PNL"}[10m]))`. **Na nuvem:** sem SLI (histograma descartado; OBS-G5). As falhas aparecem no log `event=REAL_ATTRIBUTION_READ_FAILED`.

### SLO-U2-3: barreira REAL de compra

```promql
histogram_quantile(0.95, sum by (le) (rate(real_risk_evaluation_seconds_bucket{side="BUY"}[10m])))
```

Meta: ≤ 0,5. A decisão do Build and Test é a mediana de 3 execuções do `RealRiskMeasurementIT` (U2-NFR1.8). **Na nuvem:** depende do summary da OBS-G1 (`real_risk_evaluation_p95_seconds{side="BUY",quantile="0.95"}`), que o código ainda não publica.

### SLO-U4-1: leitura da posição própria

É o único SLO deste intent com SLI completo na nuvem, porque numerador e denominador estão na lista do agente:

```bash
# Razão de BUDGET_EXCEEDED nos últimos 30 dias (meta: <= 0,001). Sem leituras (flag desligada), o resultado é 0 pelo IF.
aws cloudwatch get-metric-data --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" \
  --metric-data-queries '[
    {"Id":"m1","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_unavailable_total","Dimensions":[{"Name":"reason","Value":"BUDGET_EXCEEDED"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
    {"Id":"m3","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_reads_total"},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
    {"Id":"sli","Expression":"IF(FILL(m3, 0) > 0, FILL(m1, 0) / m3, 0)","ReturnData":true}]'
```

No ambiente local, com o Prometheus:

```promql
sum(increase(real_own_holdings_unavailable_total{reason="BUDGET_EXCEEDED"}[30d]))
/ sum(increase(real_own_holdings_reads_total[30d]))
```

A medida de liberação é a do Build and Test (`RealBotHoldingsMeasurementIT`, p99,9 e `budgetExceeded`):

```bash
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml -Dit.test=RealBotHoldingsMeasurementIT \
  -Du4.measure.reads=10000 -Du4.measure.cycles=100 verify   # 3 vezes, Linux; mediana do p99,9 <= 500 ms
```

Indicadores ao lado, sem meta de SLO: a razão `(BUDGET_EXCEEDED + READ_FAILED) / leituras` (alarme acima de 5%), o gauge `real_own_holdings_stuck_bots` (alarme acima de 0) e `STALE_SNAPSHOT` / leituras ≤ 1% em 24 h (revisão semanal; na nuvem pela consulta `real-venda-propria-cortes`, porque `real_cycle_sell_capped_total` fica só no Prometheus).

## 3. Orçamento de erro e alarmes

| SLO | Alarme na nuvem | Dispara com | Abaixo da quebra? |
|---|---|---|---|
| SLO-PNL-1 | nenhum (OBS-G5) | — | não se aplica na nuvem; o portão é o cenário de carga. A disponibilidade do api tem os alarmes de consumo herdados |
| SLO-PNL-2 | nenhum (OBS-G5) | — | idem; contagem pelo log |
| SLO-U2-1 | `real-ops-pnl-reconciliation-mismatch` | a primeira divergência, em 240 s | **sim**: o orçamento é 0,1% das leituras do mês |
| SLO-U2-2 | nenhum | — | não se aplica na nuvem |
| SLO-U2-3 | `real-risk-slow` (desligado, OBS-G1) | p95 > 150 ms por 10 min (limite antigo, OBS-G7) | sim quando ligado: 150 ms < 500 ms |
| SLO-U4-1 | `real-ops-own-holdings-unavailable` | razão > 5% por 2 × 600 s | **sim** para consumo rápido: taxa 50, gasta ≈ 2,3% do orçamento antes de disparar. Consumo lento (0,6% a 5%) não dispara: proposta OBS-G6 (`alarms.md`, 5.2) |
| SLO-U4-1 | `real-ops-own-holdings-stuck-bots` | 1 bot com 3+ leituras indisponíveis por 10 min | sim; cobre um bot só, que a razão não vê |

Conta da razão de 5% contra o SLO de 0,1%:

```
taxa de consumo             = 0,05 / 0,001 = 50
orçamento gasto até alarmar = 50 × 1.200 s / 2.592.000 s ≈ 2,3%
tempo até esgotar a 50×     = 720 h / 50 = 14,4 h
alarme de consumo lento (proposto): taxa 6 em 6 h → limite 0,006; gasta 5% do orçamento (mesma fórmula do 261113)
```

## 4. Política do orçamento de erro

Herdada do `slo-config.md` do 261113 (seção 3: > 50% ritmo normal; 25% a 50% acompanhar; < 25% só correções; esgotado, congelar), com estas regras deste intent:

| SLO | Regra própria |
|---|---|
| SLO-U2-1 | Toda divergência abre defeito com teste de regressão (`team.md`, Defeitos), mesmo com orçamento sobrando. Não se compensa o ledger por causa dela (runbook, seção 8). |
| SLO-U4-1 | **Enquanto a medida oficial não cumprir a meta, o orçamento conta como esgotado: a flag fica desligada.** Para ligá-la: medida por passo, decisão F7 (consulta enxuta de S4, cache por bot validado pela marca, ou orçamento maior), medida oficial 10.000 × 3 dentro da meta (`test-results.md`, seção 10; `cd-config.md`, seção 6, condição 1). Depois da virada, se o orçamento esgotar, desligar a flag do worker é a contenção (`rollback-runbook.md`, R2); o dinheiro continua saindo pelas condicionais, por "Vender tudo e sacar" e pelo saque. |
| SLO-PNL-1, SLO-PNL-2, SLO-U2-2 | O cenário de carga `pnl-by-mode` é o portão; uma execução acima da meta impede a promoção do SHA para a mainnet (`cd-config.md`, etapa 2). |

Revisão: manual, todo mês, no estágio de Performance Monitoring, como no 261113.

## 5. Sinais de ouro do delta

| Componente | Latência | Tráfego | Erros | Saturação |
|---|---|---|---|---|
| Leituras do P&L (api) | `TargetResponseTime` do balanceador (geral); p95 por `uri` só local | `RequestCount` (geral) | log dos blocos `UNAVAILABLE` e `PNL_*`; `real-ops-pnl-reconciliation-mismatch` | pool `pnl-mode-block` (8 conexões do pool principal); RDS `app` (D-INF2) |
| Posição própria (worker) | `BUDGET_EXCEEDED` (prazo de 500 ms) | `real_own_holdings_reads_total` | `real_own_holdings_unavailable_total{reason}`; `real_own_holdings_stuck_bots` | `hikaricp_connections_pending` (só local); RDS `app` |

Saúde: `metrics-absent-api` e `metrics-absent-worker` (herdados). Erro: as métricas acima. Assim cada componente novo tem ao menos um sinal de saúde e um de erro, como pede a regra da fase Operation.

## 6. Conferência depois do primeiro apply da devnet

```bash
# 1. As métricas do SLO-U4-1 existem (com valor 0 enquanto a flag estiver desligada)
aws cloudwatch list-metrics --namespace Eureka/App --metric-name real_own_holdings_reads_total
aws cloudwatch list-metrics --namespace Eureka/App --metric-name real_own_holdings_unavailable_total \
  --query 'Metrics[].Dimensions[0].Value'          # esperado: BUDGET_EXCEEDED, READ_FAILED, BOT_NOT_OWNED

# 2. Leitura do fundador na devnet para o SLO-PNL-1 (100 pedidos a C4, limite de 1.000 ms), registrada em docs/perf/.
#    O procedimento é o do cd-config.md, etapa 2; este estágio não cria script novo.
```

## Sources

- `observability-setup-questions.md` (Q2, resumo confirmado) [Q2] [Q1].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/observability-setup/slo-config.md` (SLOs herdados, fórmula da taxa de consumo, política do orçamento).
- `construction/u1-mode-skeleton/nfr-design/observability-design.md` e `infrastructure-design/monitoring-design.md` (NFR1.14: SLI por `uri`, sem SLO operacional novo).
- `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md` (D-NFR1, NFR1.3, NFR1.5, NFR1.8, NFR1.12), `observability-requirements.md` (NFR1.23, NFR1.24) e `infrastructure-design/monitoring-design.md` (SLIs e SLOs).
- `construction/u3-pnl-views/infrastructure-design/monitoring-design.md` (SLIs das 4 rotas; NFR1.5 e NFR1.6).
- `construction/u4-own-quantity-cycle/nfr-design/observability-design.md` (SLI NFR1.13, premissa F6) e `infrastructure-design/monitoring-design.md`.
- `construction/build-and-test/test-results.md` (seções 5, 9.3, 9.4 e 10).
- `operation/deployment-pipeline/cd-config.md` (seção 6, condições 1 e 3; etapa 2) e `rollback-runbook.md` (R2).
- Código lido: `apps/api/src/main/resources/application.yml` (`percentiles-histogram` de `http.server.requests`), `infra/terraform/modules/observability/app-metrics.tf`, `real-ops.tf`, `real-risk.tf`, `infra/observability/real-risk-alerts.yml`.
- `.claude/knowledge/aidlc-operations-agent/slo-sli-patterns.md` (janela corrida, orçamento, taxa de consumo).
- `aidlc/spaces/default/memory/project.md` (Mandated: REAL e PAPER separados; correção do CloudWatch Agent) e `team.md` (Deployment, Testing Posture: Defeitos).

## Assumptions & Open Questions

1. [assumption] As metas em percentual traduzem os p95 e o p99,9 já aprovados (p95 ≤ X ⇔ 95% das leituras ≤ X). Nenhuma meta mudou [Q2].
2. [assumption] A janela de 30 dias corridos vale para todos os SLOs em operação, como no 261113. Para os SLOs medidos só no cenário de carga, a janela é a execução (600 s).
3. [assumption] O SLO-U2-1 em 99,9% existe só para cumprir a regra de "percentual e janela"; a política real é "toda divergência é defeito", e o alarme dispara na primeira.
4. Aberta: OBS-G5 (sem SLI de latência do P&L na nuvem). Recomendação: aceitar no piloto e rever em Performance Monitoring. Dono: fundador.
5. Aberta: F7 e a medida oficial da U4. Até lá, SLO-U4-1 `Not Met` na semente B e flag desligada. Dono: fundador.
6. Aberta: aplicar o cenário `pnl-by-mode` no `load-test.yml` (U2-NFR1.9). Sem ele, SLO-PNL-1, SLO-PNL-2 e SLO-U2-2 ficam `Unverified`. Dono: o responsável (`performance-validation`).
7. [assumption] Os SLIs em PromQL valem para o Prometheus local e para o cenário de carga; não existe Prometheus de longa retenção, então uma janela de 30 dias em PromQL só é possível onde houver essa retenção.
