## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-06
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/web/src/content/prohibited-terms.json > reviewedBy | The catalog of prohibited terms is shipped with `reviewedBy` set to "pendente: responsável do projeto", and the catalog test (`prohibited-terms.test.ts:14`) only checks that the field is truthy, so a pending value passes. The proposed terms beyond the four in BR5.1 are unreviewed. | Have the project owner review the proposed terms before the beta, then record the reviewer. Make the test fail while the value starts with "pendente", or track the review explicitly. | Accepted risk |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/identity/web/AdminRouteFilter.java > shouldNotFilter and doFilterInternal | The filter now runs on every `/api/v1/` request and returns 403 FORBIDDEN_ROLE for an administrator token on any non-administrative route. This applies to the public and U1 routes as well as the U4 guest routes. The change is correct for the common contract rule, but it widens U1 behavior beyond U4. Only the guest scanner and assessment routes are named as covered. | Confirm that tests cover an administrator token on a public route, such as login and refresh. Record the widened scope in the U1 notes. | Accepted risk |
| R-03 | Minor | apps/api/src/main/resources/db/changelog/changes/0006-discovery.sql > lines 10-23 | The migration drops and re-adds the `data_source_status_ck` constraint to allow `PENDING`, and runs an UPDATE on existing rows. After that, an image from before U4 cannot read a `PENDING` source. This is documented, and a rollback block exists. It is still not strictly expand-only. | Keep the documented rollback note. Add a rollback rehearsal to the checkpoint evidence, since it is not run yet. | Accepted risk |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/code-summary.md > Testes escritos e não executados | The load test, the manual rollback and the p95 target for the list at more than 2,000 tokens (NFR3.1) have not been run. That claim cannot be verified by reading. | Run the load scenarios and the rollback at the U4 checkpoint and attach the results before relying on NFR3.1. | Accepted risk |
| R-05 | Minor | apps/web/e2e/u4-discovery/sign-in.ts > connectAndReachScanner | The single retry after the real login rate limit is the person's decision. Only the first rate-limit rejection is detected, and nothing records that the retry happened. A second rate-limit rejection appears only as a generic timeout. | Add a log line or an annotation when the retry fires, so the failure is diagnosable. | Accepted risk |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/code-summary.md > Pontos em aberto | Two tests put all sources into `FAILED` and restore them in `finally`, which relies on sequential execution. The C2 additions are also not yet copied into `contract-summary.md`. | Isolate those two tests, or mark them as non-parallel. Copy the C2 additions into the contract summary. | Accepted risk |
| R-07 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/traceability.json > coverage NFR1.2 (e NFR5.1) | NFR1.2 está `OK`, mas o requisito pede um teste de integração com relógio injetável que confira, após duas coletas seguidas, idade de até 60 s, e a própria `note` admite que esse teste não existe. NFR5.1 também está `OK`, e a `note` admite que o teste de registro de rotas globais exigido não existe, ficando só a migração como alvo. Marcar `OK` aqui é inconsistente com NFR2.1, NFR2.3 e NFR3.3, que ficaram `Deferred` por lacuna equivalente. | Mudar NFR1.2 e NFR5.1 para `Deferred` (com a lacuna na `note`), ou acrescentar o teste que falta. Se ficarem `OK`, listá-los também na tabela de `Deferred` do code-summary.md. | Resolved (reviewer) |
| R-08 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/traceability.json > coverage NFR9.2, NFR9.3, NFR9.4 | As três linhas estão `OK`, mas as `note` dizem que parte das métricas exigidas (`market_collection_retries_total`, descartes, último sucesso, durações do recálculo e da limpeza, `risk_assessments`, `market_history_samples`, `scanner_rate_limited_total`) não é conferida por nenhum teste. O `target` de NFR9.2 é código de produção, não prova. Isso é `OK` sem evidência para a maior parte das métricas, e a ressalva está só em prosa. | Rebaixar para `Deferred` (lacuna parcial) ou ampliar `MetricsLabelsIT` para registrar essas métricas. O mesmo vale para NFR10.5, que admite a volta de `FAILED` e de `AVAILABLE` sem teste. | Resolved (reviewer) |
| R-09 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/traceability.json > coverage NFR3.5 | O cenário de crescimento foi executado e reprovou (p95 da lista em 10,02 s, postgres com 990 MB contra limite de 900 MB), mas o status é `Deferred`, que lê como "não medido". Um resultado reprovado não é adiamento. | Usar a `note` em destaque (ou um status de falha, se o leitor aceitar) e registrar a decisão de escala antes do G1 com responsável. | Resolved (reviewer) |
| R-10 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/code-summary.md > Volta 1, Conferência | Alvos fora do marcador `u4` (`ScannerIT`, `CorrelationIdFilterTest`, `U4ArchitectureTest`, `shell.test.tsx`) sustentam linhas `OK` (NFR5.1, NFR6.9, NFR9.7, NFR11.2, NFR4.1) e não foram reexecutados nesta volta. O texto diz que a suíte inteira estava verde na geração. | Rodar `verify` completo e o Jest com cobertura antes do gate, ou registrar que essas linhas dependem da execução anterior. | Resolved (reviewer) |
| R-11 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/traceability.json > coverage NFR2.8, NFR13.1 | Os `target` são o Javadoc/código do limitador e o `.gitattributes`, que implementam a regra mas não a verificam. Aceitável como evidência de implementação, mas fraco. | Opcional: apontar para `PersonRouteRateLimiterTest` e deixar o limitador como alvo secundário. | Unresolved |
| R-12 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/traceability.json > coverage NFR2.6 e NFR12.1 | NFR2.6 declara o plano sem Seq Scan como conferido pelo script de carga (`scripts/load/load-test.mjs`), que não é teste automatizado, e NFR12.1 aponta para `pom.xml` com percentuais de cobertura (95,72% e 94,36%) medidos na geração e não reexecutados nesta volta. Ambos seguem `OK`. | Citar na seção "Ressalvas" que essas duas linhas se apoiam em script de carga e em medição anterior, ou reexecutar `verify` com cobertura antes do gate. | New |

> R-07 Reviewer note: NFR1.2 e NFR5.1 agora são `Deferred` em `traceability.json`, com a lacuna na `note`, e constam na tabela de `Deferred` do `code-summary.md`.

> R-08 Reviewer note: NFR9.2, NFR9.3, NFR9.4 e NFR10.5 agora são `Deferred` com lacuna parcial descrita; o alvo de NFR9.2 passou a ser `MarketCollectorJobTest.java`, que existe.

> R-09 Reviewer note: A `note` de NFR3.5 começa com "REPROVADO, não apenas adiado." e o resumo marca como reprovado; `docs/perf/u4-discovery-2026-11-06-growth.md` existe. A decisão de escala antes do G1 segue com o responsável do projeto.

> R-10 Reviewer note: A seção "Ressalvas" registra que NFR4.1, NFR5.1, NFR6.9, NFR9.7 e NFR11.2 dependem de testes fora do marcador `u4`, não reexecutados nesta volta.

> R-11 Reviewer note: NFR2.8 (alvo `PersonRouteRateLimiter.java`) e NFR13.1 (alvo `.gitattributes`) seguem `OK` com alvo que implementa a regra sem verificá-la. A ressalva agora está declarada no resumo, o que a torna transparente, mas o status continua mais forte que a evidência.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Validação do `traceability.json` (script Python) | JSON válido; 74 IDs em `upstream_ids` e 74 linhas em `coverage`, conjuntos idênticos; nenhum `target` ausente do repositório | A estrutura está íntegra. |
| Contagem por status | NFR: 41 OK, 15 Deferred, 1 N/A; mais 17 critérios de aceite OK | Bate com o `code-summary.md` (41/15/1 e 74 linhas). |
| Varredura das notas de linhas `OK` por admissão de lacuna | Nenhuma nota restante admite ausência do teste exigido, exceto NFR2.8/NFR13.1 (já ressalvadas) | Os `OK` restantes têm evidência plausível por leitura. |
| Testes (`mvnw ... -Dgroups=u4`, Jest) | Não reexecutados por mim; o resumo cita BUILD SUCCESS (139 unitários, 84 de integração) e 45 testes Jest | Resultado citado do resumo, sem rodada própria. Nenhum código nem teste mudou desde a iteração 1, então não bloqueia. |

### Summary

As duas lacunas Major da iteração 1 foram corrigidas: seis linhas passaram de `OK` para `Deferred`, as contagens de JSON e resumo coincidem e o JSON é válido, sem alvos ausentes. Restam só ressalvas Minor sobre linhas `OK` que apoiam a meta em implementação, script de carga ou medição anterior.
