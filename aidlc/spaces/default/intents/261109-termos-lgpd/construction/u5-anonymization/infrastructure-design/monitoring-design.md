# Desenho de monitoramento — U5 `u5-anonymization`

Conversa: português (Brasil). Implementa `construction/u5-anonymization/nfr-design/observability-design.md` no ambiente local (actuator, Prometheus, logs JSON). A ligação com o produto fica em `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/observability-setup/`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `memetrade_accounts_anonymized_total` | API (depois do commit) | acompanhar | volume de exclusões atendidas |
| `memetrade_account_anonymization_failures_total{reason}` | API (depois do rollback); pré-registrado em zero na partida para `reason=LOCK_TIMEOUT` e `reason=ROLLED_BACK`, para que a primeira falha já gere aumento | > 0 | o pedido de Exclusão fica `IN_PROGRESS` e o prazo legal continua correndo |
| `memetrade_account_anonymization_seconds` (timer, sem rótulos) | API, em volta da transação inteira, incluída a espera de trava | p95 > 2 s | duração real fora do teste; com contenção inclui até 2 s de `lock_timeout` |
| `memetrade_data_requests_overdue` (U4) | API | > 0 | uma falha repetida vira pedido vencido |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| `ANONYMIZATION_FAILED` | `increase(memetrade_account_anonymization_failures_total[1h]) > 0` | Média | responsável do projeto (SNS "Média" no desenho da nuvem); local: o administrador vê o erro no diálogo T8 e o log `ERROR` |
| `ANONYMIZATION_FAILING_REPEATEDLY` | `increase(memetrade_account_anonymization_failures_total[1h]) >= 3` | Alta | responsável do projeto (SNS "Alta") |
| `DATA_REQUEST_OVERDUE` (U4) | sem mudança; cobre a Exclusão que não conclui | Alta | igual |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Duração da anonimização (20 bots, 100 sessões, 50 feedbacks), sem contenção | p95 ≤ 2 s | `AnonymizationLatencyIT`, 20 repetições (asserção de teste) |
| Duração da anonimização em uso | p95 ≤ 2 s; com contenção pode chegar ao `lock_timeout` de 2 s e virar `503` | `memetrade_account_anonymization_seconds`, janela de 30 dias (volume baixo: acompanhar, sem alerta próprio) |
| Pedidos de Exclusão concluídos no prazo | 100% | por pedido (U4) |

## Logs & Tracing

- **Logs:** `INFO` no sucesso e `ERROR` na falha, só com `requestId`, contagens e a classe da exceção; nunca o endereço da carteira.
- **Auditoria:** `ACCOUNT_ANONYMIZED` e `DATA_REQUEST_TRANSITIONED`, só com contagens e identificadores internos.
- **Tracing:** não há tracing novo.
