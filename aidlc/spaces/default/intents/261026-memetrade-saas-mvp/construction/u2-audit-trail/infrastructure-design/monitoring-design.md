# Desenho de monitoramento — U2 Trilha de auditoria

Monitoramento que implementa a estratégia de [observability-design.md](../nfr-design/observability-design.md) no ambiente local, com os componentes de [logical-components.md](../nfr-design/logical-components.md), as regras de saúde de [reliability-design.md](../nfr-design/reliability-design.md) e a infraestrutura de [infrastructure-specification.md](infrastructure-specification.md). Como na U1, não há coletor, painel nem notificação no ambiente local. As métricas ficam expostas no formato Prometheus na porta de gestão (8081) e são lidas pelo `scripts/verify.sh`, pelo `scripts/perf-audit.sh` e por quem desenvolve.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `audit_outbox_pending`, `audit_outbox_oldest_age_seconds` | Relay (U1) | Idade < 60 s | Atraso da trilha |
| `audit_delivery_seconds` | Relay | Balde de 5 s com 99% ou mais das entregas | Meta de entrega (NFR4.2) |
| `audit_delivery_unmeasured_total` | Relay | 0 depois da primeira hora com `track_commit_timestamp` ligado | Medida da entrega válida |
| `audit_events_recorded_today` | Relay, por instância | < 80 mil | Teto de volume (NFR2.1) |
| `audit_events_published_total{action}` | Publicador | Acompanhar | Origem do volume |
| `audit_publish_rejected_total{reason}` | Publicador | 0 | Defeito de Unit chamadora |
| `audit_forbidden_aggregated_total` | Agregador de recusas | < 600 por hora | Possível abuso |
| `audit_job_runs_total{job,result}` | Expurgo e limpeza | Nenhum `FAILED` nem `TIME_LIMIT` | Retenção (NFR7.4) |
| `audit_job_deleted_total{job}` | Expurgo e limpeza | Acompanhar; cerca de 100 mil por dia no teto de volume | Linhas apagadas (NFR3.3, NFR7.4) |
| `audit_job_duration_seconds{job}` | Expurgo e limpeza | < 30 min | Limite de execução (NFR3.3) |
| `audit_job_last_success_timestamp_seconds{job}` | Expurgo e limpeza | Até 26 h atrás | Saúde (NFR4.5) |
| `http_server_requests_seconds` na rota da trilha | API (U1) | p95 ≤ 2 s com a trilha cheia | Consulta (NFR3.1) |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Relay parado | `audit_outbox_oldest_age_seconds` ≥ 60 por 5 min | Alta | Indicador `auditRelay` degradado no corpo da saúde; o smoke falha |
| Expurgo ou limpeza sem sucesso | `auditRetention` ou `auditOutboxCleanup` degradado | Média | Corpo da saúde; log `ERROR` ou `WARN`; o smoke falha |
| Entrega lenta | Proporção até 5 s abaixo de 99,5% por 15 min | Média | Só exposta localmente; falha do `scripts/perf-audit.sh` |
| Volume diário alto | `audit_events_recorded_today` > 80 mil | Média | Só exposta localmente |
| Recusa do publicador | `audit_publish_rejected_total` aumentou | Média | Log `ERROR`; os testes da Unit chamadora falham |
| Recusas repetidas | `audit_forbidden_aggregated_total` > 600 em 1 h | Baixa | Só exposta localmente |

Notificação por e-mail ou chat entra com a hospedagem e a operação.

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Proporção de entregas em até 5 s do commit | 99% | 1 hora de carga no `scripts/perf-audit.sh`; janela contínua de 7 dias com a hospedagem |
| p95 da consulta da trilha | ≤ 2 s | Execução do `scripts/perf-audit.sh` nos três cenários |
| Pendência mais antiga | < 60 s | Contínuo, no smoke e nos testes de integração |
| Disponibilidade | Sem SLO no beta local (NFR10.1) | — |

## Logs & Tracing

- Logs JSON na saída padrão, com o driver `json-file` da U1 (`max-size: 10m`, `max-file: 3`) também em `db-bootstrap` e `db-migrate`.
- Os logs do contêiner do PostgreSQL entram na varredura do job `smoke` e da suíte de integração (NFR7.3). A configuração de log do banco está em [infrastructure-specification.md](infrastructure-specification.md).
- Cada execução agendada tem `correlationId` próprio; a consulta usa o da requisição.
- Sem rastreamento distribuído.
- Painéis: nenhum localmente. Com a hospedagem, um painel da trilha com entrega, pendências, volume diário, execuções das tarefas e consultas.
