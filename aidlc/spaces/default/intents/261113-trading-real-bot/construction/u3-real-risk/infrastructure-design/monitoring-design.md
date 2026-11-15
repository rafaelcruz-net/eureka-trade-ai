# Monitoramento — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-design/observability-design.md`, `construction/u3-real-risk/nfr-requirements/observability-requirements.md`, `construction/u2-real-market-data/infrastructure-design/monitoring-design.md`, `infra/terraform/modules/observability/` e `infrastructure-design-questions.md` (Q2).

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `real_risk_evaluations_total{side,outcome,code,detail}` | worker (ciclo) e API (liquidação) | — (painel) | motivos de recusa |
| `real_risk_evaluation_seconds{side}` | worker e API | p95 de BUY acima de 0,15 s por 10 min | desempenho da barreira |
| `real_risk_lock_wait_seconds` | worker | — (painel) | disputa pela trava |
| `real_risk_reservations{state}` | worker | — (painel) | reservas em aberto |
| `real_risk_reservations_expired_total` | worker | aumento > 0 em 15 min | a U4 não consumiu a decisão |
| `real_risk_daily_loss_pauses_total` | worker e API | — (painel) | pausas por perda |
| `real_risk_errors_total{dependency}` | worker e API | aumento > 5 em 5 min | barreira falhando fechada |
| `real_risk_job_runs_total{job}` | worker (`day`, `daily-loss`, `reservation-expiry`), API (`purge`) | nenhuma execução bem-sucedida em 5 min (`daily-loss`) ou 15 min (`day`, `reservation-expiry`) | trabalho parado |
| `real_risk_job_last_success_seconds{job}` e `real_risk_job_seconds{job}` | os mesmos | — (painel) | idade e duração dos trabalhos |

O alerta de trabalho parado usa o contador de execuções bem-sucedidas. Assim ele tem a mesma forma no Prometheus e na nuvem, e também pega o trabalho que nunca publicou. Este documento passa a valer no lugar da forma com `time() - medidor` e `absent()` dos requisitos (U3-NFR5.12).

## Alerts

| Alert | Prometheus (`infra/observability/real-risk-alerts.yml`) | CloudWatch (`infra/terraform/modules/observability/real-risk.tf`) | Severity |
|---|---|---|---|
| U3-NFR5.10 Erros da barreira | `increase(real_risk_errors_total[5m]) > 5` | namespace `Eureka/RealRisk`, métrica `real_risk_errors_total`, `Sum`, período 300 s, 1 de 1, `> 5`, `treat_missing_data = notBreaching` | crítica |
| U3-NFR5.12 Trabalho parado (um alarme por trabalho) | `increase(real_risk_job_runs_total{job="daily-loss"}[5m]) < 1` com `or absent(...)`; o mesmo com 15 min para `day` e `reservation-expiry` | três alarmes, um por trabalho do worker: `Sum` de `real_risk_job_runs_total` com a dimensão `job`, período 300 s ou 900 s, `< 1`, `treat_missing_data = breaching` | crítica |
| U3-NFR5.9 Reserva vencida | `increase(real_risk_reservations_expired_total[15m]) > 0` | `Sum`, período 900 s, `> 0`, `notBreaching` | aviso |
| U3-NFR5.11 Barreira lenta | `histogram_quantile(0.95, ...{side="BUY"}[10m]) > 0.15` | **adiado:** o p95 na nuvem depende de a métrica chegar como distribuição, e isso faz parte da decisão sobre o caminho das métricas | aviso |

**Rotas.** Só existe um tópico SNS (`alarm_topic_arn`), que manda e-mail e SMS. Até existir um tópico só de e-mail, todo alarme da U3 vai para ele, como a U2 registrou. A severidade fica como rótulo da regra e na descrição do alarme.

**Paridade.** Um teste novo, `RealRiskAlarmParityTest`, lê o arquivo de regras da U3 e o `real-risk.tf`. Cada regra tem as anotações `threshold`, `window_seconds` e `job` (quando houver). O teste confere, regra por regra, o nome, o limite, a janela e a dimensão do alarme. O alerta de lentidão fica de fora, marcado como adiado. O teste da U2 não muda.

**Pendência.** O caminho das métricas do Micrometer até o CloudWatch (namespace, exportador e dimensões) é decisão única de U1, U2 e U3, a tomar antes da mainnet. Os alarmes acima assumem namespace `Eureka/RealRisk` com a dimensão `job`. Se a decisão mudar isso, o Terraform e o teste de paridade mudam juntos.

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Fração de minutos com p95 da avaliação de compra ≤ 150 ms | 0,99, informativo | 30 dias corridos |

Sem portão nem orçamento de erro: o `team.md` não define metas de desempenho com portão.

## Logs & Tracing

- **Logs:** os da aplicação, com `LogMasking` para mint e endereço. A recusa é registrada em nível INFO com código e detalhe; o erro técnico em WARN com a dependência.
- **Auditoria:** cada recusa e cada mudança da lista de bloqueio viram evento REAL pela saída que já existe.
- **Retenção:** 30 dias no CloudWatch Logs, como as outras Units.
