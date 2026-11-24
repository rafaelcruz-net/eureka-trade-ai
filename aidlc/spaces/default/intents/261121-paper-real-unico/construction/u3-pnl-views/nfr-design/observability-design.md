# Desenho de observabilidade — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-01) e da decisão F-1 do fundador de 2026-11-23 (`security-design.md`).

A U3 acrescenta três contadores no módulo `pnl`, sem alarme novo, sem painel novo e fora da lista do CloudWatch. Os logs continuam em JSON com o `correlationId`. Não entra rastreamento distribuído, telemetria no navegador, infraestrutura nova nem configuração por variável de ambiente [Q4].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Contadores | `pnl.compare.template_mismatches` (1 série), `pnl.compare.side_unavailable{market,cause}` (10 séries), `pnl.read_audit.failures{action}` (2 séries); 13 séries registradas com 0 na subida | NFR3.9, NFR3.15, NFR3.16 |
| Registro | dois componentes do `pnl` que registram tudo no construtor, como o `PnlModeMetrics` da U1 | NFR3.9, NFR3.15, NFR3.16 |
| Contador de C4 | `pnl.mode_block.unavailable` continua contando só os blocos de C4 | NFR3.15 |
| Logs | JSON pelo encoder que já existe, com `correlationId` pelo MDC, motivo em enum e categoria | NFR3.10, NFR3.11 |
| Rastreamento | nenhum novo | NFR3.12 |
| Alarmes | nenhum | NFR8.4 |
| SLI | o histograma `http_server_requests_seconds` por `uri`, que já existe | NFR1.16 |

## Contadores e registro (NFR3.9, NFR3.15, NFR3.16)

### Nomes

| Nome no Micrometer | Nome exportado | Etiquetas | Séries |
|---|---|---|---|
| `pnl.compare.template_mismatches` | `pnl_compare_template_mismatches_total` | nenhuma | 1 |
| `pnl.compare.side_unavailable` | `pnl_compare_side_unavailable_total` | `market` ∈ {`PAPER`, `REAL`}; `cause` ∈ o enum `PnlModeMetrics.Cause` (`TIMEOUT`, `REJECTED`, `ERROR`, `RECONCILIATION_MISMATCH`, `TOO_MANY_ORDERS`) | 10 |
| `pnl.read_audit.failures` | `pnl_read_audit_failures_total` | `action` ∈ {`TENANT_MISMATCH`, `PNL_COMPARE_TEMPLATE_MISMATCH`} | 2 |

A resposta Q4 escreve o terceiro nome como `pnl.read_audit_failures`; o desenho usa o nome de NFR3.16, `pnl.read_audit.failures`. Os dois viram o mesmo nome exportado, `pnl_read_audit_failures_total`.

### Componentes que registram

| Componente | Pacote | Registra | Quem chama |
|---|---|---|---|
| `PnlCompareMetrics` (nome proposto) | `pnl.modeview` | `template_mismatches` e as 10 séries de `side_unavailable` | `PnlController` (C6, depois da verificação de posse) e a `ModePnlView`, pelo `sink` de C6 |
| `PnlReadAuditMetrics` (nome proposto) | `pnl.application` | as 2 séries de `read_audit.failures` | `ReadRefusalAudit` |

Os dois são `@Component` com `@ProcessRole(API)` e registram todas as séries no construtor, percorrendo os enums (`PnlMarket.values()`, `PnlModeMetrics.Cause.values()` e um enum `ReadAuditAction`), com `Counter.builder(...).tag(...).register(meters)`. Assim cada série existe com 0 antes do primeiro evento e os nomes nunca dependem de texto montado na hora. Contar um evento não é conta com valores PAPER ou REAL (NEVER_SUM_MODES).

### Onde cada um sobe

| Contador | Sobe quando | Não sobe quando |
|---|---|---|
| `template_mismatches` | toda resposta 422 `TEMPLATE_MISMATCH` de C6, depois da posse, inclusive dentro da hora em que a auditoria não grava | 404, 400 |
| `side_unavailable{market,cause}` | cada lado de C6 devolvido `UNAVAILABLE`; com os dois lados falhando, as duas séries sobem | C4 (vai para `pnl.mode_block.unavailable`) |
| `read_audit.failures{action}` | gravação que falhou, passou de 200 ms, ficou sem conexão ou foi recusada pela fila cheia; só para um 404 confirmado de outro tenant (decisão F-1 do fundador) ou um 422 | `WRITTEN`, `DEDUPLICATED`, `LOCK_BUSY`; um 404 de uuid inexistente (nunca chega ao executor); uma recusa pulada pelo filtro em memória de 60 minutos |

### Como C4 e C6 contam separados

A `ModePnlView` recebe um `UnavailableSink` (interface funcional `(PnlMarket, Cause) -> void`). C4 passa `PnlModeMetrics::unavailable`; C6 passa `PnlCompareMetrics::sideUnavailable`. A classificação da causa (`causeOf`) é a mesma para os dois. Assim `pnl_mode_block_unavailable_total` continua com o significado da U1 ("blocos de C4"), base dos alarmes, e o contador de C6 fica ao lado.

### Testes

- `PnlCompareMetricsTest` e `PnlReadAuditMetricsTest` (unidade, `SimpleMeterRegistry`): as 13 séries existem com 0 depois da construção; 2 recusas 422 dão 2, e 1 resposta 404 mais 1 resposta 400 não mudam o valor; uma falha injetada por prazo no lado REAL de C6 dá `{market="REAL",cause="TIMEOUT"} = 1`, as outras 9 séries 0 e `pnl.mode_block.unavailable` sem mudança; com a gravação falhando, `{action="TENANT_MISMATCH"} = 1`.
- `PnlViewsPrometheusIT` (integração): os três nomes exportados aparecem em `/actuator/prometheus` (porta 8081) com 0 antes do primeiro evento.

## Logs (NFR3.10, NFR3.11)

| Evento | Nível | Campos (além do `correlationId` do MDC) |
|---|---|---|
| 404 de C5 e de C6 | `INFO` | `event=PNL_READ_NOT_FOUND route=<BOT_PNL_LINES|PNL_COMPARE> side=<PAPER_BOT|REAL_BOT>` (só C6) `reason=<TENANT_MISMATCH|NOT_FOUND>` |
| 422 `TEMPLATE_MISMATCH` de C6 | `INFO` | `event=PNL_COMPARE_TEMPLATE_MISMATCH` |
| lado `UNAVAILABLE` de C6 | `WARN` | `event=PNL_COMPARE_SIDE_UNAVAILABLE market=<enum> cause=<rótulo>` |
| falha técnica de C5 (503) | `WARN` | `event=PNL_BOT_LINES_UNAVAILABLE cause=<rótulo>` |
| falha da auditoria da leitura | `ERROR`, no máximo 1 por minuto por pessoa e ação | `event=PNL_READ_AUDIT_FAILED action=<enum> category=<QUEUE_FULL|DEADLINE|CONNECTION_TIMEOUT|ERROR> exceptionClass=<nome>` |

- **Um 404 de outro tenant no log.** Com a decisão F-1 do fundador, o motivo é conhecido na thread do pedido: a consulta `ownedByAnotherTenant` roda nos dois 404, antes da resposta. O log `INFO` traz `reason=TENANT_MISMATCH` ou `reason=NOT_FOUND`; é o mesmo registro, com um valor de enum diferente, e não muda o tempo da resposta de forma mensurável (o `BotNotFoundTimingParityIT` mede com o log ligado).
- **Log de falha limitado.** O `ERROR` de uma auditoria descartada ou que falhou sai no máximo 1 vez por minuto por pessoa e ação, com o mapa limitado do `ReconciliationAuditGate` (10.000 chaves); o contador sobe em toda falha. A `category` separa a fila cheia (`QUEUE_FULL`) da falha de gravação.
- **Sem dado.** O rótulo `cause` é um valor fechado ou o nome simples da classe da falha, nunca a mensagem. Nenhum endereço de carteira, `templateId` de outra pessoa, token, cabeçalho `Authorization`, nonce ou assinatura. O `PnlViewsLogCaptureTest` captura os 5 eventos de NFR3.10 e confere 1 registro por evento, com o `correlationId` da resposta, e 0 registros com esses dados.
- **Mesmo `correlationId` em tudo (NFR3.11).** O `correlationId` do corpo RFC 7807, do lado `UNAVAILABLE` de C6, do bloco `UNAVAILABLE` de C4, do evento de auditoria e do log é o `CorrelationId.current()` lido na thread do pedido e levado às tarefas pelo MDC copiado (`ModeBlockExecutor` e `ReadRefusalAudit`). A tela mostra esse valor como "Código para o suporte". O `PnlViewsCorrelationIT` confere a igualdade entre resposta, evento e log.
- **Sem `catch` vazio.** Toda exceção capturada nos componentes da U3 vira resultado explícito (bloco `UNAVAILABLE`, 503, `RequestCancelled` ignorado de propósito na tela) ou log com contador.

## Rastreamento (NFR3.12)

0 bibliotecas de rastreamento novas e 0 envios de telemetria do `apps/web`. A correlação entre tela, resposta, auditoria e log é o `correlationId`.

## Alarmes e painéis (NFR8.4)

0 regras novas em `infra/observability/*.yml` (o `promtool check rules` do job `backend` conta as mesmas regras, e o `RealOpsAlarmParityTest` não muda). As 3 métricas não entram em `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf`, então não aumentam a ingestão do CloudWatch. 0 painéis novos. Elas ficam no Prometheus para um alarme decidido em outro estágio.

## SLI (NFR1.16)

O SLI de latência das rotas da U3 é o p95 de `http_server_requests_seconds` por `uri`: `/api/v1/pnl/by-mode`, `/api/v1/bots`, `/api/v1/bots/{botId}/pnl/lines` e `/api/v1/pnl/compare`. O histograma já existe (`management.metrics.distribution` do `application.yml`). Nenhum SLO operacional novo. O portão de 1 s de C5, C6 e C4 com `RANGE` está no cenário `pnl-by-mode` ampliado (`performance-design.md`), que mede por rótulo no cliente porque o histograma por `uri` não separa os períodos.

## Sources

- `nfr-design-questions.md` desta pasta: Q2, Q4 e o resumo confirmado [Q2] [Q4].
- `construction/u3-pnl-views/nfr-requirements/observability-requirements.md`: NFR3.9 a NFR3.12, NFR3.15, NFR3.16, NFR8.4, NFR1.16.
- `construction/u3-pnl-views/nfr-requirements/security-requirements.md`: NFR3.14, NFR3.17.
- `construction/u1-mode-skeleton/nfr-design/observability-design.md` e `construction/u2-real-bot-pnl/nfr-design/observability-design.md`: convenção de nomes e registro com 0.
- Código lido: `apps/api/.../pnl/modeview/PnlModeMetrics.java`, `pnl/modeview/ModePnlView.java`, `pnl/modeview/ModeBlockExecutor.java` (MDC), `pnl/application/ReconciliationAuditGate.java` (log `REAL_PNL_MISMATCH_AUDIT_FAILED`), `apps/api/src/main/resources/application.yml`.
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos); `team.md`: Code Style (idioma dos logs, sem falha silenciosa).

## Assumptions & Open Questions

1. [assumption] O contador `pnl_read_audit_failures_total` mantém só a etiqueta `action` de NFR3.16 (2 séries); a separação entre fila cheia e falha de gravação fica na `category` do log, sem séries novas.
2. [assumption] Uma causa nova só de C6 não aparece neste desenho; os lados de C6 falham pelas mesmas causas do bloco de C4, então o enum `Cause` serve aos dois.
