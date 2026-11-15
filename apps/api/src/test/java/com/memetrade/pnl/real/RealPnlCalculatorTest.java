package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.real.RealPnlCalculator.Fill;
import com.memetrade.pnl.real.RealPnlCalculator.Result;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 11 of the U4, written BEFORE the code (BR6.2, S16, S28; team.md "P&L com cenários determinísticos de resultado
 * conhecido, em aritmética decimal e unidades de token"): average cost per mint in the order of confirmation, the buy
 * adds its fee (paid by the main wallet too, read from the order) to the cost, the sell realizes lamports received minus
 * its fee minus the average cost rounded UP, and an unknown price is unknown, never zero.
 */
@Tag("u4-real-execution")
class RealPnlCalculatorTest {

    private static final String MINT = "Mint1111111111111111111111111111111111111111";
    private static final String OTHER = "Mint2222222222222222222222222222222222222222";

    private static Fill buy(String mint, long lamports, long units, long fee) {
        return new Fill(mint, true, lamports, units, fee);
    }

    private static Fill sell(String mint, long lamports, long units, long fee) {
        return new Fill(mint, false, lamports, units, fee);
    }

    @Test
    void s16TwoBuysAndASellGiveTheKnownRealizedResult() {
        // Buys: 1 000 units for 100 000 + fee 5 000; 1 000 units for 300 000 + fee 5 000. Cost 410 000 for 2 000.
        // Sell 1 000 for 260 000 with fee 5 000: cost of the part ceil(410 000 x 1 000 / 2 000) = 205 000.
        // Realized: 260 000 - 5 000 - 205 000 = 50 000.
        Result result = RealPnlCalculator.of(
                List.of(
                        buy(MINT, 100_000, 1_000, 5_000),
                        buy(MINT, 300_000, 1_000, 5_000),
                        sell(MINT, 260_000, 1_000, 5_000)),
                Map.of(MINT, Optional.of(new BigDecimal("250"))));

        assertThat(result.realizedLamports()).isEqualTo(50_000L);
        assertThat(result.feesLamports()).isEqualTo(15_000L);
        assertThat(result.positions()).singleElement().satisfies(position -> {
            assertThat(position.baseUnits()).isEqualTo(1_000L);
            assertThat(position.costLamports()).isEqualTo(205_000L);
            assertThat(position.valueLamports()).contains(250_000L);
            assertThat(position.unrealizedLamports()).contains(45_000L);
        });
        assertThat(result.unrealizedLamports()).contains(45_000L);
    }

    @Test
    void theCostOfThePartSoldIsRoundedUpSoTheRealizedResultIsNeverOverstated() {
        // Cost 1 000 for 3 units; selling 1 takes ceil(333.33) = 334 (realized 166, never 167); 666 stay for 2 units.
        Result result = RealPnlCalculator.of(
                List.of(buy(MINT, 1_000, 3, 0), sell(MINT, 500, 1, 0)), Map.of(MINT, Optional.empty()));

        assertThat(result.realizedLamports()).isEqualTo(166L);
        assertThat(result.positions().getFirst().costLamports()).isEqualTo(666L);
    }

    /**
     * Review (Minor 6), written before the code: the rent the bot wallet paid for the token account created by the buy
     * enters the cost, so the P&L reconciles with the ledger. Buy 1 SOL + fee 5 000 + rent 2 039 280 for 1 000 000
     * units; sell all for 1.1 SOL with fee 5 000. Ledger: -1 000 000 000 - 5 000 - 2 039 280 + 1 100 000 000 - 5 000 =
     * 97 950 720, the realized result.
     */
    @Test
    void theRentPaidByTheBotWalletEntersTheCostAndThePnlReconcilesWithTheLedger() {
        long ledgerSol = -1_000_000_000L - 5_000L - 2_039_280L + 1_100_000_000L - 5_000L;

        Result result = RealPnlCalculator.of(
                List.of(
                        new Fill(MINT, true, 1_000_000_000L, 1_000_000L, 5_000L, 2_039_280L),
                        sell(MINT, 1_100_000_000L, 1_000_000L, 5_000L)),
                Map.of());

        assertThat(result.realizedLamports()).isEqualTo(97_950_720L).isEqualTo(ledgerSol);
        assertThat(result.feesLamports()).isEqualTo(10_000L);
        assertThat(result.rentLamports()).isEqualTo(2_039_280L);
        assertThat(result.positions()).isEmpty();
    }

    @Test
    void anOpenPositionCarriesTheRentInItsCost() {
        Result result = RealPnlCalculator.of(
                List.of(new Fill(MINT, true, 50_000_000L, 1_000_000L, 5_000L, 2_039_280L)),
                Map.of(MINT, Optional.of(new BigDecimal("52"))));

        assertThat(result.positions().getFirst().costLamports()).isEqualTo(52_044_280L);
        assertThat(result.unrealizedLamports()).contains(52_000_000L - 52_044_280L);
    }

    @Test
    void s28TheFeePaidByTheMainWalletEntersTheCostOfTheBuy() {
        Result result = RealPnlCalculator.of(
                List.of(buy(MINT, 50_000_000L, 1_000_000L, 13_457L), sell(MINT, 50_013_457L, 1_000_000L, 0)), Map.of());

        assertThat(result.realizedLamports()).isZero();
        assertThat(result.positions()).isEmpty();
    }

    @Test
    void anUnknownPriceKeepsThePositionAndTheTotalUnknownNeverZero() {
        Result result = RealPnlCalculator.of(
                List.of(buy(MINT, 100_000, 1_000, 0), buy(OTHER, 100_000, 1_000, 0)),
                Map.of(MINT, Optional.of(new BigDecimal("150")), OTHER, Optional.empty()));

        assertThat(result.positions()).hasSize(2);
        assertThat(result.positions().stream()
                        .filter(p -> p.mint().equals(OTHER))
                        .findFirst()
                        .orElseThrow()
                        .unrealizedLamports())
                .isEmpty();
        assertThat(result.unrealizedLamports()).isEmpty();
    }

    @Test
    void aLossIsNegativeAndExact() {
        Result result = RealPnlCalculator.of(
                List.of(buy(MINT, 1_000_000, 10, 5_000), sell(MINT, 400_000, 10, 5_000)), Map.of());

        assertThat(result.realizedLamports()).isEqualTo(-610_000L);
    }

    @Test
    void withoutFillsTheResultIsZeroWithKnownTotals() {
        Result result = RealPnlCalculator.of(List.of(), Map.of());

        assertThat(result.realizedLamports()).isZero();
        assertThat(result.feesLamports()).isZero();
        assertThat(result.unrealizedLamports()).contains(0L);
        assertThat(result.positions()).isEmpty();
    }

    @Test
    void theValueOfAPositionIsRoundedDownFromTheExactPrice() {
        Result result = RealPnlCalculator.of(
                List.of(buy(MINT, 1_000, 3, 0)), Map.of(MINT, Optional.of(new BigDecimal("333.3333"))));

        assertThat(result.positions().getFirst().valueLamports()).contains(999L);
    }
}
