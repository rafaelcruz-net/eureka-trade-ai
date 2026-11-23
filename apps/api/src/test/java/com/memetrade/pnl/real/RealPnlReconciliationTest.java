package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.real.RealPnlReconciliation.Inputs;
import com.memetrade.pnl.real.RealPnlReconciliation.Side;
import com.memetrade.pnl.real.ReconciliationResult.Check;
import com.memetrade.pnl.real.ReconciliationResult.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR7.5, BR8.1 to BR8.5, BR3.4, AC5.3.1, AC5.3.3,
 * AC5.3.4, AC5.4.3): the reconciliation of the lines (bots plus "Sem bot") with the mode computed from the ledger is an
 * exact equality in lamports, with no tolerance. Each check fails on its own with its difference; the unrealized is only
 * checked since the beginning.
 */
class RealPnlReconciliationTest {

    private static final PnlWindow WEEK =
            new PnlWindow.Range(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"));

    private static Side side(long realized, long fees, long unrealized) {
        return new Side(realized, fees, new LamportsValue.Known(unrealized));
    }

    private static Side unknown(long realized, long fees) {
        return new Side(realized, fees, new LamportsValue.Unknown(LamportsValue.UnknownReason.PRICE_UNKNOWN));
    }

    private static Inputs equal(PnlWindow window) {
        Side all = side(95_000_000L, 8_000L, 16_000_000L);
        return new Inputs(window, all, all, all, all, 0, 0, 0, false);
    }

    /** AC5.3.1: equal sides since the beginning: MATCH, every difference 0. */
    @Test
    void equalSidesSinceTheBeginningMatch() {
        ReconciliationResult result = RealPnlReconciliation.reconcile(equal(PnlWindow.all()));

        assertThat(result.status()).isEqualTo(Status.MATCH);
        assertThat(result.failedChecks()).isEmpty();
        assertThat(result.realizedDiffLamports()).isZero();
        assertThat(result.feesDiffLamports()).isZero();
        assertThat(result.unrealizedDiffLamports()).contains(0L);
    }

    /** AC5.3.3: one lamport of difference in the realized fails REALIZED alone, with the difference. */
    @Test
    void oneLamportOfRealizedFailsRealizedAlone() {
        Inputs in = equal(PnlWindow.all());
        Side lines = side(95_000_001L, 8_000L, 16_000_000L);
        ReconciliationResult result = RealPnlReconciliation.reconcile(
                new Inputs(in.window(), lines, in.ledgerInWindow(), lines, in.ledgerAll(), 0, 0, 0, false));

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.failedChecks()).containsExactly(Check.REALIZED);
        assertThat(result.realizedDiffLamports()).isEqualTo(1L);
        assertThat(result.diffs()).containsEntry("REALIZED", 1L);
    }

    @Test
    void aDifferenceOfFeesFailsFeesAlone() {
        Inputs in = equal(PnlWindow.all());
        Side lines = side(95_000_000L, 7_999L, 16_000_000L);
        ReconciliationResult result = RealPnlReconciliation.reconcile(
                new Inputs(in.window(), lines, in.ledgerInWindow(), lines, in.ledgerAll(), 0, 0, 0, false));

        assertThat(result.failedChecks()).containsExactly(Check.FEES);
        assertThat(result.feesDiffLamports()).isEqualTo(-1L);
    }

    @Test
    void aDifferenceOfUnrealizedFailsUnrealizedAloneAndAKnownAgainstAnUnknownToo() {
        Inputs in = equal(PnlWindow.all());
        Side lines = side(95_000_000L, 8_000L, 16_000_002L);
        ReconciliationResult result = RealPnlReconciliation.reconcile(
                new Inputs(in.window(), lines, in.ledgerInWindow(), lines, in.ledgerAll(), 0, 0, 0, false));
        Side open = unknown(95_000_000L, 8_000L);
        ReconciliationResult halfKnown = RealPnlReconciliation.reconcile(
                new Inputs(in.window(), open, in.ledgerInWindow(), open, in.ledgerAll(), 0, 0, 0, false));
        ReconciliationResult bothUnknown =
                RealPnlReconciliation.reconcile(new Inputs(in.window(), open, open, open, open, 0, 0, 0, false));

        assertThat(result.failedChecks()).containsExactly(Check.UNREALIZED);
        assertThat(result.unrealizedDiffLamports()).contains(2L);
        assertThat(halfKnown.failedChecks()).containsExactly(Check.UNREALIZED);
        assertThat(halfKnown.unrealizedDiffLamports()).isEmpty();
        assertThat(bothUnknown.status()).isEqualTo(Status.MATCH);
    }

    @Test
    void eachConservationAndTheEventSumsFailAloneWithTheirDifference() {
        Inputs in = equal(PnlWindow.all());

        ReconciliationResult cost = RealPnlReconciliation.reconcile(new Inputs(
                in.window(), in.linesInWindow(), in.ledgerInWindow(), in.linesAll(), in.ledgerAll(), 3, 0, 0, false));
        ReconciliationResult quantity = RealPnlReconciliation.reconcile(new Inputs(
                in.window(), in.linesInWindow(), in.ledgerInWindow(), in.linesAll(), in.ledgerAll(), 0, 4, 0, false));
        ReconciliationResult sums = RealPnlReconciliation.reconcile(new Inputs(
                in.window(), in.linesInWindow(), in.ledgerInWindow(), in.linesAll(), in.ledgerAll(), 0, 0, 5, false));

        assertThat(cost.failedChecks()).containsExactly(Check.COST_CONSERVATION);
        assertThat(cost.diffs()).containsEntry("COST_CONSERVATION", 3L);
        assertThat(quantity.failedChecks()).containsExactly(Check.QUANTITY_CONSERVATION);
        assertThat(quantity.diffs()).containsEntry("QUANTITY_CONSERVATION", 4L);
        assertThat(sums.failedChecks()).containsExactly(Check.EVENT_SUMS);
        assertThat(sums.diffs()).containsEntry("EVENT_SUMS", 5L);
    }

    /** AC5.3.4: a line "Sem bot" different from zero without a reason fails the reconciliation. */
    @Test
    void aLineWithoutBotWithoutReasonFailsUnownedWithoutReasonAlone() {
        Inputs in = equal(PnlWindow.all());
        ReconciliationResult result = RealPnlReconciliation.reconcile(new Inputs(
                in.window(), in.linesInWindow(), in.ledgerInWindow(), in.linesAll(), in.ledgerAll(), 0, 0, 0, true));

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.failedChecks()).containsExactly(Check.UNOWNED_WITHOUT_REASON);
    }

    /** BR3.4: an order of a bot outside the directory is UNKNOWN_BOT alone, never a number. */
    @Test
    void anUnknownBotIsAMismatchOfItsOwn() {
        ReconciliationResult result = RealPnlReconciliation.unknownBot(PnlWindow.all());

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.failedChecks()).containsExactly(Check.UNKNOWN_BOT);
        assertThat(result.diffs()).isEmpty();
    }

    /** AC5.4.3: in a period the unrealized is not checked; the realized and the fees are, in the period and ALL. */
    @Test
    void inAPeriodTheUnrealizedIsCheckedOnlySinceTheBeginning() {
        Side all = side(165_000_000L, 0, 1L);
        ReconciliationResult period = RealPnlReconciliation.reconcile(
                new Inputs(WEEK, side(125_000_000L, 0, 7L), side(125_000_000L, 0, 9L), all, all, 0, 0, 0, false));
        ReconciliationResult allBroken = RealPnlReconciliation.reconcile(new Inputs(
                WEEK,
                side(125_000_000L, 0, 7L),
                side(125_000_000L, 0, 7L),
                side(165_000_001L, 0, 1L),
                all,
                0,
                0,
                0,
                false));

        assertThat(period.status()).isEqualTo(Status.MATCH);
        assertThat(period.window()).isEqualTo(WEEK);
        assertThat(allBroken.failedChecks()).containsExactly(Check.REALIZED);
        assertThat(allBroken.realizedDiffLamports()).isEqualTo(1L);
    }

    /** The checks and the differences are in a stable order: the fingerprint of the audit depends on it. */
    @Test
    void theFailedChecksAreInAlphabeticalOrderAndTheDifferencesByKey() {
        Inputs in = equal(PnlWindow.all());
        Side lines = side(95_000_001L, 8_001L, 16_000_000L);
        ReconciliationResult result = RealPnlReconciliation.reconcile(
                new Inputs(in.window(), lines, in.ledgerInWindow(), lines, in.ledgerAll(), 2, 0, 0, true));

        assertThat(result.failedChecks())
                .containsExactly(Check.COST_CONSERVATION, Check.FEES, Check.REALIZED, Check.UNOWNED_WITHOUT_REASON);
        assertThat(List.copyOf(result.diffs().keySet())).isSorted();
        assertThat(result.diffs()).isEqualTo(Map.of("COST_CONSERVATION", 2L, "FEES", 1L, "REALIZED", 1L));
        assertThat(result.unrealizedDiffLamports()).isEqualTo(Optional.of(0L));
    }

    /** BR7.5, BR8.1: the engine reconciles its own lines with the mode computed from the ledger rows, in ALL and RANGE. */
    @Test
    void theEngineMatchesSinceTheBeginningAndInAPeriod() {
        PnlScenario ledger = new PnlScenario().bot(RealCostBasisTest.A).bot(RealCostBasisTest.B);
        ledger.at("2026-11-09T10:00:00Z").buy(RealCostBasisTest.A, 100, 100L, 3L);
        ledger.at("2026-11-11T10:00:00Z").ownSale(RealCostBasisTest.A, 150, 400L, 5L);
        ledger.at("2026-11-12T10:00:00Z").buy(RealCostBasisTest.B, 100, 300L, 3L);
        ledger.at("2026-11-18T10:00:00Z").liquidation(60, 600L, 7L);

        assertThat(ledger.run(PnlWindow.all(), Map.of()).reconciliation().status())
                .isEqualTo(Status.MATCH);
        RealPnlResult week = ledger.run(WEEK, Map.of());
        assertThat(week.reconciliation().status()).isEqualTo(Status.MATCH);
        assertThat(week.pnl().total().realizedLamports()).isEqualTo(400L - 100L);
        assertThat(week.pnl().total().feesLamports()).isEqualTo(5L + 3L);
    }
}
