# Desenho de monitoramento — U1 Esqueleto de ponta a ponta

Monitoramento que implementa a estratégia de [observability-design.md](../nfr-design/observability-design.md) no ambiente local da U1, com os componentes de [logical-components.md](../nfr-design/logical-components.md) e a infraestrutura de [infrastructure-specification.md](infrastructure-specification.md).

No ambiente local não há coletor, painel nem notificação: as métricas ficam expostas no formato Prometheus na porta de gestão (8081) e são lidas pelo script de verificação, pelo teste de carga e por quem desenvolve. Alertas com notificação entram com a hospedagem.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `http_server_requests_seconds` (histograma com percentis) | API, por rota | p95 ≤ 300 ms em desafio, verificação e lista (NFR3.1) | Meta de tempo da U1; lida pelo teste de carga |
| Proporção de respostas 5xx | API | Acompanhar; sem meta até a hospedagem | Erros técnicos |
| `login_attempts_total{result,reason}` | IdentityAccess | Acompanhar picos de `rejected` | Ataques e erros de assinatura |
| `rate_limit_rejections_total{target}` | IdentityAccess | Acompanhar picos | Limite de tentativas em ação |
| `session_refresh_reuse_total` | IdentityAccess | Qualquer valor acima de 0 merece olhar | Possível roubo de token de renovação |
| `audit_outbox_pending` e `audit_outbox_oldest_age_seconds` | AuditTrail | Idade < 60 s | Atraso da trilha (NFR9.4) |
| `market_source_status{source}` e `market_source_last_collection_age_seconds` | MarketData | Estado `OK`; idade < 60 s | Frescor dos dados (NFR9.5, NFR1.2) |
| `scanner_list_errors_total` | Scanner | Acompanhar | Falhas da lista |
| Pool de conexões (ativas, pendentes) | API (HikariCP) | Pendentes > 0 por mais de 2 s | Esgotamento do pool antes do `503` |
| Memória da JVM e uso de CPU | API | Heap < 80% do máximo | Dimensionamento local |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Saúde da API `DOWN` | Prontidão responde 503 (banco indisponível ou migrações não aplicadas) | Alta | Script de verificação falha com código diferente de zero; Docker Compose mostra o contêiner como não saudável |
| Auditoria atrasada | `audit_outbox_oldest_age_seconds` > 60 s | Média | Estado degradado no corpo da saúde (HTTP 200, contêiner continua saudável); log `ERROR` do relay; o smoke falha se encontrar esse estado |
| Fonte sem coletar | `market_source_last_collection_age_seconds` > 60 s | Média | Estado degradado no corpo da saúde (HTTP 200); log `WARN` da coleta; o smoke falha se encontrar esse estado |
| p95 acima da meta | p95 > 300 ms no teste de carga | Média | Falha do workflow manual de carga |

Sem notificação por e-mail ou chat no ambiente local; definida com a hospedagem e a operação.

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Latência p95 da API (desafio, verificação, lista) | ≤ 300 ms sob 20 usuários (meta de teste, não SLO de produção) | 5 minutos do teste de carga |
| Disponibilidade | Sem SLO na U1 (NFR10.1); definido com a hospedagem, antes dos convites | — |
| Frescor da fonte simulada | Idade ≤ 60 s | Contínuo, medido no smoke |
| Atraso da auditoria | Pendência mais antiga ≤ 60 s | Contínuo, medido no teste de integração |

## Logs & Tracing

- Logs JSON na saída padrão de cada contêiner; no ambiente local, lidos com `docker compose logs`. Retenção pelo driver `json-file` do Docker com `max-size: 10m` e `max-file: 3` por serviço (limite por tamanho, não por idade); a retenção de 7 dias de [observability-requirements.md](../nfr-requirements/observability-requirements.md) vira meta do agregador de logs da hospedagem.
- Campos mínimos: `timestamp` (UTC), `level`, `component`, `message`, `correlationId`. Mascaramento do endereço na origem e no formatador (Q9 do NFR Design).
- A CI guarda os logs do smoke como artefato da execução por 7 dias, depois de passar pela varredura de segredos e endereços (NFR7.3).
- Sem rastreamento distribuído; o `correlationId` liga os logs de uma requisição.
- Painéis: nenhum na U1. Com a hospedagem, um painel com RED por rota, atraso da auditoria e frescor da fonte.
