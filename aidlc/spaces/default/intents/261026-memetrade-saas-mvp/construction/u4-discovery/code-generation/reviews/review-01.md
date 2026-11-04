## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T01:16:18Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/web/src/content/prohibited-terms.json > reviewedBy | The catalog of prohibited terms is shipped with `reviewedBy` set to "pendente: responsável do projeto", and the catalog test (`prohibited-terms.test.ts:14`) only checks that the field is truthy, so a pending value passes. The proposed terms beyond the four in BR5.1 are unreviewed. | Have the project owner review the proposed terms before the beta, then record the reviewer. Make the test fail while the value starts with "pendente", or track the review explicitly. | New |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/identity/web/AdminRouteFilter.java > shouldNotFilter and doFilterInternal | The filter now runs on every `/api/v1/` request and returns 403 FORBIDDEN_ROLE for an administrator token on any non-administrative route. This applies to the public and U1 routes as well as the U4 guest routes. The change is correct for the common contract rule, but it widens U1 behavior beyond U4. Only the guest scanner and assessment routes are named as covered. | Confirm that tests cover an administrator token on a public route, such as login and refresh. Record the widened scope in the U1 notes. | New |
| R-03 | Minor | apps/api/src/main/resources/db/changelog/changes/0006-discovery.sql > lines 10-23 | The migration drops and re-adds the `data_source_status_ck` constraint to allow `PENDING`, and runs an UPDATE on existing rows. After that, an image from before U4 cannot read a `PENDING` source. This is documented, and a rollback block exists. It is still not strictly expand-only. | Keep the documented rollback note. Add a rollback rehearsal to the checkpoint evidence, since it is not run yet. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/code-summary.md > Testes escritos e não executados | The load test, the manual rollback and the p95 target for the list at more than 2,000 tokens (NFR3.1) have not been run. That claim cannot be verified by reading. | Run the load scenarios and the rollback at the U4 checkpoint and attach the results before relying on NFR3.1. | New |
| R-05 | Minor | apps/web/e2e/u4-discovery/sign-in.ts > connectAndReachScanner | The single retry after the real login rate limit is the person's decision. Only the first rate-limit rejection is detected, and nothing records that the retry happened. A second rate-limit rejection appears only as a generic timeout. | Add a log line or an annotation when the retry fires, so the failure is diagnosable. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/code-generation/code-summary.md > Pontos em aberto | Two tests put all sources into `FAILED` and restore them in `finally`, which relies on sequential execution. The C2 additions are also not yet copied into `contract-summary.md`. | Isolate those two tests, or mark them as non-parallel. Copy the C2 additions into the contract summary. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Static reading of main sources (grep) | No `double`, `float` or `now()` calls in marketdata, riskassessment or scanner; BigDecimal in `RiskCalculator` and `Curve`; MarketData imports nothing from riskassessment or scanner | Matches the exact-decimal, injectable-clock and module-boundary rules |
| Recorded test and build results | Not re-run by this reviewer (read-only); reported green | Taken as reported, not verified |

### Summary

I found no Critical or Major issue. `RiskCalculator` counts an unknown factor as risk 100 and never as LOW. It raises a LOW band to MEDIUM when any factor is unknown. `SourceCaller` retries only the source reads. `ScannerRateLimitFilter` limits each person per route and answers 429 RATE_LIMITED. The weights activation is audited, and recalculation runs under a ShedLock lock with a version guard. The remaining items are minor and are mostly checkpoint evidence that has not yet been run.
