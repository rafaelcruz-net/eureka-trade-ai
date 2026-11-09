# Alarmes

Conversa: português (Brasil). Roteamento conforme a resposta 7A. Os alarmes vêm dos `monitoring-design.md` das Units U1 a U8 e das regras de saúde dos `reliability-design.md` (`construction/<unit>/nfr-design/`). As ameaças cobertas pela seção "Segurança" estão nos `security-design.md`.

## Roteamento

| Ambiente | Alta | Média | Escalonamento |
|---|---|---|---|
| Local (hoje) | saúde `DOWN` no `/actuator/health`, contêiner `unhealthy`, log `ERROR` com código estável, falha do `verify.sh` | saúde `DEGRADED`, log `WARN` ou `ERROR` | o responsável do projeto lê saúde e logs |
| AWS (desenho) | tópico SNS `memetrade-<env>-alta`: e-mail e notificação no celular ao responsável do projeto; reconhecimento esperado em 15 min | tópico SNS `memetrade-<env>-media`: e-mail; tratar no mesmo dia | só o responsável do projeto enquanto o time tiver uma pessoa; contenção pelo kill switch da U8 |

Em staging, a Alta vai só por e-mail. Os runbooks ligados a cada alarme vêm na etapa de Incident Response.

## Definições

Condições em métricas Prometheus coletadas pelo agente. Na AWS, viram alarmes do CloudWatch com `TreatMissingData = breaching` nos alarmes de frescor e de saúde.

| Alarme | Condição | Período | Severidade | Origem |
|---|---|---|---|---|
| `API_DOWN` | health check do balanceador falha, ou `/actuator/health` = `DOWN` | 2 de 3 min | Alta | U1 |
| `SLO_BURN_FAST` | consumo ≥ 14,4× em 1 h e em 5 min (`slo-config.md`) | 5 min | Alta | SLO |
| `SLO_BURN_SLOW` | consumo ≥ 6× em 6 h e em 30 min | 30 min | Média | SLO |
| `HTTP_5XX_RATE` | 5xx > 2% das requisições | 5 min | Alta | U1, todas |
| `LATENCY_P95` | p95 de uma rota acima da meta da Unit por 10 min | 10 min | Média | performance-design |
| `DB_POOL_PENDING` | `hikaricp_connections_pending` > 0 | 2 min contínuos | Média | U1 |
| `JVM_HEAP_HIGH` | heap > 85% do máximo | 10 min | Média | U1 |
| `CONTAINER_MEMORY_HIGH` | memória > 90% do limite do serviço | 5 min | Média | infraestrutura |
| `AUDIT_OUTBOX_STALE` | `audit_outbox_oldest_age_seconds` ≥ 60 | 2 min | Alta | U2 |
| `AUDIT_JOB_STALE` | sem sucesso de expurgo ou limpeza no período esperado (`audit_job_last_success_timestamp_seconds`) | 1 h | Média | U2 |
| `MARKET_SOURCE_STALE` | `market_source_last_collection_age_seconds` > 60 ou status ≠ `OK` | 2 min | Média; Alta se > 10 min | U1, U4 |
| `RISK_RECALC_STALE` | último recálculo bem-sucedido > 5 min | 5 min | Média | U4 |
| `BOT_CYCLE_ERRORS` | aumento de `bots_cycle_errors_total` | 5 min | Média | U5 |
| `RISK_EVALUATION_ERRORS` | aumento de `riskcontrol_evaluation_errors_total` | 5 min | Alta (ordem sem checagem de risco não pode passar) | U6 |
| `RISK_PURGE_FAILED` | aumento de `riskcontrol_purge_errors_total` | 1 rodada | Média | U6 |
| `LEDGER_DIVERGENCE` | reconciliação com divergência > 0, ou saúde `ledgerJobs` `DEGRADED` | 1 rodada | Alta | U7 |
| `KILL_SWITCH_RECONCILE_STALE` | `admin_killswitch_reconcile_needed` = 1 por > 60 s | 1 min | Alta | U8 |
| `KILL_SWITCH_PENDING_STALE` | `admin_killswitch_pending_bots` > 0 por > 2 min | 2 min | Alta | U8 |
| `KILL_SWITCH_DIVERGENCE` | aumento de `admin_killswitch_reconcile_divergences_total` | 1 rodada | Alta | U8 |
| `FEEDBACK_PURGE_STALE` | expurgo sem sucesso há 48 h | 1 h | Média | U8 |
| `REFRESH_TOKEN_REUSE` | `session_refresh_reuse_total` > 0 | 1 min | Alta | U1/U3, segurança |
| `LOGIN_REJECTED_SPIKE` | `login_attempts_total{result="rejected"}` acima de 5× a linha de base | 15 min | Média | segurança |
| `ADMIN_LOGIN_RATE_LIMITED` | aumento de `admin_login_refused_total{reason="RATE_LIMITED"}` | 15 min | Média | U8, segurança |
| `MIGRATIONS_DOWN` | saúde `migrations` `DOWN` | 1 min | Alta | U1 |

## Regras

- Todo alarme Alto tem um runbook (Incident Response) e uma ação de contenção conhecida. Para qualquer dúvida sobre ordens PAPER, a contenção é o kill switch.
- A mensagem do alarme leva o nome, o ambiente, o valor e o link do painel. Nunca leva dado pessoal, endereço completo, token ou segredo.
- Alarmes de frescor e de saúde tratam ausência de dado como falha.
