# Design de observabilidade — U1 `u1-legal-documents`

Conversa: português (Brasil). Atende `construction/u1-legal-documents/nfr-requirements/observability-requirements.md` (NFR8.1 a NFR8.3).

## Sinais

| Sinal | Design |
|---|---|
| Logs (NFR8.1) | Na subida: um `INFO` "Legal documents loaded: current version X, N version(s), publishable=true/false" e um `WARN` "Legal version Y ignored: <motivo>" para cada versão ignorada. JSON, como o resto, sem texto do documento nem campos de implantação |
| Saúde (NFR8.2) | Indicador `legalDocuments` com detalhes `currentVersion` e `publishable`; `UP` ou `DEGRADED` (HTTP 200); fora dos grupos de liveness e readiness |
| Métrica (NFR8.3) | A métrica HTTP existente (`http.server.requests`), por rota e status, cobre as três rotas; nenhuma métrica nova com rótulo de dado pessoal |

## SLI

- p95 das rotas `/api/v1/legal/**`, pela métrica HTTP.
- Taxa de 5xx e de 429 nessas rotas.

Painel de referência: a seção "Visão geral" de `operation/observability-setup/dashboards.md` do produto ganha as rotas de documentos. Sem SLO de disponibilidade no beta local.

## Correlação

O `correlationId` existente segue em todas as respostas de erro dessas rotas.
