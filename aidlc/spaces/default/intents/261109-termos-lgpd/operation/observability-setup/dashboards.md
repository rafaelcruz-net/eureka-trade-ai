# Painéis

Conversa: português (Brasil). Resposta 3A. Os painéis do intent anterior continuam (`261026-memetrade-saas-mvp/operation/observability-setup/dashboards.md`). Este intent acrescenta o painel "Privacidade e termos", montado com as métricas dos `monitoring-design.md` das Units U1 a U5. Localmente, os mesmos números saem de `/actuator/prometheus` (porta 8081) e `/actuator/health`; na AWS, viram um painel do CloudWatch.

## Painel "Privacidade e termos"

| Linha | Widget | Métrica ou consulta | Leitura |
|---|---|---|---|
| Documentos legais | versão vigente e publicável | saúde `legalDocuments` (detalhes) | `UP` com versão publicável; `DEGRADED` sem os campos de implantação |
| Documentos legais | p95 e volume das rotas `/api/v1/legal/**` | `http_server_requests_seconds{uri=~"/api/v1/legal.*"}` | meta 300 ms (U1 NFR3.2) |
| Novo aceite | requisições barradas pela trava | `memetrade_terms_reacceptance_blocked_total` | pico logo depois de publicar uma versão |
| Novo aceite | aceites por origem | `memetrade_terms_acceptances_total{source}` | adesão ao novo aceite |
| Novo aceite | p95 da trava | `memetrade_terms_reacceptance_check_seconds` | meta 5 ms (U3 NFR3.2) |
| Retenção | linhas apagadas e execuções por expurgo | `memetrade_purge_deleted_total{purge}`, `memetrade_purge_runs_total{purge,result}` | volume diário; falhas |
| Retenção | último sucesso por expurgo | `memetrade_purge_last_success_seconds{purge}` | alerta acima de 48 h |
| Pedidos do titular | abertos por tipo, vencendo, vencidos | `memetrade_data_requests_open{type}`, `memetrade_data_requests_due_soon`, `memetrade_data_requests_overdue` | prazo legal de 15 dias |
| Pedidos do titular | criados por tipo e origem | `memetrade_data_requests_created_total{type,origin}` | volume |
| Anonimização | concluídas e falhas por motivo | `memetrade_accounts_anonymized_total`, `memetrade_account_anonymization_failures_total{reason}` | falha gera alarme |
| Anonimização | p95 da transação | `memetrade_account_anonymization_seconds` | meta 2 s (U5 NFR3.1) |

Nenhuma métrica tem rótulo de pessoa, tenant, carteira ou pedido (`security-design.md` das Units).

## Painéis existentes

O painel de saúde geral do intent anterior passa a mostrar os indicadores `legalDocuments`, `loginChallengePurge`, `sessionPurge` e `dataRequests` ao lado dos que já existiam.
