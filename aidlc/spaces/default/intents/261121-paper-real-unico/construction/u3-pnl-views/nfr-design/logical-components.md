# Componentes lógicos — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` e das decisões do fundador de 2026-11-23 (F-1 a F-3).

Inventário dos componentes que a U3 cria ou muda, onde cada padrão de NFR se aplica, os domínios de falha e o raio de impacto. A U3 não cria processo, pool de conexões, tabela nem infraestrutura; ela cria um executor pequeno de auditoria e três contadores, e reaproveita o pool `pnl-mode-block` da U1 e o publicador limitado da U2 [Q1] [Q2] [Q4].

## Processos e recursos compartilhados

| Recurso | Muda? | Uso pela U3 |
|---|---|---|
| Processo `api` (Spring Boot) | não | C4 com `market` e `RANGE`, `listBots`, C5, C6, a auditoria das recusas |
| Processo `worker` | não | nenhum uso novo |
| PostgreSQL 17 | não | 0 tabelas, 0 índices, 0 migrações |
| Hikari principal (20 conexões na API) | não | as leituras, pelo pool `pnl-mode-block`; `listBots` e a verificação de existência, curtas |
| Pool `pnl-mode-block` (8 threads, fila de 16) | não | blocos de C4, a tarefa de C5, os lados de C6 |
| Pool `pnl-mismatch-audit` (2 conexões, espera de 250 ms) | não | também a gravação dos eventos `TENANT_MISMATCH` e `PNL_COMPARE_TEMPLATE_MISMATCH` |
| Executor `pnl-read-audit` (1 thread, fila de 16) | novo, na API | só a auditoria das recusas da U3 |
| Prometheus (`/actuator/prometheus`, porta 8081) | +13 séries | os três contadores |
| CloudWatch, Terraform | não | nenhum |
| Canal ao vivo (`DashboardMarkPoller`, fetch em leitura contínua) | não | o painel assina mais dois tipos que o canal já envia |
| TxSigner, KMS, RPC | não | nenhum uso |

## Componentes do backend

| Componente | Módulo e pacote | Novo ou muda | Responsabilidade | Padrões de NFR |
|---|---|---|---|---|
| `PnlController` | `pnl.web` | muda | C4 com `market` e `RANGE`; C5; C6; ordem 400 → posse → 404 → 422; `refuseNotFound` chama `ownedByAnotherTenant` nos dois 404 | validação na fronteira, caminho único dos 404, tenant do token |
| `ModePnlDtos` | `pnl.web` | muda | `paper` e `real` opcionais (`NON_ABSENT`); DTOs de C5 e C6 | NEVER_SUM_MODES, contrato gerado |
| `ModePnlView` | `pnl.modeview` | muda | `statement(window, …, sink)` e `single(market, window, …, sink)`; dois blocos ou lados em paralelo com prazo | antepara, prazo contado do envio, degradação por bloco |
| `ModeBlockExecutor` | `pnl.modeview` | não | pool `pnl-mode-block` | antepara, fila limitada, MDC |
| `PnlCompareMetrics` (nome proposto) | `pnl.modeview` | novo | `pnl.compare.template_mismatches` e `pnl.compare.side_unavailable{market,cause}` | registro com 0, etiquetas de enum |
| `PaperModeBlockReader` | `pnl.application` | muda | aceita `PnlWindow.Range`; `lineOf(person, bot, window)` usado pelo bloco e pelo lado PAPER de C6 | mesmo cálculo, igualdade exata |
| `RealBotPnl` | `pnl.application` | muda | `botLines(tenant, pessoa, botId, cursor, limit)`: a passada da U2 com um coletor das linhas do bot; corte da página pela âncora | leitura em fluxo, prazo do bloco, sem trava |
| `RealBotPnlQuery` | `pnl.api` | muda (C10, aditivo) | `botLines` e os tipos `RealPnlLinePage` e `RealPnlLineItem` | contrato de esquema compartilhado |
| `BotLinesCursor` (nome proposto) | `pnl.application` | novo | codificar e validar o cursor de 44 caracteres; resumo da identidade da linha | opaco, sem dado sensível, 400 estrito |
| `ReadRefusalAudit` (nome proposto) | `pnl.application` | novo | filtro em memória de 60 minutos por pessoa e ação; executor `pnl-read-audit`; tarefas `tenantMismatch` (só casos positivos) e `templateMismatch`; impressão digital; contagem e log limitado das falhas | assíncrono sem espera, fila limitada, descarte contado, log com limite de taxa |
| `PnlReadAuditMetrics` (nome proposto) | `pnl.application` | novo | `pnl.read_audit.failures{action}` | registro com 0 |
| `BotDirectory` | `pnl.api` (C8), implementado em `bots` | muda (aditivo) | `boolean ownedByAnotherTenant(tenantId, botId)`: só diz se o id enviado existe em outro tenant | a única consulta fora do tenant de quem pede (decisão F-2), restrita por ArchUnit |
| `AuditActions` | `audit.api` | muda | ação nova `PNL_COMPARE_TEMPLATE_MISMATCH` | trilha só de inserção |
| `RateLimitedAuditPublisher` e `MismatchAuditWriter` | `audit.api`, `audit.infra` | não (só o javadoc) | limite de 1 por hora pela própria trilha | trava consultiva, sem estado em memória |
| `BotController.list` e a consulta de página | `bots.web`, `bots.application`, `bots.infra` | muda | `market`; `matchingCount` e `totalCount` | tenant em cada consulta |
| `openapi-guest.yaml` | contrato | muda | C4 (`market`, chaves opcionais), `listBots` (`market`, contagens), C5 (com o 503), C6 | contrato gerado, quebra falha a CI |

## Componentes do `apps/web`

| Componente | Lugar | Novo ou muda | Padrões de NFR |
|---|---|---|---|
| `client.ts` | `lib/api` | muda: `signal` e `RequestCancelled`, sem repetir um cancelamento; `retry: false` por chamada | cancelamento de pedido, sem repetição em C5 e na tabela por token |
| `guest-api.ts` | `lib/api` | muda: `market`, `period`, `cursor` e `{ signal }` nas funções da U3 | contrato gerado |
| `pnl-screen-state.ts` | `features/pnl` | novo: reducer puro de `/pnl` com vagas e `requestId` | resposta antiga descartada |
| `PnlScreen` | `features/pnl` | muda: `useReducer`, vagas com `AbortController`, `PeriodSelector`, `ComparisonView`, "Tentar de novo" com `market` | 1 pedido por bloco |
| `PeriodSelector`, `ProratedLines`, `BotPnlLines`, `ComparisonView` | `features/pnl` | novos | estados distintos, foco, 360 px |
| `PnlModeBlock` | `features/pnl` | muda: variantes `compact` e `bot`, parcelas e "Sem bot" | só formata |
| `by-mode.ts` | `features/pnl` | muda: chave ausente sem erro | degradação por bloco |
| `concurrency.ts` | `features/dashboard` | muda: `windowedRefresh` | 30 s por cartão |
| `dashboard-reducer.ts` | `features/dashboard` | muda: fatia `modeCards` | estado por cartão |
| `DashboardModeCards` | `features/dashboard` | novo | um temporizador por cartão; anúncio limitado |
| `DashboardProvider` | `features/dashboard` | muda: assina `real.order.state` e `real.conditional.state` | canal sem mudança |
| `BotModeFilter`, `BotList`, `BotDetail` | `features/bots` | novo e muda | filtro no endereço, contagens, 404 sem dado |

## Componentes de carga

| Arquivo | Mudança |
|---|---|
| `scripts/load/pnl-by-mode.mjs` | ciclo de 9 leituras rotuladas, p95 por rótulo no cliente, 7 portões novos |
| `scripts/load/load-test.mjs` | escolha do bot REAL e do par por pessoa, relatório `docs/perf/u3-pnl-views-<data>.md` |
| `scripts/load/load-test-pnl-by-mode.test.mjs` | testes dos portões novos |
| `code-summary.md` da Code Generation da U3 | texto pronto do `load-test.yml`, que substitui o da U2 |

## Domínios de falha e raio de impacto

| Falha | Onde fica | Raio de impacto | O que a pessoa vê |
|---|---|---|---|
| Um bloco de C4 passa do prazo, a fila do pool enche ou a conferência REAL diverge | `ModePnlView` | só aquele bloco | o bloco com "P&L indisponível agora" e o código; o outro bloco intacto |
| Um lado de C6 falha | `ModePnlView`, sink de C6 | só aquele lado | erro e "Tentar de novo" só no lado |
| A passada de C5 falha | `PnlController` (C5) | só as linhas do detalhe | erro com código; o cabeçalho e o bloco do bot continuam |
| A página seguinte de C5 falha | tela | só a página nova | as linhas já mostradas ficam; com o cursor recusado, "Recarregar do início" |
| Rajada de 404 com uuid aleatório | `PnlController` | nenhuma fila: só 1 consulta pela chave primária por pedido | o 404 de sempre; nada entra no executor |
| O pool `pnl-mode-block` satura | pool | as leituras de P&L, não o resto da API | blocos `REJECTED`; 12 das 20 conexões continuam livres |
| A auditoria falha ou a fila enche | `ReadRefusalAudit` | nenhuma resposta | nada; log `ERROR` e contador para o responsável |
| O `pnl-mismatch-audit` fica sem conexão | escritor da U2 | a gravação de eventos limitados da U2 e da U3 | nada; falha contada nos dois contadores |
| Uma resposta antiga chega depois | tela | nenhum | descartada pelo `AbortController` e pelo `requestId` |
| Rajada de eventos do canal ao vivo | `windowedRefresh` | o cartão do modo do evento | no máximo 2 releituras a cada 30 s |

Nenhuma falha da U3 chega à execução de ordens, à barreira, ao TxSigner ou ao saque: as leituras não tomam trava, e as tarefas novas não rodam no worker.

## Fronteiras de módulo (ArchUnit)

| Regra | Novo ou mantém |
|---|---|
| `PNL_USES_ONLY_THE_AUDIT_API`: o `pnl` chega à auditoria só por `audit.api` | mantém; o `ReadRefusalAudit` usa `RateLimitedAuditPublisher` e `AuditEventCommand` |
| `MISMATCH_POOL_ONLY_IN_ITS_WRITER` | mantém |
| `bots -> pnl.api` e nunca `pnl -> bots.api` | mantém; `ownedByAnotherTenant` fica na C8 (`pnl.api.BotDirectory`) |
| `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`: só o caminho dos 404 do `PnlController` chama `BotDirectory.ownedByAnotherTenant` | **nova** (decisão F-2) |
| os tipos de C5 e C6 na lista fechada de NEVER_SUM_MODES | mantém, com os tipos novos no mesmo commit |
| ponto flutuante proibido no `pnl` | mantém |

## Ordem de construção

1. Testes do núcleo financeiro antes do código (NFR7.7): igualdade dos lados de C6 com C4, soma exata das páginas de C5, estabilidade do cursor, limites do `RANGE`, "nenhum terceiro número" de C6.
2. Backend: janela e modo único na `ModePnlView` com o `sink`; `PaperModeBlockReader` com `RANGE` e `lineOf`; `botLines` e o cursor; C4, C5 e C6 no controlador; `listBots` com `market` e contagens; `ReadRefusalAudit`, a ação nova, `ownedByAnotherTenant` e os contadores; o teste de paridade de tempo.
3. Contrato e `apps/web` no mesmo commit: `openapi-guest.yaml`, `schema.d.ts`, `client.ts` com `signal`, `by-mode.ts`, telas e reducers.
4. Spec `e2e/zz-paper-real-unico` ampliado e `bash scripts/verify.sh`.
5. Cenário de carga ampliado e o texto pronto do `load-test.yml`.

## Alinhamentos para a Code Generation

Decisões do fundador de 2026-11-23 (F-1 a F-3, em `security-design.md` e `performance-design.md`) e correções da revisão 01. A Code Generation aplica cada linha.

| # | Artefato | Alinhamento |
|---|---|---|
| A1 | `nfr-design-questions.md` Q2 e NFR3.17 | **Mudança registrada (F-1).** A Q2 e o NFR3.17 descreviam a verificação "existe em outro tenant" depois da resposta, dentro da tarefa. Agora ela roda na thread do pedido, nos dois 404, pela mesma consulta `ownedByAnotherTenant`; só um resultado positivo vai ao executor. A regra de paridade do NFR3.17 (mesmo caminho, mesma medida de 5 ms ou 10%) não muda. |
| A2 | `inception/contract-design/contract-summary.md`, C8 | **Exceção ao filtro de tenant (F-2).** C8 ganha `boolean ownedByAnotherTenant(UUID tenantId, UUID botId)`, a única consulta autorizada a olhar fora do tenant de quem pede; devolve só um booleano, nunca dado do bot; só o caminho dos 404 da U3 a chama (ArchUnit `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`). |
| A3 | `team.md` (Code Style, "Tenant no código") e NFR3.1 | A exceção de A2 entra anotada pelo ritual de aprendizado, mais tarde. Este estágio não edita o `team.md`. |
| A4 | `nfr-design-questions.md` Q3 | **Premissa errada, reescrita aceita (F-3).** A Q3 supôs um reducer na tela de P&L; o `PnlScreen` usa `useState`. O fundador aceitou `pnl-screen-state.ts` com `useReducer`, sem biblioteca nova. Os testes da U1 de `/pnl` ficam verdes; os casos do `PnlScreen.test.tsx` presos ao formulário antigo e à tabela por token na abertura foram ajustados ao "Desde o início" por padrão, sem apagar nenhum (conflito 2 do plano da Code Generation); o `PnlModeBlock.test.tsx` ficou sem edição. |
| A5 | `nfr-requirements/performance-requirements.md`, NFR1.6 | Texto do NFR1.6 a manter: ciclo de **9 leituras** (C4 `ALL` e `GET /api/v1/real/pnl` da U2; C4 `RANGE` de 30 e de 366 dias; C5 1ª página e página seguinte; C6 `ALL`, `RANGE` de 30 e de 366 dias) e **7 portões novos** de p95 ≤ 1.000 ms, um por leitura nova, mais os portões da U2 sem mudança. As colunas "Portão" de NFR1.1, NFR1.3 e NFR1.4 seguem esses números. |
| A6 | `construction/u2-real-bot-pnl/code-generation/code-summary.md` | A Code Generation da U3 edita esse arquivo **só** para acrescentar uma linha de ponteiro para o texto pronto do `load-test.yml` no `code-summary.md` da U3. O responsável aplica só o texto da U3. |
| A7 | `openapi-guest.yaml`, C5 | O 503 `SERVICE_UNAVAILABLE` de C5 entra no contrato, no mesmo commit do leitor do `apps/web` (NFR7.8). |
| A8 | `guest-api.ts` | `retry: false` em `listBotPnlLines` (C5) e em `getPnl` (tabela por token); teste do cliente com exatamente 1 `fetch` num 503 (revisão R-02). |
| A9 | `BotPnlLines` e `functional-spec.md` (máquina de estados das linhas) | Fase `cursorStale` com "Recarregar do início" para um 400 de `cursor` na página seguinte (revisão R-04). |
| A10 | `RateLimitedAuditPublisher` (javadoc) | Passa a citar a U3 como segundo usuário. |

## Sources

- `nfr-design-questions.md` desta pasta: Q1 a Q4 e o resumo confirmado [Q1] [Q2] [Q3] [Q4].
- `reviews/review-01.md` (R-01 a R-07) e as decisões do fundador de 2026-11-23 (F-1 a F-3).
- `construction/u3-pnl-views/nfr-requirements/` (todos os arquivos) e `functional-design/frontend-components.md`.
- `construction/u1-mode-skeleton/nfr-design/logical-components.md` e `construction/u2-real-bot-pnl/nfr-design/logical-components.md`.
- `inception/contract-design/contract-summary.md`: C4, C5, C6, C8, C10.
- Código lido: `apps/api/.../pnl/web/PnlController.java`, `pnl/modeview/*`, `pnl/application/{RealBotPnl,PaperModeBlockReader,ReconciliationAuditGate}.java`, `pnl/api/{BotDirectory,RealBotPnlQuery}.java`, `audit/api/RateLimitedAuditPublisher.java`, `audit/infra/MismatchAuditWriter.java`, `bots/web/BotController.java`, `apps/api/src/test/java/com/memetrade/architecture/AuditArchitectureTest.java`; `apps/web/src/lib/api/client.ts`, `features/pnl/*`, `features/dashboard/*`; `scripts/load/*`.
- `aidlc/spaces/default/memory/team.md`: Code Style (fronteiras de módulos por ArchUnit), Testing Posture (Ordering); `project.md`: Corrections (canal ao vivo por fetch, interfaces Java como contrato).

## Assumptions & Open Questions

1. [assumption] Os nomes marcados como "proposto" (`PnlCompareMetrics`, `PnlReadAuditMetrics`, `ReadRefusalAudit`, `BotLinesCursor`) podem mudar na Code Generation; os pacotes e as responsabilidades não.
2. [assumption] `PnlCompareMetrics` fica em `pnl.modeview`, ao lado do `PnlModeMetrics`, porque a `ModePnlView` recebe o `sink` de C6 dali; `PnlReadAuditMetrics` fica em `pnl.application`, com o `ReadRefusalAudit`.
