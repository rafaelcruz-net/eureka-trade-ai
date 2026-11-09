# Desenho de monitoramento — U1 `u1-legal-documents`

Conversa: português (Brasil). Implementa `construction/u1-legal-documents/nfr-design/observability-design.md` no ambiente local (actuator, logs JSON, healthcheck do Compose), com a ligação aos painéis e alarmes do produto (`aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/observability-setup/`). A infraestrutura está em `infrastructure-specification.md`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `http_server_requests_seconds` nas rotas `/api/v1/legal/**` | API | p95 ≤ 300 ms | NFR3.2 |
| Contagem de 429 nas rotas de documentos | API (métrica HTTP por status) | picos | abuso ou limite mal configurado |
| Contagem de 5xx nas rotas de documentos | API | 0 | NFR3.3 |
| Saúde `legalDocuments` (`currentVersion`, `publishable`) | actuator | `DEGRADED` | versão não publicável |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| `LEGAL_NOT_PUBLISHABLE` | `legalDocuments` em `DEGRADED` em produção | Média | responsável do projeto (SNS "Média" no desenho da nuvem); local: saúde e log |
| `LEGAL_VERSION_IGNORED` | `WARN` "Legal version ... ignored" na subida | Média | log; na nuvem, consulta salva do Logs Insights |
| `HTTP_5XX_RATE` (existente) | inclui as rotas de documentos | Alta | igual ao produto |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 das rotas de documentos | ≤ 300 ms | cenário `legal`, 5 min |
| p95 das páginas `/termos` e `/privacidade` | ≤ 2 s | cenário `legal`, 5 min |
| Disponibilidade | sem SLO no beta local, como o produto | — |

## Logs & Tracing

- Logs JSON no stdout com `INFO` de carga e `WARN` por versão ignorada, sem texto nem campos de implantação.
- Sem rastreamento distribuído; `correlationId` nos erros.
- Painel: as rotas de documentos entram na "Visão geral" do produto.
