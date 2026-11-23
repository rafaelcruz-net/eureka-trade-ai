package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.UnownedPnlLine;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.real.Holder;
import com.memetrade.pnl.real.PnlScenario;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U1, Step 11, CHANGED BY THE U2 (Etapa 18, written before the code; C10, BR10.3, BR7.1, BR7.4,
 * BR5.5): the scenario of the U1 read from the ledger by the new implementation. What still holds: each bot has only its
 * own result, the total of the mode covers every order of the person, an unknown price is never zero. What changes: the
 * realized is GROSS of fees ({@code total = realized + unrealized - fees}), the line "Sem bot" is present when a sale
 * passes the bots, and the shares are filled.
 *
 * <p>The U1 numbers: A buys 10.000.000 units for 1.000.000 lamports (fee 5.000) and sells 5.000.000 for 600.000 (fee
 * 5.000); the U1 gave A a NET realized of 92.500. From the U2: {@code 600.000 - 500.000 = +100.000}, fees 10.000.
 */
class RealBotPnlQueryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final Holder A =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000a"), Instant.parse("2026-11-01T10:00:00Z"));
    private static final Holder B =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000b"), Instant.parse("2026-11-02T10:00:00Z"));

    private final RealBotPnlTest fixture = new RealBotPnlTest();

    private static PnlScenario u1() {
        PnlScenario scenario = new PnlScenario().bot(A).bot(B);
        scenario.buy(A, 10_000_000L, 1_000_000L, 5_000L);
        scenario.buy(B, 20_000_000L, 2_000_000L, 5_000L);
        scenario.ownSale(A, 5_000_000L, 600_000L, 5_000L);
        scenario.failed(B, 5_000L);
        return scenario;
    }

    @Test
    void eachBotHasOnlyItsOwnResultAndTheRealizedIsNowGross() {
        RealBotPnl query = fixture.query(u1(), PnlByModeProperties.defaults());
        fixture.prices(java.util.Map.of());

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        RealBotPnlLine a = pnl.lines().get(0);
        assertThat(a.realizedLamports()).isEqualTo(100_000L);
        assertThat(a.feesLamports()).isEqualTo(10_000L);
        RealBotPnlLine b = pnl.lines().get(1);
        assertThat(b.realizedLamports()).isZero();
        assertThat(b.feesLamports()).isEqualTo(10_000L);
        assertThat(pnl.total().feesLamports()).isEqualTo(20_000L);
        assertThat(pnl.total().realizedLamports()).isEqualTo(100_000L);
    }

    @Test
    void theTotalIsRealizedPlusUnrealizedMinusFees() {
        RealBotPnl query = fixture.query(u1(), PnlByModeProperties.defaults());
        fixture.prices(PnlScenario.price(PnlScenario.XYZ, 1L));

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        // 25.000.000 units open at 1 lamport: A 5.000.000 (cost 500.000), B 20.000.000 (cost 2.000.000).
        RealBotPnlLine a = pnl.lines().get(0);
        assertThat(a.unrealized()).isEqualTo(new LamportsValue.Known(4_500_000L));
        assertThat(a.total()).isEqualTo(new LamportsValue.Known(100_000L + 4_500_000L - 10_000L));
        assertThat(pnl.total().total())
                .isEqualTo(new LamportsValue.Known(100_000L + 4_500_000L + 18_000_000L - 20_000L));
    }

    @Test
    void anUnknownPriceMakesTheUnrealizedAndTheTotalUnknownNeverZeroAndTheRealizedStaysExact() {
        RealBotPnl query = fixture.query(u1(), PnlByModeProperties.defaults());
        fixture.prices(java.util.Map.of());

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.lines().get(0).unrealized()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(pnl.lines().get(0).total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(pnl.total().total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(pnl.total().realizedLamports()).isEqualTo(100_000L);
    }

    @Test
    void fromTheU2TheLineWithoutBotAndTheSharesAreFilled() {
        PnlScenario scenario = u1();
        scenario.liquidation(30_000_000L, 3_000_000L, 3_000L);
        RealBotPnl query = fixture.query(scenario, PnlByModeProperties.defaults());
        fixture.prices(java.util.Map.of());

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.lines()).allSatisfy(line -> assertThat(line.shares()).hasSize(1));
        UnownedPnlLine unowned = pnl.unowned().orElseThrow();
        assertThat(unowned.reasons()).containsExactly("UNOWNED_NO_COST");
        assertThat(unowned.realizedLamports()).isEqualTo(500_000L);
    }
}
