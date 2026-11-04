# Desenho de monitoramento — U3 Convites, contas e sessões

Monitoramento da Unit `u3-identity-access` no ambiente local, implementando [observability-design.md](../nfr-design/observability-design.md). Sem métricas de negócio próprias (Q5 de NFR Requirements) e sem alertas no beta local.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `http_server_requests_seconds` com `uri="/api/v1/auth/verify"` | Micrometer, `/actuator/prometheus` | p95 ≤ 500 ms no script de carga | NFR3.1 |
| `http_server_requests_seconds` com `uri="/api/v1/invites/{code}"` e `uri="/api/v1/account"` | Micrometer | p95 ≤ 300 ms no script de carga | NFR3.2 |
| `http_server_requests_seconds_count` por `uri` e `status` 5xx nas rotas da U3 | Micrometer | Nenhum 5xx no smoke e na carga | NFR9.2 |
| `http_server_requests_seconds_count` com `status="429"` em `/api/v1/invites/{code}` | Micrometer | Zero na carga (perfil `loadtest`); presente no teste do limite | NFR6.6 |
| `health` do banco | Actuator | `UP` | NFR9.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Nenhum no beta local | — | — | Definidos com a hospedagem, abaixo das metas de SLO (NFR9.6) |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 da verificação com convite | ≤ 500 ms | 20 verificações simultâneas no script de carga |
| p95 da consulta do convite e da tela Conta | ≤ 300 ms | 5 minutos, 20 usuários |
| Taxa de 5xx das rotas da U3 | 0 no smoke e na carga | Execução do smoke e da carga |

## Logs & Tracing

| Item | Configuração |
|---|---|
| Correlação | `CorrelationIdFilter` da U1 em todas as rotas; `correlationId` no `ProblemDetail` |
| Mascaramento | `LogMasking` (endereços); redação de segmentos longos cobre o código do convite; `RequestPaths` registra o modelo da rota |
| Varredura | `LogScanIT` ganha um fluxo completo da U3 (emitir, consultar, verificar, conta) e procura código, assinatura, nonce, token e endereço completo; o `scripts/verify.sh` já procura o endereço completo da carteira de smoke no log da `api` |
| Rótulos de métrica | `MetricsLabelsIT` (U4) passa a exercitar as rotas da U3 e falha se algum rótulo tiver código, endereço, `inviteId` ou identificador de pessoa (NFR9.3) |
| Rastreamento distribuído | Não usado (um processo) |
| Painéis | Nenhum no beta local; as métricas ficam em `/actuator/prometheus` e no relatório do script de carga |
