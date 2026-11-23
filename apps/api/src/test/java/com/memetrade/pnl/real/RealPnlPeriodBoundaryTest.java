package com.memetrade.pnl.real;

import static com.memetrade.pnl.real.PnlScenario.XYZ;
import static com.memetrade.pnl.real.PnlScenario.line;
import static com.memetrade.pnl.real.PnlScenario.price;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.PnlWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR7.6, BR8.2, BR9.5, AC5.4.3, AC7.2.1): each event has
 * ONE instant, the {@code createdAt} of its main row, used by every part of it (received, cost removed, realized, fee
 * and RENT) and by both sides of the reconciliation; so a fee or a RENT recorded on the other side of a boundary never
 * gives a false MISMATCH. The unrealized is always the one of now.
 */
class RealPnlPeriodBoundaryTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Instant FROM = Instant.parse("2026-11-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-17T00:00:00Z");

    private static void assertMatch(RealPnlResult result) {
        assertThat(result.reconciliation().status())
                .as("%s", result.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
    }

    private static PnlScenario saleNear(Instant boundary) {
        PnlScenario ledger = new PnlScenario().bot(A);
        ledger.at("2026-11-01T00:00:00Z").buy(A, 100, 100_000L, 0);
        ledger.at(boundary.minusMillis(1).toString()).feeDelay(Duration.ofMillis(2));
        ledger.ownSaleWithRent(A, 100, 150_000L, 5_000L, 2_000L);
        return ledger;
    }

    /** BR9.5: the fill 1 ms before {@code to}, the fee and the RENT 1 ms after: all of it in [from, to). */
    @Test
    void anEventWhoseFillIsBeforeTheEndEntersWholeAndNothingOfItEntersTheNextPeriod() {
        PnlScenario ledger = saleNear(TO);

        RealPnlResult period = ledger.run(new PnlWindow.Range(FROM, TO), Map.of());
        RealPnlResult next = ledger.run(new PnlWindow.Range(TO, TO.plus(Duration.ofDays(1))), Map.of());

        assertThat(line(period, A).realizedLamports()).isEqualTo(50_000L);
        assertThat(line(period, A).feesLamports()).isEqualTo(7_000L);
        assertThat(period.pnl().total().feesLamports()).isEqualTo(7_000L);
        assertThat(line(next, A).realizedLamports()).isZero();
        assertThat(line(next, A).feesLamports()).isZero();
        assertThat(next.pnl().total().feesLamports()).isZero();
        assertMatch(period);
        assertMatch(next);
    }

    /** BR9.5: the fill 1 ms before {@code from}: nothing of it in [from, to), even the fee recorded after from. */
    @Test
    void anEventWhoseFillIsBeforeTheStartStaysWholeInThePreviousPeriod() {
        PnlScenario ledger = saleNear(FROM);

        RealPnlResult period = ledger.run(new PnlWindow.Range(FROM, TO), Map.of());
        RealPnlResult before = ledger.run(new PnlWindow.Range(FROM.minus(Duration.ofDays(1)), FROM), Map.of());

        assertThat(line(period, A).feesLamports()).isZero();
        assertThat(period.pnl().total().feesLamports()).isZero();
        assertThat(line(before, A).feesLamports()).isEqualTo(7_000L);
        assertThat(before.pnl().total().realizedLamports()).isEqualTo(50_000L);
        assertMatch(period);
        assertMatch(before);
    }

    /** BR9.5, BR6.3: the fee of the mode Assinada follows the instant of the fill too. */
    @Test
    void theFeeOfTheModeAssinadaFollowsTheInstantOfTheFill() {
        PnlScenario ledger = new PnlScenario().bot(A);
        ledger.at("2026-11-01T00:00:00Z").buy(A, 100, 100_000L, 0);
        ledger.at(TO.minusMillis(1).toString()).signedOwnSale(A, 100, 150_000L, 5_000L);

        RealPnlResult period = ledger.run(new PnlWindow.Range(FROM, TO), Map.of());
        RealPnlResult next = ledger.run(new PnlWindow.Range(TO, TO.plus(Duration.ofDays(1))), Map.of());

        assertThat(line(period, A).feesLamports()).isEqualTo(5_000L);
        assertThat(line(next, A).feesLamports()).isZero();
        assertMatch(period);
        assertMatch(next);
    }

    /** AC7.2.1, BR7.6: the unrealized of a period is the one of now, the same as since the beginning. */
    @Test
    void theUnrealizedOfAPeriodIsTheOneOfNow() {
        PnlScenario ledger = new PnlScenario().bot(A);
        ledger.at("2026-11-01T00:00:00Z").buy(A, 100, 80_000_000L, 0);

        RealPnlResult week = ledger.run(new PnlWindow.Range(FROM, TO), price(XYZ, 1_000_000L));
        RealPnlResult all = ledger.run(PnlWindow.all(), price(XYZ, 1_000_000L));

        assertThat(line(week, A).unrealized()).isEqualTo(line(all, A).unrealized());
        assertThat(week.pnl().total().unrealized()).isEqualTo(all.pnl().total().unrealized());
        assertThat(week.reconciliation().window()).isEqualTo(new PnlWindow.Range(FROM, TO));
        assertMatch(week);
    }
}
