## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-28T19:22:54Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/api/src/main/java/com/memetrade/identity/domain/session/SessionService.java > refresh and renew; apps/api/src/main/java/com/memetrade/identity/infra/JdbcSessionRepository.java > findByRefreshDigestForUpdate | Two refresh requests that arrive together with the same cookie (two tabs opening at once) serialize on the row lock. The second one re-reads the row, no longer finds the old digest, and falls into the used-digest path. That path revokes the whole session as reuse and writes SESSION_REFRESH_REUSED. The reason CONCURRENT_ROTATION can therefore hardly ever be returned, and a legitimate parallel renewal looks like theft and logs the person out. | Decide whether a short grace window for the digest just rotated is wanted. If not, record in the design that parallel renewal revokes the session on purpose, and remove or justify the CONCURRENT_ROTATION branch. Add a test for two parallel refreshes. | New |
| R-02 | Minor | apps/api/src/main/resources/application.yml > springdoc and management.endpoint.health.show-details; apps/api/src/main/java/com/memetrade/identity/web/AuthenticationFilter.java > shouldNotFilter | The default profile publishes /v3/api-docs without authentication, because the filter only protects /api/v1. Port 8081 shows health details (`always`) and is mapped to the host (127.0.0.1). This is harmless locally, but it becomes information disclosure in the first shared environment. | Turn off springdoc.api-docs outside the test and dev profiles (the OpenAPI file is already generated and versioned). Set show-details to `when-authorized` or keep port 8081 off the public path. Record this as a G1 prerequisite. | New |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/JdbcMarketListingQuery.java > observations | The observations query filters only by `mint in (...)`. It picks no source and has no order, and it matches the mint without the chain (the chain is only checked afterwards in memory). When U4 adds a second source, the last row read wins non-deterministically for the same token and field. code-summary.md already lists this as an open point. | Before U4, add the source-choice rule (or a primary key on chain, mint, field plus source) to the U4 backlog, and join on chain in the query. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/traceability.json > coverage targets (64 entries) | The target files exist and the 7 N/A entries have a stated reason. However, 64 target files never mention the ID they are meant to prove (for example BR3.4 points to ScannerServiceTest, but the exact-5-minute test lives in MarketCollectorTest and ScannerIT). A reader cannot confirm the claimed link from the test file alone. | Add the ID to the test name or to a comment in each target (a `@Tag` or `@DisplayName` with the ID). Correct the BR3.4 target. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/code-summary.md > Testes escritos e não executados; .github/workflows/security.yml > dependencies | The three workflows (CI, security, load test) and the load script were never run. security.yml passes `-DnvdApiKey` from a secret that is empty on pull requests from forks, and the dependency scan covers Maven only. CI pinning (SHA) and the `contents: read` permissions are correct. | Run the workflows on the first pull request. Confirm that an empty NVD key does not break the scan, and record the pnpm audit as the frontend dependency control. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` (re-run) | BUILD SUCCESS: 67 integration tests, 0 failures, 0 errors; JaCoCo "All coverage checks have been met"; spotless clean | Confirms code-summary.md. The 80% floor was not weakened. |
| `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage ...` (re-run) | Test Suites: 9 passed, 9 total; Tests: 80 passed, 80 total; Lines 98.32% (412/419) | Confirms the claim. |
| `bash scripts/verify.sh` (earlier run today, quoted) | exit 0: 3 services healthy, Playwright 3/3 passed (smoke with nonce replay refused, keyboard, screen-time), no full wallet address in the logs, environment removed | The end-to-end slice works. |
| Traceability check (earlier run, quoted) | 81 entries, 0 upstream IDs without an entry, 7 N/A with a reason, every other target exists | Targets resolve; the ID inside the target is not always visible (Minor finding above). |

### Summary

I found no Critical or Major defects. The nonce is consumed by one conditional UPDATE and a concurrency test covers it. A refusal commits its state and a single audit event in the same transaction, and the audit trail is append-only through triggers. The tenant comes from the session and the database, not from the request. Refresh rotation locks the row and revokes on reuse. `unknown` is a distinct type, stale is strictly above 5 minutes, and the keyset order follows BR3.6. There is no key generation in production code. The test profile and the load-test profile are isolated in the Compose overlay, and the CI actions are pinned by SHA. The remaining items are minor, and the first two should be settled before any shared environment exists.
