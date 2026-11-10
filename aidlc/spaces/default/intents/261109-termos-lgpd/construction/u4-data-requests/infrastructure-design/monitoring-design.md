# Desenho de monitoramento — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa `construction/u4-data-requests/nfr-design/observability-design.md` no ambiente local (actuator, Prometheus, logs JSON). A ligação com o produto fica em `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/observability-setup/`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `memetrade_data_requests_overdue` | API (cache atualizado a cada 60 s) | > 0 | prazo legal de 15 dias vencido |
| `memetrade_data_requests_open{type}` | API | acompanhar | fila de atendimento |
| `memetrade_data_requests_created_total{type,origin}` | API | acompanhar | volume de pedidos |
| Saúde `dataRequests` | actuator | `DEGRADED` | vencido aberto (AC5.7.1) |
| Saúde `dataSubjectRequestPurge` e `memetrade_purge_*{purge="dataSubjectRequestPurge"}` | API | `DEGRADED` ou execução `FAILED` | retenção de 12 meses |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| `DATA_REQUEST_OVERDUE` | `memetrade_data_requests_overdue > 0` por 15 minutos, ou `dataRequests` em `DEGRADED` | Alta | responsável do projeto (SNS "Alta" no desenho da nuvem); local: saúde e evento de auditoria |
| `DATA_REQUEST_DUE_SOON` | `memetrade_data_requests_due_soon > 0`: um gauge a mais no mesmo refresher de 60 s, que conta os abertos com `due_at` a 3 dias ou menos e ainda não vencidos. Na tela, a lista do administrador mostra o mesmo critério com ícone e texto (BR4.16) | Média | painel do administrador; na nuvem, SNS "Média" |
| `PURGE_STALE` (U2) | inclui `dataSubjectRequestPurge` | Média | igual |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Pedidos respondidos dentro do prazo | 100% | por pedido |
| Latência de criação e lista do convidado | p95 ≤ 500 ms | cenário de carga `data-requests` |
| Latência das rotas administrativas | p95 ≤ 1 s | cenário de carga `data-requests` |

## Logs & Tracing

- **Logs:** só `requestId`, tipo, origem e estados, nunca texto, contato, mensagem ou assinatura.
- **Auditoria:** os eventos `DATA_REQUEST_*` formam a trilha do atendimento.
- **Tracing:** não há tracing novo.
