## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-28T19:30:19Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/api/src/main/java/com/memetrade/identity/domain/session/SessionService.java > refresh and renew; apps/api/src/main/java/com/memetrade/identity/infra/JdbcSessionRepository.java > findByRefreshDigestForUpdate | Two refresh requests that arrive together with the same cookie (two tabs opening at once) serialize on the row lock. The second one re-reads the row, no longer finds the old digest, and falls into the used-digest path. That path revokes the whole session as reuse and writes SESSION_REFRESH_REUSED. The reason CONCURRENT_ROTATION can therefore hardly ever be returned, and a legitimate parallel renewal looks like theft and logs the person out. | Decide whether a short grace window for the digest just rotated is wanted. If not, record in the design that parallel renewal revokes the session on purpose, and remove or justify the CONCURRENT_ROTATION branch. Add a test for two parallel refreshes. | Unresolved |
| R-02 | Minor | apps/api/src/main/resources/application.yml > springdoc and management.endpoint.health.show-details; apps/api/src/main/java/com/memetrade/identity/web/AuthenticationFilter.java > shouldNotFilter | The default profile publishes /v3/api-docs without authentication, because the filter only protects /api/v1. Port 8081 shows health details (`always`) and is mapped to the host (127.0.0.1). This is harmless locally, but it becomes information disclosure in the first shared environment. | Turn off springdoc.api-docs outside the test and dev profiles (the OpenAPI file is already generated and versioned). Set show-details to `when-authorized` or keep port 8081 off the public path. Record this as a G1 prerequisite. | Unresolved |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/JdbcMarketListingQuery.java > observations | The observations query filters only by `mint in (...)`. It picks no source and has no order, and it matches the mint without the chain (the chain is only checked afterwards in memory). When U4 adds a second source, the last row read wins non-deterministically for the same token and field. code-summary.md already lists this as an open point. | Before U4, add the source-choice rule (or a primary key on chain, mint, field plus source) to the U4 backlog, and join on chain in the query. | Unresolved |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/traceability.json > coverage targets (64 entries) | The target files exist and the 7 N/A entries have a stated reason. However, 64 target files never mention the ID they are meant to prove (for example BR3.4 points to ScannerServiceTest, but the exact-5-minute test lives in MarketCollectorTest and ScannerIT). A reader cannot confirm the claimed link from the test file alone. | Add the ID to the test name or to a comment in each target (a `@Tag` or `@DisplayName` with the ID). Correct the BR3.4 target. | Unresolved |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/code-summary.md > Testes escritos e não executados; .github/workflows/security.yml > dependencies | The three workflows (CI, security, load test) and the load script were never run. security.yml passes `-DnvdApiKey` from a secret that is empty on pull requests from forks, and the dependency scan covers Maven only. CI pinning (SHA) and the `contents: read` permissions are correct. | Run the workflows on the first pull request. Confirm that an empty NVD key does not break the scan, and record the pnpm audit as the frontend dependency control. | Unresolved |
| R-06 | Minor | scripts/verify.sh > linha 52 (export de MSYS2_ENV_CONV_EXCL dentro do bloco `if [ ! -f .env ]`) | A exclusão só vale quando não existe .env. Com .env presente (o caminho usual no desenvolvimento local no Windows), o bloco `else` carrega ACCESS_TOKEN_SIGNING_KEY do arquivo e não define a exclusão. Uma chave base64 que comece com "/" sofre a mesma reescrita para "C:/Program Files/Git/..." ao ser passada ao docker.exe. O caminho do .env fica sem a correção. | Mover o export de MSYS2_ENV_CONV_EXCL para fora do if/else, depois da definição da chave, para cobrir os dois ramos. | New |

> R-01 Reviewer note: A única mudança desde a revisão anterior foi em scripts/verify.sh; SessionService e JdbcSessionRepository não foram alterados, então a renovação paralela continua tratada como reuso.

> R-02 Reviewer note: Nenhum arquivo de configuração foi alterado; /v3/api-docs e show-details continuam como descrito.

> R-03 Reviewer note: JdbcMarketListingQuery não mudou; a escolha de fonte segue em aberto para a U4.

> R-04 Reviewer note: traceability.json não mudou; os alvos continuam sem citar o ID.

> R-05 Reviewer note: Os workflows não foram executados; a mudança desta rodada não os toca.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Reprodução Git Bash com `MSYS2_ENV_CONV_EXCL=ACCESS_TOKEN_SIGNING_KEY` e valor `/AbC+de` via cmd.exe | Saída `/AbC+de` (inalterada) | A exclusão funciona. |
| Mesma reprodução sem a exclusão | Saída `C:/Program Files/Git/AbC+de` | Confirma a causa raiz da falha anterior. |
| backend verify e jest (execuções anteriores citadas na instrução) | Verdes | Não reexecutados; a mudança não toca código da aplicação. |

### Summary

A correção é válida: a variável é excluída da conversão de caminho, nenhum segredo é impresso, o `${MSYS2_ENV_CONV_EXCL:+;...}` preserva valor preexistente e a variável é ignorada no Linux/CI. `random_secret` já remove `/`, `+` e `=`, então as senhas aleatórias não podem sofrer a reescrita. Fica a lacuna menor do ramo com .env. Restam apenas achados Minor, sem bloqueio.
