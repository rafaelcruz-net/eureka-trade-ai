# Monitoramento — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/observability-requirements.md` (U6-NFR5.1, U6-NFR5.2) e `infrastructure-design-questions.md` (Q1).

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| Métricas HTTP que já existem da API, com a rota `/api/v1/real/release` | API | — (painel) | a leitura nova segue o padrão das outras rotas |

Nenhuma métrica nova no navegador nem no servidor.

## Alerts

| Alert | Condition | Severity |
|---|---|---|
| Nenhum alerta novo | os alertas de U1 a U5 cobrem o REAL; a U6 não muda nenhum estado do servidor | — |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Nenhum novo | — | — |

## Logs & Tracing

- O navegador não registra nada do REAL (U6-NFR1.2).
- As falhas mostram o `correlationId`, que liga a tela ao log da API.
- A rota nova usa o log e a regra de mascaramento que já existem.
