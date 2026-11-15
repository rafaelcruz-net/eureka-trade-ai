# Requisitos de observabilidade — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md` (NFR5);
- `construction/u3-real-risk/functional-design/rules.md` (BR7.1);
- `infra/observability/real-alerts.yml` (U1);
- `infra/terraform/modules/observability/marketdata.tf` (U2);
- `nfr-requirements-questions.md` (Q5).

## Métricas

Nenhum rótulo leva mint, endereço ou usuário.

| ID | Métrica | Tipo |
|---|---|---|
| U3-NFR5.1 | `real_risk_evaluations_total{side,outcome,code,detail}` (a recusa é `outcome="REJECTED"`) | contador |
| U3-NFR5.2 | `real_risk_evaluation_seconds{side}`, com buckets em 0,01, 0,03, 0,05, 0,1, 0,15, 0,3, 1 s | histograma |
| U3-NFR5.3 | `real_risk_lock_wait_seconds`, com buckets até 1 s | histograma |
| U3-NFR5.4 | `real_risk_reservations{state}` | medidor |
| U3-NFR5.5 | `real_risk_reservations_expired_total` | contador |
| U3-NFR5.6 | `real_risk_daily_loss_pauses_total` | contador |
| U3-NFR5.7 | `real_risk_errors_total{dependency}` (`wallet`, `ledger`, `market`, `assessment`, `lock`) | contador |
| U3-NFR5.8 | `real_risk_job_last_success_seconds{job}` (horário da última execução bem-sucedida de F3, F4 e F5) e `real_risk_job_seconds{job}` | medidor e temporizador |

## Alertas (Prometheus, `infra/observability/real-risk-alerts.yml`)

| ID | Alerta | Condição | Severidade |
|---|---|---|---|
| U3-NFR5.9 | Reserva vencida | `increase(real_risk_reservations_expired_total[15m]) > 0` (sinal de que a U4 não consumiu a decisão) | warning |
| U3-NFR5.10 | Erros da barreira | `increase(real_risk_errors_total[5m]) > 5` | critical |
| U3-NFR5.11 | Barreira lenta | p95 de `real_risk_evaluation_seconds{side="BUY"}` acima de 0,15 s por 10 min | warning |
| U3-NFR5.12 | Trabalho parado | nenhuma execução bem-sucedida (`real_risk_job_runs_total{job}`) em 5 min para `daily-loss` ou em 15 min para `day` e `reservation-expiry`, com `absent()` para o trabalho que nunca publicou (forma ajustada no Infrastructure Design para ter o mesmo alarme no CloudWatch) | critical |

**Regras:**
- As regras do Prometheus são a fonte de verdade e são conferidas pelo `promtool`.
- Os alarmes equivalentes na nuvem dependem do caminho das métricas do Micrometer até o CloudWatch, que ainda não foi decidido (pendência já registrada na U2). Por isso eles ficam para o Infrastructure Design, sem paridade literal com as regras.
- Cada recusa também gera evento de auditoria REAL (BR7.1).
