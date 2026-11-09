# Painéis

Conversa: português (Brasil). Três painéis (resposta 4A). No ambiente local, cada painel é uma lista de consultas de referência ao `/actuator/prometheus` e ao `/actuator/health` (porta 8081 da API; 8082 do worker no overlay de teste). Na AWS, cada painel vira um painel do CloudWatch, alimentado pelo agente CloudWatch/ADOT que coleta as métricas Prometheus (desenho, sem recurso criado).

Sinais vindos dos `monitoring-design.md` das Units U1 a U8 (`construction/<unit>/infrastructure-design/`) e dos `performance-design.md` (`construction/<unit>/nfr-design/`). A infraestrutura está em `infrastructure-specification.md`.

## Leitura local

```bash
# métricas (dentro do ambiente subido com o overlay de teste ou com a porta 8081 publicada)
curl -s http://127.0.0.1:8081/actuator/prometheus | grep -E '^(http_server_requests_seconds|hikaricp_connections|audit_outbox|market_source)'
# saúde com detalhes por componente
curl -s http://127.0.0.1:8081/actuator/health
# memória e CPU dos contêineres
docker stats --no-stream
```

## Painel 1: Visão geral (sinais de ouro)

| Widget | Métrica (nome Prometheus) | Agregação | Limite visual |
|---|---|---|---|
| Latência por rota | `http_server_requests_seconds` (histograma) por `uri`, `method` | p95 e p99 em 5 min | p95 > meta da Unit (ver `slo-config.md`) |
| Tráfego por rota | `http_server_requests_seconds_count` | taxa por minuto | — |
| Taxa de 5xx | `http_server_requests_seconds_count{status=~"5.."}` ÷ total | 5 min | > 1% |
| 4xx por categoria | `http_server_requests_seconds_count{status=~"4.."}` por `uri` | 5 min | picos |
| Pool do banco | `hikaricp_connections_active`, `hikaricp_connections_pending` | máx. em 1 min | pendentes > 0 por 2 min |
| JVM | `jvm_memory_used_bytes{area="heap"}` ÷ máximo, `process_cpu_usage` | média de 5 min | heap > 80% |
| Contêineres | memória e CPU por serviço (`docker stats` local; Container Insights na AWS) | 5 min | memória > 85% do limite (1 GB api/worker/postgres; 512 MB web/admin) |
| Saúde | `/actuator/health` de api e worker; healthchecks de web e admin | estado atual | qualquer `DOWN` |
| SLOs | disponibilidade, latência de leitura e frescor (`slo-config.md`) | janela móvel | consumo do orçamento |

## Painel 2: Domínio

| Widget | Métrica | Limite |
|---|---|---|
| Atraso da auditoria (U2) | `audit_outbox_pending`, `audit_outbox_oldest_age_seconds`, `audit_delivery` | idade ≥ 60 s |
| Rodadas da auditoria (U2) | `audit_job_runs`, `audit_job_last_success_timestamp_seconds` | sem sucesso no período esperado |
| Fonte de mercado (U1/U4) | `market_source_status`, `market_source_last_collection_age_seconds`, `market_collection_total`, `market_collection_retries_total`, `market_collection_discarded_total` | idade > 60 s; estado diferente de `OK` |
| Scanner (U4) | `scanner_excluded_by_unknown`, `scanner_rate_limited_total`, `risk_assessments` por `risk_band` e `risk_status` | aumento brusco de `unknown` |
| Recálculo de risco (U4) | `risk_recalculation_total`, `risk_recalculation_duration_seconds`, saúde `riskRecalculation` | último sucesso > 5 min |
| Bots (U5) | `bots_cycle_errors_total`, timers `bots.create/start/pause/resume/stop`, saúde `botCycles` | erros > 0 |
| Controle de risco (U6) | `riskcontrol_evaluation_errors_total`, `riskcontrol_purge_errors_total`, saúde `riskcontrol` | erros > 0 |
| Ledger e P&L (U7) | timers `paper.execute`, `paper.fund`, `pnl.reconciliation.run`, `pnl.updated`; saúde `ledgerJobs` | divergência > 0; `DEGRADED` |
| Kill switch (U8) | `admin_killswitch_state`, `admin_killswitch_pending_bots`, `admin_killswitch_reconcile_needed`, `admin_killswitch_reconcile_divergences_total`; saúde `adminKillSwitch` | pendentes > 0 por 2 min; reconciliação > 60 s |
| Feedback (U8) | `feedback_received_total`, `feedback_refused_total` por `reason`; saúde `feedbackPurge` | expurgo sem sucesso há 48 h |
| Migrações | saúde `migrations` | `DOWN` |

## Painel 3: Segurança

| Widget | Métrica | Limite |
|---|---|---|
| Logins | `login_attempts_total` por `result` e `reason` | pico de `rejected` |
| Limite de tentativas | `rate_limit_rejections_total` por `target`; `admin_login_refused_total{reason="RATE_LIMITED"}` | picos |
| Reuso de token de renovação | `session_refresh_reuse_total` | qualquer valor > 0 |
| Acesso negado por tenant | `http_server_requests_seconds_count{status=~"403\|404"}` em rotas de recurso por ID | aumento acima da linha de base |
| Auditoria recusada | `audit_publish_rejected` | > 0 |
| Entradas de administrador | `admin_login_refused_total` por `reason`; eventos de auditoria `ADMIN_*` | qualquer recusa repetida |

Nenhum painel mostra endereço completo de carteira, token, nonce, assinatura ou texto de feedback. Os rótulos seguem a lista de campos permitidos testada por `MetricsLabelsIT`.
