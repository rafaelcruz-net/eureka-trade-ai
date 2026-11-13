# Alarmes

Conversa: português (Brasil). O roteamento e os alarmes do intent anterior (`261026-memetrade-saas-mvp/operation/observability-setup/alarms.md`) continuam valendo: local pela saúde, pelo contêiner `unhealthy` e pelos logs; na AWS, tópicos SNS `memetrade-<env>-alta` (e-mail e celular, reconhecimento em 15 min) e `memetrade-<env>-media` (e-mail, no mesmo dia). Aqui ficam os alarmes das Units U1 a U5, vindos dos `monitoring-design.md` (`construction/<unit>/infrastructure-design/`), das regras de saúde dos `reliability-design.md` e das ameaças dos `security-design.md` (`construction/<unit>/nfr-design/`).

## Alarmes novos

| Alarme | Condição | Período | Severidade | Local (hoje) | Origem |
|---|---|---|---|---|---|
| `LEGAL_NOT_PUBLISHABLE` | `legalDocuments` em `DEGRADED` em produção | 5 min | Média | saúde `legalDocuments` (esperado `DEGRADED` no ambiente de teste) | U1 NFR7.3 |
| `LEGAL_VERSION_IGNORED` | log `WARN` "Legal version ... ignored" na subida | por ocorrência | Média | log da `api` | U1 NFR8.1 |
| `PURGE_STALE` | `loginChallengePurge`, `sessionPurge` ou `dataSubjectRequestPurge` em `DEGRADED`; ou `time() - memetrade_purge_last_success_seconds > 172800` | 15 min | Média | saúde da `api` | U2 NFR8.5, U4 |
| `PURGE_FAILED` | `increase(memetrade_purge_runs_total{result="FAILED"}[1d]) > 0` | 1 dia | Média | log `ERROR` do expurgo | U2 NFR7.1 |
| `AUDIT_FULL_WALLET_REJECTED` | log `ERROR` da recusa com motivo `FULL_WALLET` | por ocorrência | Alta | log da `api` | U2 NFR1.2 |
| `TERMS_CHECK_SLOW` | p95 de `memetrade_terms_reacceptance_check_seconds` > 5 ms | 15 min | Média | métrica no `/actuator/prometheus` | U3 NFR3.2 |
| `DATA_REQUEST_OVERDUE` | `memetrade_data_requests_overdue > 0`, ou `dataRequests` em `DEGRADED` | 15 min | Alta | saúde `dataRequests` e evento da trilha | U4 NFR8.3 |
| `DATA_REQUEST_DUE_SOON` | `memetrade_data_requests_due_soon > 0` | 1 h | Média | lista do administrador destaca o prazo | U4 |
| `ANONYMIZATION_FAILED` | `increase(memetrade_account_anonymization_failures_total[1h]) > 0` | 1 h | Média | erro no diálogo T8 e log `ERROR` | U5 NFR8.4 |
| `ANONYMIZATION_FAILING_REPEATEDLY` | `increase(memetrade_account_anonymization_failures_total[1h]) >= 3` | 1 h | Alta | idem | U5 NFR8.4 |

Os dois alarmes que a Construction deixou para este estágio (U2 NFR8.5 `PURGE_STALE` e U4 NFR8.3 `DATA_REQUEST_OVERDUE`) ficam definidos aqui. No ambiente local, o sinal deles é a saúde `DEGRADED` (já testada); o alarme provisionado no CloudWatch depende da conta da AWS (Environment Provisioning, pendente).

## Alarmes existentes que passam a cobrir este intent

| Alarme | O que inclui agora |
|---|---|
| `HTTP_5XX_RATE` | rotas legais, trava e aceite, pedidos do titular e anonimização |
| `LATENCY_P95` | metas dos `performance-design.md` deste intent: legais 300 ms, aceite 500 ms, pedidos 500 ms, rotas administrativas 1 s, anonimização 2 s |
| `DB_POOL_PENDING` | pool fixo de 20 conexões da `api` |

## Runbooks

Cada alarme novo ganha seu runbook na Incident Response (próximo estágio).
