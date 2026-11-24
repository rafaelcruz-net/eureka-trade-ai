## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-24
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u3-pnl-views/code-generation/code-summary.md > Pendente item 1 (A3); aidlc/spaces/default/memory/team.md > Code Style "Tenant no código" | `BotDirectory.ownedByAnotherTenant` (query `bot_id = :bot and tenant_id <> :tenant`, em `JdbcBotRepository`) é uma consulta de dados de cliente fora do tenant. A regra do time diz que "toda consulta de dados de cliente filtra pelo tenant", e a regra do projeto manda validar o tenant em toda leitura. A exceção (F-2) está só no Javadoc e na ArchUnit; o texto da regra e o NFR3.1 não foram escritos (A3 pendente). Até lá o código contradiz a regra vigente. Decisão do fundador: ratificar a exceção. | O fundador decide se aceita a exceção F-2 (só booleano, só no caminho dos 404, thread do pedido, protegida pela ArchUnit). Se aceitar, registrar pelo ritual de aprendizado a exceção em `team.md` (Code Style) e no NFR3.1 de `security-requirements.md`, antes de fechar a Unit. Se recusar, a consulta sai e o 404 deixa de auditar a tentativa em outro tenant. | New |
| R-02 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u3-pnl-views/code-generation/code-summary.md > Resultados e Pendente itens 2, 3, 4 | A prova de que o NFR7.3 vale no `apps/api` (JaCoCo >= 80% e Spotless), a execução dos 10 casos U3 e dos smokes ajustados da U5, U7 e U9, e o `verify-checkpoint.sh` não existem. O traceability marca NFR7.3 como `N/A` com a nota "não medido". Nesta revisão rodei só o Jest de `features/pnl`, `features/dashboard` e `lib/api` (29 suítes, 308 testes verdes), os 104 testes `node:test` de `scripts/load` (verdes) e, no Maven, `PnlViewsArchitectureTest`, `NeverSumModesArchitectureTest` e `BotLinesCursorTest` (sem falha). Isso não cobre piso de cobertura nem e2e. | Não fechar o ponto de controle sem o `ci` verde do mesmo commit (jobs `backend`, `smoke` e `e2e`) e o `verify-checkpoint.sh`. Trocar o `N/A` do NFR7.3 pelo valor medido de JaCoCo quando existir. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/pnl/web/PnlController.java > `refuseNotFound` e apps/api/src/test/java/com/memetrade/architecture/PnlViewsArchitectureTest.java > `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS` | A regra ArchUnit proíbe chamar `BotDirectory#ownedByAnotherTenant` fora do `PnlController` (a classe inteira, não só `refuseNotFound`). Uma chamada pelo tipo concreto `BotDirectoryImpl` não bate com `callMethod(BotDirectory.class, ...)`. A segunda regra cobre só o repositório. | Fazer a regra cobrir também `BotDirectoryImpl` como alvo, ou restringir por método chamador (`refuseNotFound`). | New |
| R-04 | Minor | apps/web/src/lib/api/guest-api.ts > `comparePnl` e `getPnlByMode` | Só C5 e a tabela por token usam `retry: false`; C4 e C6 continuam repetindo um 503. Isso segue o desenho (`performance-design.md`: C4 continua repetindo), mas C6 faz duas passadas, uma delas REAL, e foi tratada como C4 sem justificativa escrita. | Registrar no desenho que C6 repete como C4 de propósito, ou passar `retry: false` em C6. | New |
| R-05 | Minor | apps/web/e2e/u7-ledger-pnl/smoke.spec.ts | O smoke da U7 deixou de conferir o aviso "MODO PAPER (simulado)" na seção PAPER e passou a conferir só o texto "Ver por token". A troca é justificada pelo BR2.9, mas a cobertura do aviso fixo no simulado depende dos outros casos. | Confirmar que um caso do spec `zz-paper-real-unico` cobre o aviso fixo no bloco Simulado e citá-lo no traceability. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u3-pnl-views/code-generation/code-summary.md > Desvios 9 | O `ModePnlLatencyIT` (U1) falhou uma vez sob carga. Teste instável é tratado como defeito pelo time e aqui ficou só como acompanhamento. | Rodar de novo na CI do commit; se repetir, tratar como defeito da U3 (a leitura de C4 ganhou `RANGE` e `market`). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability.json | 165 linhas (151 `OK`, 14 `N/A`), 52 `BR`, 0 alvo `OK` inexistente | Íntegro. Os 14 `N/A` são "entregue pela U1/U2" ou sem recurso novo; só o NFR7.3 pede ação (ver Major 2). |
| source-manifest.json | 136 arquivos escritos, todos existem | Íntegro. |
| `git status` em `.github/` e `scripts/verify.sh` | sem mudança | Cumprido: nada de workflow nem `verify.sh` foi editado. |
| jest (pnl, dashboard, lib/api) | 29 suítes, 308 testes, 0 falhas | Telas, reducer, cliente e painel verdes. |
| `node --test scripts/load/*.test.mjs` | 104 de 104 | Bate com o resumo. |
| Maven (ArchUnit de visões, nunca somar modos, cursor) | sem falhas | A regra da consulta fora do tenant e o contrato dos modos passam; a ressalva está no Minor 1. |
| Busca por `Number(`, `toFixed`, `parseFloat`, `bigint` nos arquivos novos | nada em `features/pnl`, `features/dashboard`, `lib/format`, `guest-api.ts`; `bigint` só em `features/real/money.ts` e no `features/bots/decimal.ts` já existente | Dinheiro tratado como texto; `isZeroText` é comparação de texto. `unknown` continua estado visível (`UNKNOWN` entra na decisão da linha "Sem bot"). |
| Busca por `PnlCard` e `DashboardRealPnl` | só comentários e o `DashboardRealPnl.test.tsx` reaproveitado; nenhum consumidor | Remoção limpa. |

### Summary

**Pontos verificados sem achado.** Os modos nunca são somados nem convertidos: cartões, comparação e filtro "Todos" usam só valores de C4, C6 e dos contadores da API, sem total geral. O 404 vem antes do 422 em C6, e a consulta de outro tenant roda na thread do pedido nos dois 404, devolvendo só booleano. Só um positivo vai para a fila de auditoria (1 thread, fila 16, prazo 200 ms), e o corpo do 404 é igual nos três casos de C5. O cursor de C5 é opaco (44 caracteres, resumo SHA-256), estável na inserção e recusa cursor mal formado ou de outro bot com 400. A lista fechada de C4 recusa `tenantId` com 400, e a mudança no teste da U1 (`ModePnlFailureIT`) é justificada e não enfraquece nada: o teste passou a conferir o 400 e manteve a leitura sem dados de outra conta. Os testes ajustados da U1/U2 e dos smokes U5, U7 e U9 não foram apagados.

**O que entregue ao responsável.** Os textos prontos do `load-test.yml` (9 leituras, 7 portões, grupos U2 e U3 com a regra R-04) e do passo do `ci.yml` (`node --test "scripts/load/*.test.mjs"` no job `frontend`) são coerentes entre si e com o código. O CSS não introduz cor nova, só tokens existentes, e o spec cobre os 360 px.

**Para decisão do fundador:** Major 1, a exceção F-2 à regra de tenant (ratificar e registrar pelo ritual de aprendizado). Os outros pontos (Major 2 e Minors) são ações de verificação e ajustes de texto ou teste, sem decisão de produto. Com 2 Major e nenhum Critical o veredito é READY, condicionado a CI verde antes do ponto de controle.
