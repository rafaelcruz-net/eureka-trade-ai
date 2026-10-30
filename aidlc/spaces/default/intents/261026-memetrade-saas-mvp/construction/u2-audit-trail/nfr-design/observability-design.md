# Desenho de observabilidade — U2 Trilha de auditoria

Como a Unit `u2-audit-trail` cumpre [observability-requirements.md](../nfr-requirements/observability-requirements.md) (NFR9.1 a NFR9.5), para os fluxos F1 a F5 de [functional-spec.md](../functional-design/functional-spec.md), com a base da U1 (logs JSON com `correlationId`, métricas Prometheus na porta de gestão, sem rastreamento distribuído) e as escolhas de [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q2, Q4, Q5).

## Métricas (NFR9.2)

| Métrica | Tipo | Rótulos | Uso |
|---|---|---|---|
| `audit_outbox_pending`, `audit_outbox_oldest_age_seconds` (U1) | Medidor | — | Atraso do relay e saúde |
| `audit_delivery_seconds` | Histograma (limites 0,5; 1; 2; 5; 10; 30; 60 s) | — | Do commit real à gravação (NFR4.2, Q5) |
| `audit_events_published_total` | Contador | `action` | Volume por ação |
| `audit_events_recorded_today` | Medidor | — | Eventos gravados no dia UTC corrente, contra o teto de 100 mil. É um contador em memória que o relay incrementa a cada evento gravado e zera na virada do dia UTC. Na partida, começa com uma contagem dos eventos de `recorded_at` no dia corrente, feita uma vez, numa faixa da coluna `recorded_at` com índice próprio |
| `audit_delivery_unmeasured_total` | Contador | — | Entregas sem horário de commit, fora do histograma |
| `audit_publish_rejected_total` | Contador | `reason` (`MISSING_FIELD`, `INVALID_FORMAT`, `FORBIDDEN_KEY`, `FORBIDDEN_VALUE`, `NO_TRANSACTION`) | Defeito de chamador |
| `audit_forbidden_aggregated_total` | Contador | — | Recusas que entraram num resumo em vez de evento próprio |
| `audit_job_runs_total` | Contador | `job`, `result` | Execuções do expurgo e da limpeza |
| `audit_job_deleted_total` | Contador | `job` | Linhas apagadas |
| `audit_job_duration_seconds` | Histograma | `job` | Conferir o limite de 30 min |
| `audit_job_last_success_timestamp_seconds` | Medidor | `job` | Saúde e alerta |
| Requisições HTTP por rota e status, latência (U1) | Contador e histograma | rota, status | Consulta da trilha (NFR3.1) e `403` |

Rótulos proibidos: endereço, `actor`, `tenantId`, `eventId` e qualquer valor de `details`. O rótulo `action` tem cardinalidade limitada, porque as ações são nomes fixos no código de cada Unit. Um teste lê a saída de métricas e falha se encontrar um rótulo proibido.

## Saúde (NFR9.1)

Três indicadores do AuditTrail na saúde agregada da porta de gestão: `auditRelay` (U1), `auditRetention` e `auditOutboxCleanup`, com as regras de [reliability-design.md](reliability-design.md). Qualquer um degradado deixa a saúde da trilha degradada, sem derrubar a API.

## Logs (NFR9.3, NFR9.4)

| Evento | Nível | Campos |
|---|---|---|
| Início de expurgo ou limpeza | INFO | `job`, corte, `correlationId` |
| Fim de expurgo ou limpeza | INFO (`COMPLETED`), WARN (`TIME_LIMIT`), ERROR (`FAILED`) | `job`, corte, quantidade, duração, resultado, causa técnica em falha |
| Tarefa atrasada rodando ao subir | WARN | `job`, horas desde a última execução bem-sucedida |
| Recusa do publicador | ERROR | `action`, motivo, campo; nunca o valor |
| Consulta respondida | INFO | rota, status, quantidade de itens; nunca o conteúdo |
| Resumo de recusas gravado | INFO | rota, contagem; pessoa só como identificador interno |
| Registro de horário de commit desligado | ERROR | nome do parâmetro |

- Cada execução agendada gera um `correlationId` próprio (U1), usado nos logs e em `AUDIT_RETENTION_PURGED`.
- A consulta usa o `correlationId` da requisição nos logs e em `AUDIT_QUERIED`.
- Retenção local de 7 dias, como na U1.

## SLI e SLO de entrega (NFR9.5)

- **SLI:** proporção de eventos com `audit_delivery_seconds` até 5 s, calculada pelo balde de 5 s do histograma sobre o total.
- **SLO:** 99%. Localmente, medido em janela de 1 hora de carga; com hospedagem, janela contínua de 7 dias (a confirmar no Infrastructure Design).

## Alertas candidatos

No ambiente local as métricas ficam só expostas, sem coletor nem notificação (U1). Com a hospedagem, os candidatos são estes. Os alertas de entrega disparam antes de a meta ser violada.

| Alerta | Condição | Severidade |
|---|---|---|
| Entrega lenta | Proporção até 5 s abaixo de 99,5% por 15 min | Ticket |
| Relay parado | `audit_outbox_oldest_age_seconds` ≥ 60 por 5 min | Chamado imediato |
| Expurgo ou limpeza sem sucesso | `auditRetention` ou `auditOutboxCleanup` degradado | Ticket |
| Volume diário alto | `audit_events_recorded_today` acima de 80 mil | Ticket |
| Recusa do publicador | `audit_publish_rejected_total` aumentou | Ticket (defeito de Unit chamadora) |
| Recusas repetidas | `audit_forbidden_aggregated_total` acima de 600 em 1 h | Ticket (possível abuso) |

## Painéis

Nenhum painel no ambiente local (U1). Com a hospedagem, um painel da trilha mostra entrega (SLI), pendências, volume diário, execuções das tarefas e consultas.
