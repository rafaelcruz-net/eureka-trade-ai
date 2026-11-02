# Desenho de monitoramento — U4 Descoberta e avaliação

Implementação local do [observability-design.md](../nfr-design/observability-design.md): métricas em `/actuator/prometheus` (porta 8081), logs JSON na saída padrão do contêiner (`docker compose logs api`), saúde no Actuator. Sem Prometheus, Grafana nem alertas no beta local.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `market_source_status{source}` | `MarketCollector` | 3 (`FAILED`) em todas as fontes | Lista sem tokens (BR1.4) |
| `market_source_last_success_timestamp_seconds{source}` | `MarketCollector` | mais de 60 s atrás com fonte `OK` | Frescor (NFR1.2) |
| `market_collection_duration_seconds{source}` p95 | `MarketCollector` | > 10 s no caminho sem falha | NFR2.1 |
| `market_collection_total{result="SKIPPED_LOCKED"}` | `MarketCollector` | crescendo a cada intervalo | Coleta mais lenta que o intervalo |
| `risk_recalculation_duration_seconds` p95 | `RiskRecalculator` | > 10 s | NFR2.2 |
| `risk_assessments{status="UNAVAILABLE"}` | `RiskRecalculator` | > 50% dos tokens | Cobertura caindo ou fonte fora |
| `market_history_samples` | `MarketHistoryCleanup` | > 4,8 milhões | NFR2.5 |
| `http_server_requests_seconds{uri="/api/v1/scanner/tokens"}` p95 | Spring | > 1 s | NFR3.1 |
| `http_server_requests_seconds{uri="/api/v1/tokens/{chain}/{mint}/assessment"}` p95 | Spring | > 500 ms | NFR3.2 |
| `hikaricp_connections_pending` | HikariCP | > 0 por mais de 10 s | Pool de 20 insuficiente (Q1) |
| `scanner_rate_limited_total{route}` | `PersonRouteRateLimiter` | crescimento contínuo | Abuso ou limite baixo |

Os limiares são lidos no relatório do script de carga e no teste de integração, não por alerta automático.

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Nenhum alerta automático no beta local | — | — | — |
| Candidatos para a hospedagem | Todas as fontes `FAILED` > 2 min; fonte `DEGRADED` > 10 min; recálculo sem sucesso > 5 min; p95 da lista > 1 s por 10 min | página (os dois primeiros), ticket (os demais) | A definir com a hospedagem |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Latência da lista (p95) | ≤ 1 s com 20 usuários e 2 000 tokens | Execução de 10 minutos do script de carga |
| Latência do detalhe (p95) | ≤ 500 ms | Mesma execução |
| Frescor com fonte operante | idade ≤ 60 s com intervalo de 30 s | Teste de integração com relógio injetável |
| Recálculo em dia | último sucesso há ≤ 5 min | Saúde `riskRecalculation` contínua |

## Logs & Tracing

- **Logs:** JSON na saída padrão, campos da tabela de [observability-design.md](../nfr-design/observability-design.md); filtro de mascaramento Base58 da U1; `LogScanIT` da U2 cobre a suíte da U4. Retenção: a do Docker local (`json-file` com 3 arquivos de 10 MB, configuração já existente).
- **Tracing:** sem rastreamento distribuído (um processo); o `correlationId` liga requisição, consultas e logs.
- **Painel:** nenhum painel dedicado; o relatório do script de carga (Markdown em `docs/perf/`) mostra latências, durações e espera do pool.
