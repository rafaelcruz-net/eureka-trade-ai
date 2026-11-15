# Monitoramento — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-design/observability-design.md`, `construction/u4-real-execution/nfr-requirements/observability-requirements.md`, `construction/u3-real-risk/infrastructure-design/monitoring-design.md` e `infrastructure-design-questions.md` (Q3).

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `real_orders_total{side,mode,state,code}` | worker e API | — (painel) | estados e motivos |
| `real_order_decision_to_send_seconds{mode}` | worker | p95 de AUTO_FULL acima de 3 s por 10 min | NFR3 |
| `real_order_send_to_final_seconds` | worker | — (painel) | confirmação |
| `real_orders_sent_oldest_seconds` | worker | acima de 75 s | a regra dos 60 s falhou |
| `real_orders_ambiguous_open`, `real_orders_ambiguous_oldest_seconds` | worker | ambígua mais velha acima de 300 s | dinheiro reservado sem resposta |
| `txsigner_swap_decisions_total{outcome,reason}` | TxSigner | qualquer `REFUSED` em 5 min | ataque ou erro de montagem |
| `real_upstream_failures_total{provider}`, `real_upstream_pauses_total` | worker | qualquer pausa em 5 min | fornecedor fora |
| `real_execution_job_runs_total{job}` | worker | nenhuma execução na janela do trabalho, ou ausente | trabalho parado |

## Alerts

| Alert | Prometheus (`infra/observability/real-execution-alerts.yml`) | CloudWatch (`infra/terraform/modules/observability/real-execution.tf`, namespace `Eureka/RealExecution`) | Severity |
|---|---|---|---|
| U4-NFR5.9 Ambígua antiga | `max(real_orders_ambiguous_oldest_seconds) > 300` | `Maximum`, 60 s, `> 300`, `notBreaching` | crítica |
| U4-NFR5.10 Recusa da política | `increase(txsigner_swap_decisions_total{outcome="REFUSED"}[5m]) > 0` | `Sum`, 300 s, `> 0`, `notBreaching` | crítica |
| U4-NFR5.11 Falha de assinatura | `increase(real_orders_total{code="SIGNING_REFUSED"}[5m]) > 0` | `Sum`, 300 s, `> 0`, `notBreaching` | crítica |
| U4-NFR5.11 TxSigner indisponível | `increase(real_upstream_failures_total{provider="TXSIGNER"}[5m]) > 2` | `Sum` com a dimensão `provider`, 300 s, `> 2`, `notBreaching` | crítica |
| U4-NFR5.12 Pausa por fornecedor | `increase(real_upstream_pauses_total[5m]) > 0` | `Sum`, 300 s, `> 0`, `notBreaching` | alta |
| U4-NFR5.14 Trabalho parado (uma por trabalho) | `increase(real_execution_job_runs_total{job="<job>"}[<janela>]) < 1 or absent(...)`: janela de 1 min para `queue`, `driver` e `confirmation`, e de 5 min para `ambiguous`, `signing-deadline`, `health` e `liquidation` | sete alarmes, `Sum` por `job`, período igual à janela, `< 1`, `breaching` | crítica |
| `SENT` mais antiga | `max(real_orders_sent_oldest_seconds) > 75` | `Maximum`, 60 s, `> 75`, `notBreaching` | crítica |
| U4-NFR5.13 Execução lenta | `histogram_quantile(0.95, ...{mode="AUTO_FULL"}[10m]) > 3` | **adiado**, como o p95 da U3 | aviso |

**Configuração:**
- **Rotas:** só o tópico SNS único (`alarm_topic_arn`), com e-mail e SMS, como em U1 a U3.
- **Paridade:** `RealExecutionAlarmParityTest`, no mesmo formato do teste da U3. Lê as anotações `threshold`, `window_seconds` e `job` de cada regra.
- **Pendência:** o caminho das métricas do Micrometer até o CloudWatch é decisão única de U1 a U4, a tomar antes da mainnet. Ela inclui o endpoint de VPC do TxSigner, que fica numa subrede sem internet.

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Fração das ordens AUTO_FULL enviadas com até 3 s desde a decisão | 0,95, informativo | 30 dias corridos |
| Fração das ordens enviadas que chegam a estado final sem passar por `AMBIGUOUS` | 0,99, informativo | 30 dias corridos |

Sem portão (`team.md`).

## Logs & Tracing

- `LogMasking` para assinatura e endereço; a transação assinada nunca vai para o log.
- Cada transição registra em INFO a ordem, o estado anterior, o novo e o código.
- O erro de fornecedor é registrado em WARN, com o fornecedor e a categoria.
- 30 dias no CloudWatch Logs.
