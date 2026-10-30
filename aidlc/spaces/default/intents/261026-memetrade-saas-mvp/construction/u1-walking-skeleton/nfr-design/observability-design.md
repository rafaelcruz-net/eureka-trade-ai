# Desenho de observabilidade — U1 Esqueleto de ponta a ponta

Como a Unit `u1-walking-skeleton` cumpre os requisitos de [observability-requirements.md](../nfr-requirements/observability-requirements.md) (NFR9.1 a NFR9.5, NFR7.3), para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) e as regras comuns de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (`correlationId`). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q9, Q10).

## Arquitetura

- Métricas pelo suporte do Spring Boot (Actuator com Micrometer), expostas no formato Prometheus numa porta de gestão separada da porta da API (Q10). A porta de gestão não é publicada fora da rede do Docker Compose, exceto para o script de verificação no ambiente local.
- Sem coletor no Docker Compose na U1; Prometheus, painéis e alertas entram com a hospedagem.
- Logs estruturados em JSON na saída padrão do contêiner.
- Sem rastreamento distribuído (decisão Q6 do NFR Requirements); o `correlationId` liga os logs de uma requisição.

## `correlationId` (NFR9.2)

1. Um filtro na entrada da API lê o cabeçalho `X-Correlation-Id`; se faltar ou não for um UUID válido, gera um novo.
2. O valor vai para o contexto de log (MDC) e aparece em todo log da requisição.
3. Volta no cabeçalho `X-Correlation-Id` da resposta e no campo `correlationId` do `ProblemDetail`.
4. Tarefas agendadas (coleta, relay, limpeza) geram um `correlationId` por ciclo.

## Logs (NFR9.3)

Campos mínimos: `timestamp` (UTC), `level`, `component`, `message`, `correlationId`.

| Nível | Exemplos na U1 |
|---|---|
| ERROR | Banco indisponível, ciclo do relay falhou |
| WARN | Coleta simulada falhou, `429` emitido |
| INFO | Login aceito ou recusado (motivo, endereço mascarado), coleta concluída, sessão revogada por reutilização |
| DEBUG | Desligado por padrão |

Proibido em log: token de acesso, token de renovação, cabeçalho `Authorization`, nonce, assinatura, segredo, endereço completo. O mascaramento usa as duas barreiras de [security-design.md](security-design.md) (Q9).

## Métricas (NFR9.1, NFR9.4, NFR9.5)

| Métrica | Tipo | Rótulos | Componente |
|---|---|---|---|
| `http_server_requests` | Histograma | rota, método, status | API |
| `login_attempts_total` | Contador | resultado (`accepted`, `rejected`), motivo | IdentityAccess |
| `rate_limit_rejections_total` | Contador | alvo (`ip`, `wallet`) | IdentityAccess |
| `session_refresh_reuse_total` | Contador | — | IdentityAccess |
| `audit_outbox_pending` | Medidor | — | AuditTrail |
| `audit_outbox_oldest_age_seconds` | Medidor | — | AuditTrail |
| `market_source_status` | Medidor (0 `OK`, 1 `FAILED`) | fonte | MarketData |
| `market_source_last_collection_age_seconds` | Medidor | fonte | MarketData |
| `scanner_list_errors_total` | Contador | tipo | Scanner |

Nenhum rótulo leva endereço, token ou identificador de pessoa, para evitar vazamento e cardinalidade alta.

## Saúde (NFR9.1)

- Saúde agregada da API na porta de gestão, com indicadores de banco, relay da auditoria (pendência mais antiga abaixo de 60 s) e fonte simulada (última coleta abaixo de 60 s).
- Relay atrasado ou fonte sem coletar deixam a saúde como degradada, sem derrubar a API; banco indisponível a deixa `DOWN`.

## SLI provisórios (sem SLO na U1)

| SLI | Medida | Referência |
|---|---|---|
| Latência da API | p95 de `http_server_requests` por rota | NFR3.1 (300 ms) |
| Erros da API | Proporção de status 5xx | Sem meta até a hospedagem |
| Atraso da auditoria | `audit_outbox_oldest_age_seconds` | Abaixo de 60 s |
| Frescor dos dados | `market_source_last_collection_age_seconds` | Abaixo de 60 s (NFR1.2) |

Os alertas com notificação, com limites abaixo da quebra de SLO, ficam para a hospedagem (NFR10.1).

## Varredura de logs nos testes (NFR7.3)

Um teste coleta os logs da suíte de integração e do smoke e falha se encontrar endereço completo, token de acesso ou renovação, nonce ou assinatura. Os valores procurados são os gerados pelos próprios testes, além do padrão Base58 de endereço.
