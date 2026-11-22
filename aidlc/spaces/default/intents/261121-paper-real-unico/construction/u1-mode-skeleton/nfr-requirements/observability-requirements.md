# Requisitos de observabilidade — U1 `u1-mode-skeleton`

A U1 passa a medir duas coisas, sem dado pessoal, e não acrescenta alarme [Q5].

## Convenção de nomes

As métricas do código usam Micrometer, exportadas em `/actuator/prometheus` (porta de gestão 8081). O nome do Micrometer leva o prefixo do módulo dono (por exemplo, `bots.cycle.errors` no `bots`, `real_orders` no `realexecution`), e o exportador do Prometheus troca o ponto por `_` e acrescenta `_total` aos contadores (`bots_cycle_errors_total`, `real_orders_total`). As etiquetas levam só valores de enum, com poucas séries. As duas métricas novas seguem essa convenção, com o prefixo do módulo que as grava.

## Métricas novas

| ID | Requisito | Nome no Micrometer | Nome exportado (Prometheus) | Etiquetas e valores | Onde é incrementada |
|---|---|---|---|---|---|
| NFR3.10 | Contar as recusas REAL na criação por motivo. | `bots.real.create.refusals` | `bots_real_create_refusals_total` | `reason` ∈ {`KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`} (5 séries no máximo) | no `bots`, uma vez por resposta 409 `REAL_NOT_ELIGIBLE` de `POST /api/v1/bots`, depois da gravação da auditoria (NFR3.5); a repetição com a mesma chave que reavalia e recusa de novo conta de novo |
| NFR4.6 | Contar os blocos `UNAVAILABLE` de C4 por modo. | `pnl.mode_block.unavailable` | `pnl_mode_block_unavailable_total` | `market` ∈ {`PAPER`, `REAL`} (2 séries no máximo) | no `pnl`, uma vez por bloco devolvido com `status: UNAVAILABLE` em `GET /api/v1/pnl/by-mode`; quando os dois modos falham, as duas séries sobem 1 |

Critérios mensuráveis das duas métricas:

- 0 etiquetas com tenant, pessoa, bot, carteira, endereço ou `correlationId`; no máximo 7 séries novas por processo;
- teste de unidade de cada uma: depois de 1 recusa por motivo, cada série de `reason` vale 1; depois de uma falha injetada no REAL, `pnl_mode_block_unavailable_total{market="REAL"}` vale 1 e a de `PAPER` não existe ou vale 0;
- teste de integração confere que as duas aparecem em `/actuator/prometheus` com os nomes exportados acima.

Os 503 da criação REAL e de C2 e C3 não ganham métrica nova: eles já aparecem em `http_server_requests_seconds_count{uri=...,status="503"}`, que já tem histograma ligado em `application.yml`.

## Logs

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.11 | Os registros de log da U1 (recusa REAL, bloco `UNAVAILABLE`, falha da fonte da elegibilidade) trazem o `correlationId`, o motivo ou o `market` e a categoria do erro, em inglês, no nível adequado: recusa de domínio em `INFO`, bloco `UNAVAILABLE` e 503 em `WARN` com a causa técnica. | Teste de captura de log: cada um dos 3 eventos gera 1 registro com o `correlationId` da resposta; 0 registros com endereço de carteira, token, cabeçalho `Authorization`, nonce ou assinatura; 0 `catch` vazios no código da U1. |
| NFR3.12 | O `correlationId` do corpo RFC 7807 (409, 503) e do bloco `UNAVAILABLE` é o mesmo do log daquele pedido. | Teste de integração compara o `correlationId` da resposta com o do registro capturado: igualdade em 100% dos casos testados. |

## Rastreamento distribuído

| ID | Requisito | Valor |
|---|---|---|
| NFR3.13 | A U1 não acrescenta rastreamento distribuído. | 0 bibliotecas de tracing novas; a correlação entre resposta e log é o `correlationId` (NFR3.12). A U1 não chama outro processo (NFR1.8). |

## Alarmes e painéis

| ID | Requisito | Valor |
|---|---|---|
| NFR8.5 | Nenhum alarme novo entra na U1. | 0 regras novas em `infra/observability/*.yml` (o `promtool check rules` do job `backend` continua com o mesmo número de regras); 0 alarmes novos do CloudWatch em `infra/terraform/`; 0 painéis novos. As duas métricas ficam disponíveis para um alarme futuro, decidido em outro estágio. |

## SLI e SLO

| ID | Requisito | Valor |
|---|---|---|
| NFR1.14 | O SLI de latência das rotas da U1 é o p95 do `http.server.requests` por `uri`, que já existe; a U1 não declara SLO operacional novo. | Na U1, o p95 de NFR1.1 a NFR1.4 é medido e registrado no log pelo teste de integração, sem portão (NFR1.5); o portão de 1.000 ms de C4 é o cenário de carga da CI da U2, e C1, C2 e C3 ficam só com o registro (NFR1.6); num ambiente com usuários, a latência só é observada depois que a U2 for implantada junto (NFR8.4). |

## Sources

- `nfr-requirements-questions.md`: Q5 e resumo confirmado [Q5].
- `inception/requirements-analysis/requirements.md`: NFR3 (sem endereço de carteira), NFR4, NFR8 (custo).
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR2.4, BR2.5, BR5.3, BR5.8.
- `inception/contract-design/contract-summary.md`: C1 a C4 (`correlationId` no RFC 7807 e em `ModeBlockUnavailable`).
- Código lido: `bots/worker/CycleFailureRecorder.java` (`bots.cycle.errors`), `botwallet/infra/RealOpsMetrics.java` (`real_release_blocks` com `reason`), `identity/infra/TermsMetrics.java`, `infra/observability/*.yml` (nomes exportados com `_total`), `application.yml` (`management`, Prometheus, histograma de `http.server.requests`).
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, nonces e assinaturas).
- `aidlc/spaces/default/memory/team.md`: Code Style (erros da API, sem falha silenciosa, idioma dos logs).

## Assumptions & Open Questions

1. [assumption] O pedido de despacho deu como exemplo `memetrade_real_create_refused_total{reason}` e `memetrade_pnl_block_unavailable_total{market}`. O código não usa o prefixo `memetrade_` nos contadores de módulo (só no `identity`, em `memetrade.terms.*` e `memetrade.purge.*`); os módulos mais novos usam o prefixo do próprio módulo. Por isso os nomes são `bots_real_create_refusals_total{reason}` e `pnl_mode_block_unavailable_total{market}`. O Code Generation pode trocar o nome só com uma decisão registrada.
2. [assumption] A etiqueta de modo se chama `market`, igual ao campo da API e do domínio (`BotMarket`), e não `mode`, que está obsoleto (BR1.3).
