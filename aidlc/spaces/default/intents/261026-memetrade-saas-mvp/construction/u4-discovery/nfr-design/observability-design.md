# Desenho de observabilidade — U4 Descoberta e avaliação

Como a U4 cumpre [observability-requirements.md](../nfr-requirements/observability-requirements.md), no padrão da U1 e da U2: Micrometer com saída Prometheus em `/actuator/prometheus` (porta de gestão 8081), logs JSON com `correlationId`, saúde no Actuator. Sem alertas automáticos no beta local.

## Métricas (NFR9.2 a NFR9.5)

| Métrica | Tipo | Rótulos | Atualizada em |
|---|---|---|---|
| `market_source_status` | gauge (0 PENDING, 1 OK, 2 DEGRADED, 3 FAILED) | `source` | fim de cada coleta |
| `market_collection_total` | counter | `source`, `result` (`OK`, `DEGRADED`, `FAILED`, `SKIPPED_LOCKED`) | fim de cada coleta |
| `market_collection_duration_seconds` | timer | `source` | fim de cada coleta |
| `market_collection_retries_total` | counter | `source` | cada nova tentativa |
| `market_collection_discarded_total` | counter | `source`, `reason` (`INVALID_FIELD`, `TOO_MANY`) | coleta |
| `market_source_last_success_timestamp_seconds` | gauge | `source` | coleta bem-sucedida |
| `risk_recalculation_total` | counter | `result` | fim do recálculo |
| `risk_recalculation_duration_seconds` | timer | — | fim do recálculo |
| `risk_assessments` | gauge | `status`, `band` | fim do recálculo |
| `market_history_cleanup_deleted_total` | counter | — | limpeza |
| `market_history_cleanup_duration_seconds` | timer | — | limpeza |
| `market_history_samples` | gauge | — | limpeza |
| `scanner_rate_limited_total` | counter | `route` | cada `429` |
| `scanner_excluded_by_unknown` | distribution summary | — | cada consulta da lista |
| `http_server_requests_seconds` | timer (Spring) | `uri` (modelo da rota), `status` | cada requisição |

Rótulos permitidos só de listas fechadas: `source` (identificadores configurados), `result`, `reason`, `route`, `status`, `band`. Um teste lê `/actuator/prometheus` depois da suíte e falha se algum rótulo tiver mint, chain, carteira, pessoa ou tenant (NFR9.5).

## Logs (NFR9.6 a NFR9.8)

| Evento | Nível | Campos |
|---|---|---|
| Mudança de estado de fonte | `INFO` | `source`, `from`, `to`, `failureCategory` |
| Coleta `DEGRADED` | `WARN` | `source`, `retries`, `discarded` por motivo |
| Coleta `FAILED` | `ERROR` | `source`, `failureCategory`, `exceptionClass` (nunca a mensagem nem o corpo da resposta) |
| Fim de recálculo | `INFO` | `durationMs`, `evaluated`, `rewritten`, `unavailable` |
| Fim de limpeza | `INFO` | `durationMs`, `deleted` |
| Ativação de pesos | `INFO` | `fromVersion`, `toVersion`, `actor` mascarado |
| `429` | `INFO` | `route` (sem pessoa) |

Cada execução agendada abre um `correlationId` próprio no MDC (padrão da U2); as requisições usam o da U1. O filtro de mascaramento Base58 da U1 continua no formatador; a varredura `LogScanIT` da U2 inclui os testes da U4.

## Saúde (NFR9.1)

Indicadores `marketSources` e `riskRecalculation` conforme [reliability-design.md](reliability-design.md): nunca `DOWN`, `DEGRADED` com HTTP 200, fora do grupo de prontidão.

## SLI e SLO do beta local

| SLI | Alvo (beta local) | Medido por |
|---|---|---|
| Latência da lista (p95) | ≤ 1 s | `http_server_requests_seconds{uri="/api/v1/scanner/tokens"}` no script de carga |
| Latência do detalhe (p95) | ≤ 500 ms | idem, rota do detalhe |
| Frescor com fonte operante | idade ≤ 60 s com intervalo de 30 s | teste de integração (NFR1.2) |
| Recálculo em dia | último sucesso há ≤ 5 min | `riskRecalculation` |

Metas de disponibilidade e alertas ficam para a hospedagem (NFR10.1); os alertas candidatos estão em [observability-requirements.md](../nfr-requirements/observability-requirements.md).
