package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.pnl.real.ReconciliationResult.Check;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2 (NFR3.15; decision of the founder of 2026-11-23 on review R-05): the fingerprint of a
 * mismatch is the SHA-256 of the person and the kinds of the failed checks, and NOTHING ELSE: no difference in lamports
 * and no window. A divergence whose value changes at every read is the same fingerprint, so the trail keeps at most one
 * event per hour for it. The details of the event still carry the window, the checks and the differences.
 */
class ReconciliationFingerprintTest {

    private static final UUID PERSON = UUID.fromString("0f000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0f000000-0000-4000-8000-000000000002");

    private static ReconciliationResult result(PnlWindow window, List<Check> checks, Map<String, Long> diffs) {
        return new ReconciliationResult(
                window, ReconciliationResult.Status.MISMATCH, 0, 0, Optional.empty(), checks, diffs);
    }

    private static PnlWindow week() {
        return new PnlWindow.Range(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"));
    }

    @Test
    void anotherValueOrAnotherWindowIsTheSameFingerprint() {
        String base = ReconciliationAuditGate.fingerprint(
                PERSON, result(PnlWindow.all(), List.of(Check.REALIZED), Map.of("REALIZED", -1L)));

        assertThat(base).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(ReconciliationAuditGate.fingerprint(
                        PERSON, result(PnlWindow.all(), List.of(Check.REALIZED), Map.of("REALIZED", -2L))))
                .isEqualTo(base);
        assertThat(ReconciliationAuditGate.fingerprint(
                        PERSON, result(week(), List.of(Check.REALIZED), Map.of("REALIZED", 987_654_321L))))
                .isEqualTo(base);
    }

    @Test
    void theOrderOfTheChecksDoesNotMatter() {
        assertThat(ReconciliationAuditGate.fingerprint(
                        PERSON, result(PnlWindow.all(), List.of(Check.REALIZED, Check.FEES), Map.of())))
                .isEqualTo(ReconciliationAuditGate.fingerprint(
                        PERSON, result(PnlWindow.all(), List.of(Check.FEES, Check.REALIZED), Map.of())));
    }

    @Test
    void anotherPersonOrAnotherKindOfCheckIsAnotherFingerprint() {
        String base = ReconciliationAuditGate.fingerprint(
                PERSON, result(PnlWindow.all(), List.of(Check.REALIZED), Map.of("REALIZED", -1L)));

        assertThat(ReconciliationAuditGate.fingerprint(
                        OTHER, result(PnlWindow.all(), List.of(Check.REALIZED), Map.of("REALIZED", -1L))))
                .isNotEqualTo(base);
        assertThat(ReconciliationAuditGate.fingerprint(
                        PERSON, result(PnlWindow.all(), List.of(Check.FEES), Map.of("FEES", -1L))))
                .isNotEqualTo(base);
        assertThat(ReconciliationAuditGate.fingerprint(
                        PERSON, result(PnlWindow.all(), List.of(Check.REALIZED, Check.FEES), Map.of())))
                .isNotEqualTo(base);
    }

    @Test
    void theCanonicalTextHasThePersonAndTheChecksOnly() {
        assertThat(ReconciliationAuditGate.canonical(
                        PERSON,
                        result(week(), List.of(Check.REALIZED, Check.FEES), Map.of("REALIZED", -1L, "FEES", 3L))))
                .isEqualTo(PERSON + "|FEES,REALIZED");
    }

    @Test
    void theDetailsCarryOnlyTheWindowTheChecksTheDifferencesAndTheFingerprint() {
        ReconciliationResult mismatch =
                result(PnlWindow.all(), List.of(Check.REALIZED, Check.FEES), Map.of("REALIZED", -1L, "FEES", 3L));
        String fingerprint = ReconciliationAuditGate.fingerprint(PERSON, mismatch);

        Map<String, String> details = ReconciliationAuditGate.details(mismatch, fingerprint);

        assertThat(details)
                .containsOnly(
                        Map.entry("window", "ALL"),
                        Map.entry("checks", "FEES,REALIZED"),
                        Map.entry("diff.FEES", "3"),
                        Map.entry("diff.REALIZED", "-1"),
                        Map.entry("fingerprint", fingerprint));
        assertThat(ReconciliationAuditGate.window(week())).isEqualTo("RANGE:2026-11-10T00:00:00Z:2026-11-17T00:00:00Z");
    }
}
