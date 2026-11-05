# Desenho de monitoramento — U6 Barreira de limites

Monitoramento da Unit `u6-risk-control` no ambiente local, implementando [observability-design.md](../nfr-design/observability-design.md). Só métricas de saúde e de erro, sem alertas no beta local.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `http_server_requests_seconds` com `uri` da rota C21 | Micrometer | p95 ≤ 1 s no cenário `u6` | NFR3.1 |
| Tempo de `OrderGate.evaluate` | Medido pelo ciclo de teste do perfil `loadtest` e gravado no relatório de carga | p95 ≤ 250 ms | NFR2.1 |
| Tempo de cada lote do expurgo | Log da rodada e relatório do cenário `u6` | ≤ 100 ms por lote; p95 da avaliação com expurgo no máximo 10% acima de sem expurgo | NFR2.5 |
| `riskcontrol.evaluation.errors` | Micrometer, rótulo `cause` | Zero no cenário `u6` e nos testes sem falha injetada | NFR9.2 |
| `riskcontrol.purge.errors` | Micrometer | Zero | NFR9.2 |
| Taxa de 5xx na rota C21 | Micrometer | Zero no cenário `u6` | NFR9.2 |
| `health` `riskcontrol` | Actuator, a partir de `risk_purge_run` | `UP`; `DOWN` com `PURGE_STALE` ou `PURGE_FAILED` | NFR9.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Nenhum no beta local | — | — | Definidos com a hospedagem |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 de `evaluate` | ≤ 250 ms | Cenário `u6`, 10 minutos, 60 bots |
| p95 da rota C21 | ≤ 1 s | Cenário `u6`, 10 minutos, 20 usuários, 10.000 recusas por bot |
| Lote do expurgo | ≤ 100 ms em todos | Cenário `u6` com expurgo concorrente |
| Rodadas do expurgo com sucesso | 100% | Toda rodada diária |

## Logs & Tracing

| Item | Configuração |
|---|---|
| Correlação | `CorrelationIdFilter` da U1 na rota C21; no ciclo, o identificador que a U5 liga no MDC |
| Formato | JSON, mesmo layout das outras Units |
| Conteúdo | Só identificadores (`rejectionId`, `botId`, `decisionId`, `evaluationId`); nunca carteira, token ou cabeçalho `Authorization`; `LogScanIT` cobre um ciclo de teste com recusa |
| Tracing distribuído | Não usado no beta local (um processo por requisição) |
| Painéis | Nenhum; o relatório `docs/perf/u6-risk-control-<data>.md` é a referência |
