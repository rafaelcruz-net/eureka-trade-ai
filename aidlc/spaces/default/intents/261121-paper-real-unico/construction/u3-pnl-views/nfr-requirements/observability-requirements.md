# Requisitos de observabilidade — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

A U3 passa a medir três coisas, sem dado pessoal: as recusas `TEMPLATE_MISMATCH` de C6 [Q4], os lados `UNAVAILABLE` de C6 (revisão R-06) e as falhas de gravação da auditoria nova [fundador D-NFR1]. Ela não acrescenta alarme, painel nem tracing [Q4].

## Convenção de nomes

Mesma convenção da U1 e da U2: Micrometer, exportado em `/actuator/prometheus` (porta de gestão 8081 na API). O nome no Micrometer leva o prefixo do módulo dono, e o exportador do Prometheus troca o ponto por `_` e acrescenta `_total` aos contadores (por exemplo, `pnl.mode_block.unavailable` → `pnl_mode_block_unavailable_total`). O dono de C5 e de C6 é o `pnl` (`pnl.web` e `pnl.modeview.ModePnlView`, contrato), então os contadores novos levam o prefixo `pnl`, e os de C6 o prefixo `pnl.compare`, para ficarem lado a lado. As etiquetas levam só valores de enum.

## Métricas novas

| ID | Requisito | Nome no Micrometer | Nome exportado (Prometheus) | Etiquetas e séries | Onde é incrementada |
|---|---|---|---|---|---|
| NFR3.9 | Contar as recusas 422 `TEMPLATE_MISMATCH` de C6 (BR5.2) [Q4]. | `pnl.compare.template_mismatches` | `pnl_compare_template_mismatches_total` | nenhuma (1 série) | no `pnl`, processo da API, +1 por resposta 422 `VALIDATION_FAILED` com `reason` `TEMPLATE_MISMATCH` de `GET /api/v1/pnl/compare`, depois da verificação de posse, em toda recusa (também dentro da hora em que a auditoria não grava, NFR3.14). Um 404 e um 400 não contam. |
| NFR3.15 | Contar os lados `UNAVAILABLE` de C6 por modo e causa (BR5.5; revisão R-06). | `pnl.compare.side_unavailable` | `pnl_compare_side_unavailable_total` | `market` ∈ {`PAPER`, `REAL`}; `cause` ∈ {`TIMEOUT`, `REJECTED`, `ERROR`, `RECONCILIATION_MISMATCH`, `TOO_MANY_ORDERS`}, o mesmo enum `PnlModeMetrics.Cause` (10 séries) | no `pnl`, +1 por lado devolvido com `status: UNAVAILABLE` em C6; quando os dois lados falham, as duas séries sobem 1. |
| NFR3.16 | Contar as falhas de gravação dos eventos de auditoria da U3 (NFR3.14) [fundador D-NFR1]. | `pnl.read_audit.failures` | `pnl_read_audit_failures_total` | `action` ∈ {`TENANT_MISMATCH`, `PNL_COMPARE_TEMPLATE_MISMATCH`} (2 séries) | no `pnl`, +1 por gravação que falhou, passou de 200 ms ou foi recusada pela fila cheia. |

Escolha de NFR3.15: um contador novo, e não uma etiqueta `route` no `pnl.mode_block.unavailable`. A etiqueta mudaria as 10 séries que já existem e o significado do contador ("blocos de C4"), que é a base dos alarmes; o contador novo mantém C4 intacto e segue o prefixo `pnl.compare` de NFR3.9.

Critérios mensuráveis:

- 13 séries novas por processo (1 + 10 + 2), todas registradas com 0 na subida do processo; 0 etiquetas com tenant, pessoa, bot, `templateId`, carteira ou `correlationId`;
- testes de unidade: depois de 2 recusas `TEMPLATE_MISMATCH`, NFR3.9 vale 2, e 1 resposta 404 mais 1 resposta 400 não o mudam; depois de uma falha injetada por tempo esgotado no lado REAL de C6, `pnl_compare_side_unavailable_total{market="REAL",cause="TIMEOUT"}` vale 1, as outras 9 séries valem 0 e `pnl_mode_block_unavailable_total` não muda; com a gravação da auditoria falhando, `pnl_read_audit_failures_total{action="TENANT_MISMATCH"}` vale 1;
- teste de integração confere que as três aparecem em `/actuator/prometheus` com os nomes exportados acima, com 0 antes do primeiro evento.

`pnl_mode_block_unavailable_total{market,cause}` continua contando só os blocos `UNAVAILABLE` de C4, também quando a leitura vem com `market` ou com `RANGE`. Os 400 e 404 de C5 e C6 já aparecem em `http_server_requests_seconds_count{uri=...,status=...}`.

## Logs

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.10 | Os registros de log da U3 trazem o `correlationId`, o motivo em enum e a categoria do erro, em inglês, no nível adequado: 422 `TEMPLATE_MISMATCH` e 404 de C5 e C6 em `INFO` (o 404 de outro tenant com o motivo `TENANT_MISMATCH`); lado `UNAVAILABLE` de C6 e falha técnica de C5 em `WARN`, com a causa técnica; falha da gravação da auditoria em `ERROR` (NFR3.14). | Teste de captura de log: cada um dos 5 eventos gera 1 registro com o `correlationId` da resposta; 0 registros com endereço de carteira, `templateId` de bot de outra pessoa, token, cabeçalho `Authorization`, nonce ou assinatura; 0 `catch` vazios no código da U3. |
| NFR3.11 | O `correlationId` do corpo RFC 7807, do lado `UNAVAILABLE` de C6, do bloco `UNAVAILABLE` de C4 e do evento de auditoria é o mesmo do log daquele pedido, e é o "Código para o suporte" que a tela mostra (BR3.3, BR5.5). | Teste de integração: igualdade entre resposta, evento e log em 100% dos casos testados. Teste de componente: o código mostrado é o `correlationId` da fixture. |

## Rastreamento distribuído

| ID | Requisito | Valor |
|---|---|---|
| NFR3.12 | A U3 não acrescenta rastreamento distribuído nem telemetria no navegador. | 0 bibliotecas de tracing novas; 0 envios de telemetria do `apps/web`; a correlação entre tela, resposta, auditoria e log é o `correlationId` (NFR3.11). |

## Alarmes e painéis

| ID | Requisito | Valor |
|---|---|---|
| NFR8.4 | Nenhum alarme novo entra na U3 [Q4]. | 0 regras novas em `infra/observability/*.yml` (o `promtool check rules` do job `backend` continua com o mesmo número de regras e o `RealOpsAlarmParityTest` não muda); 0 alarmes novos do CloudWatch; as 3 métricas novas não entram em `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf`; 0 painéis novos. As métricas ficam no Prometheus para um alarme futuro, decidido em outro estágio. |

## SLI e SLO

| ID | Requisito | Valor |
|---|---|---|
| NFR1.16 | O SLI de latência das rotas da U3 é o p95 do `http.server.requests` por `uri` (`/api/v1/pnl/by-mode`, `/api/v1/bots`, `/api/v1/bots/{botId}/pnl/lines`, `/api/v1/pnl/compare`), que já tem histograma. A U3 não declara SLO operacional novo. | O portão de 1.000 ms de C5, C6 e C4 com `RANGE` está no cenário `pnl-by-mode` ampliado do workflow manual `load-test`, que fecha o Build and Test (NFR1.6) [fundador D-NFR2]; os demais casos ficam com o p95 registrado no log do teste de integração (NFR1.5). A repetição na devnet fica com o responsável, nos estágios de operação. Num ambiente com usuários, a latência é observada pelo mesmo SLI. |

## Sources

- `nfr-requirements-questions.md`: Q4 e o resumo confirmado [Q4].
- `reviews/review-01.md` (R-01, R-03, R-06) e as decisões do fundador de 2026-11-23 (D-NFR1 em `security-requirements.md`, D-NFR2 em `performance-requirements.md`).
- `inception/requirements-analysis/requirements.md`: NFR1 (carga e devnet), NFR3 (sem endereço de carteira), NFR8 (custo).
- `construction/u3-pnl-views/functional-design/rules.md`: BR3.3, BR5.2, BR5.3, BR5.5.
- `inception/contract-design/contract-summary.md`: C5, C6 (422 `TEMPLATE_MISMATCH`, 404 com `reason`), consumidores de `pnl.modeview.ModePnlView` (C4, C6).
- `construction/u1-mode-skeleton/nfr-requirements/observability-requirements.md` e `construction/u2-real-bot-pnl/nfr-requirements/observability-requirements.md`: convenção de nomes com o prefixo do módulo; NFR3.16 da U2 (contador de falha da auditoria).
- Código lido: `apps/api/src/main/java/com/memetrade/pnl/modeview/PnlModeMetrics.java` (`pnl.mode_block.unavailable`, enum `Cause`), `pnl/application/PnlRealMetrics.java`, `pnl/application/ReconciliationAuditGate.java`.
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos); `team.md`: Code Style (erros da API, sem falha silenciosa, idioma dos logs).

## Assumptions & Open Questions

1. [assumption] NFR3.9 fica sem etiqueta porque só existe um motivo (`TEMPLATE_MISMATCH`). Uma etiqueta `reason` só entra se outro motivo de 422 aparecer em C6.
2. [assumption] NFR3.15 reusa o enum `Cause` de C4, porque os lados de C6 são lidos pelo mesmo cálculo e falham pelas mesmas causas. Uma causa só de C6, se aparecer no NFR Design, entra no enum e soma 2 séries.
