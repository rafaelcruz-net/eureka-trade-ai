# Desenho de monitoramento — U7 Ledger e P&L

Monitoramento da Unit `u7-ledger-pnl` no ambiente local, implementando [observability-design.md](../nfr-design/observability-design.md). Sem métricas de negócio próprias e sem alertas no beta local.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `http_server_requests_seconds` com `uri` das rotas C5 | Micrometer | p95 ≤ 1 s no cenário `u7` | NFR3.2, NFR3.3 |
| Tempo da chamada `PaperExecution.execute` e `closePositions` | Medido pelo ciclo de teste do perfil `loadtest` e gravado no relatório de carga | p95 ≤ 250 ms | NFR3.1 |
| Duração de cada rodada de marcação e de fechamento pendente | Log da rodada e relatório do cenário `u7-jobs` | Nenhuma acima de 10 s em 60 rodadas | NFR3.5 |
| Taxa de 5xx nas rotas C5 | Micrometer | Zero no smoke e na carga | NFR9.2 |
| `health` `ledgerJobs` | Actuator | `UP`; `DEGRADED` com falha, atraso ou divergência | NFR9.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Nenhum no beta local | — | — | Definidos com a hospedagem |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 de `execute` | ≤ 250 ms | Cenário `u7`, 10 minutos, com as tarefas ativas |
| p95 de P&L e ledger | ≤ 1 s | Cenário `u7`, 10 minutos, 20 usuários |
| Rodada das tarefas | ≤ 10 s em todas | Cenário `u7-jobs`, 60 rodadas |
| Pessoas com divergência | 0 | Cada rodada de reconciliação |

## Logs & Tracing

| Item | Configuração |
|---|---|
| Correlação | `CorrelationIdFilter` da U1 nas rotas C5 |
| Rodadas | Início, fim, contas tratadas, contas puladas por trava, falha; `WARN` com contagem quando há divergência |
| Conteúdo | Só identificadores; sem valores, endereços nem dados de mercado; `LogScanIT` ampliado |
| Rótulos | `MetricsLabelsIT` exercita as rotas da U7 |
| Rastreamento distribuído | Não usado |
| Painéis | Nenhum no beta local |
