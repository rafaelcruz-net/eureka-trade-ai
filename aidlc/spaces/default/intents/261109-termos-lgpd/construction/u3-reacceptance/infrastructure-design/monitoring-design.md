# Desenho de monitoramento — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa `construction/u3-reacceptance/nfr-design/observability-design.md` no ambiente local (actuator, Prometheus, logs JSON). A ligação com o produto está em `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/observability-setup/`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `memetrade_terms_reacceptance_blocked_total` | API | picos logo depois de publicar uma versão | quantas requisições a trava barrou |
| `memetrade_terms_acceptances_total{source}` | API | cresce depois de uma versão nova | adesão ao novo aceite |
| `memetrade_terms_reacceptance_check_seconds` (histograma) | API | p95 ≤ 5 ms | custo da trava (NFR3.2) |
| `http_server_requests_seconds` em `/api/v1/terms/acceptances` | API | p95 ≤ 500 ms | NFR3.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| `TERMS_CHECK_SLOW` | p95 de `memetrade_terms_reacceptance_check_seconds` acima de 5 ms por 15 minutos | Média | responsável do projeto; local: métrica |
| `HTTP_5XX_RATE` (existente) | inclui a trava e o aceite | Alta | igual ao produto |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Latência do aceite | p95 ≤ 500 ms | cenário de carga `reacceptance` |
| Latência da trava | p95 ≤ 5 ms | cenário de carga `reacceptance` |

## Logs & Tracing

- A trava não registra log por bloqueio.
- O aceite registra `INFO` só com a versão e o `source`.
- Uma falha de banco na trava registra só a classe da exceção.
- Não há tracing novo.
