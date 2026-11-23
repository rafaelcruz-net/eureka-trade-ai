package com.memetrade.pnl.real;

import static com.memetrade.pnl.real.PnlScenario.XYZ;
import static com.memetrade.pnl.real.PnlScenario.line;
import static com.memetrade.pnl.real.PnlScenario.price;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.UnownedPnlLine;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR6.1 to BR6.6, BR2.1, BR7.1): the network fee and
 * the RENT go to the right holder, in the lines of fees, never into the cost (except the RENT of a buy) nor into the
 * realized, and the mode counts each one exactly once.
 */
class RealPnlFeesTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;

    private static PnlScenario abc() {
        return new PnlScenario().bot(A).bot(B).bot(C);
    }

    private static void assertMatch(RealPnlResult result) {
        assertThat(result.reconciliation().status())
                .as("%s", result.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
    }

    /** BR6.1, BR2.1: the fee of a buy is on its holder's fees and never in its cost. */
    @Test
    void theFeeOfABuyIsAFeeOfItsHolderAndNeverPartOfTheCost() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 5_000L);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 1_000_000L));

        assertThat(line(result, A).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, A).unrealized()).isEqualTo(new LamportsValue.Known(0L));
        assertThat(result.positions().getFirst().costLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, A).total()).isEqualTo(new LamportsValue.Known(-5_000L));
        assertMatch(result);
    }

    /** BR2.1: the RENT of the account a buy created is in its cost, not in the fees. */
    @Test
    void theRentOfABuyIsInItsCost() {
        PnlScenario ledger = abc();
        ledger.buyWithRent(A, 100, 100_000_000L, 2_039_280L, 5_000L);
        ledger.ownSale(A, 100, 110_000_000L, 0);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(110_000_000L - 102_039_280L);
        assertThat(line(result, A).feesLamports()).isEqualTo(5_000L);
        assertMatch(result);
    }

    /** BR6.1: the fee of an own sale with excess is shared by the quantity sold by each participant. */
    @Test
    void theFeeOfAnOwnSaleWithExcessIsSharedByTheQuantitySold() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 1L, 0);
        ledger.buy(B, 300, 3L, 0);
        ledger.ownSale(A, 200, 1_000L, 8_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(4_000L);
        assertThat(line(result, B).feesLamports()).isEqualTo(4_000L);
        assertMatch(result);
    }

    /** BR6.2 (AC3.7.1): a failed order whose fee the bot wallet paid: all of it on the bot; nothing else changes. */
    @Test
    void aFailedOrderPaidByTheBotWalletPutsTheWholeFeeOnTheBot() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.failed(A, 5_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, A).realizedLamports()).isZero();
        assertThat(result.positions().getFirst().tokenBaseUnits()).isEqualTo(100L);
        assertThat(result.positions().getFirst().costLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, B).feesLamports()).isZero();
        assertMatch(result);
    }

    /** BR6.2, BR6.3: a failed order of the mode Assinada (no row, read from the order) is on its bot too. */
    @Test
    void aFailedOrderPaidByTheMainWalletIsReadFromTheOrderAndPutOnTheBot() {
        PnlScenario ledger = abc();
        ledger.buy(B, 10, 10L, 0);
        ledger.signedFailed(B, 5_000L);
        ledger.buy(A, 10, 10L, 0);

        RealPnlResult result = ledger.all();

        assertThat(line(result, B).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, A).feesLamports()).isZero();
        assertThat(result.pnl().total().feesLamports()).isEqualTo(5_000L);
        assertMatch(result);
    }

    /** BR6.2: the fee of a failed liquidation is shared by the quantities just before it, as LIQUIDATION shares. */
    @Test
    void theFeeOfAFailedLiquidationIsSharedByTheQuantitiesBeforeIt() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100L, 0);
        ledger.buy(B, 300, 300L, 0);
        ledger.failedLiquidation(5_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(1_250L);
        assertThat(line(result, B).feesLamports()).isEqualTo(3_750L);
        assertThat(line(result, A).shares())
                .extracting(
                        ProratedShare::origin,
                        ProratedShare::tokenBaseUnits,
                        ProratedShare::receivedLamports,
                        ProratedShare::feeLamports)
                .containsExactly(Tuple.tuple("LIQUIDATION", 0L, 0L, 1_250L));
        assertThat(result.positions().getFirst().tokenBaseUnits()).isEqualTo(400L);
        assertMatch(result);
    }

    /** BR6.2: the same base in the mode Assinada: the quantities just before the attempt, by its instant. */
    @Test
    void aFailedLiquidationOfTheModeAssinadaUsesTheQuantitiesJustBeforeTheAttempt() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100L, 0);
        ledger.buy(B, 300, 300L, 0);
        ledger.signedFailedLiquidation(4_000L);
        ledger.ownSale(B, 300, 600L, 0);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(1_000L);
        assertThat(line(result, B).feesLamports()).isEqualTo(3_000L);
        assertMatch(result);
    }

    /**
     * Review R-04 (written before the fix): a person with two bot wallets. The failed liquidation of the mode Assinada
     * of the first wallet is shared among the holders of THAT wallet, never of the wallet whose rows the stream reads
     * next (the stream reads the wallets one after the other) nor of the last wallet read.
     */
    @Test
    void aVirtualAttemptIsSharedInItsOwnWalletWhenThePersonHasTwoWallets() {
        PnlScenario ledger = abc();
        ledger.at("2026-11-10T09:00:00Z").buy(A, 100, 100L, 0);
        ledger.at("2026-11-10T09:10:00Z").signedFailedLiquidation(4_000L);
        ledger.inWallet(PnlScenario.SECOND_WALLET);
        ledger.at("2026-11-10T09:20:00Z").buy(B, 300, 300L, 0);
        ledger.at("2026-11-10T09:30:00Z").ownSale(B, 300, 600L, 0);
        ledger.inWallet(ledger.wallet);
        ledger.at("2026-11-10T09:40:00Z").signedFailedLiquidation(2_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports())
                .as("both failed liquidations were of the wallet of A")
                .isEqualTo(6_000L);
        assertThat(line(result, B).feesLamports()).isZero();
        assertMatch(result);
    }

    /** BR6.2: a failed liquidation without any holder goes to "Sem bot" with UNOWNED_NO_COST. */
    @Test
    void aFailedLiquidationWithoutAnyHolderGoesToTheLineWithoutBot() {
        PnlScenario ledger = abc();
        ledger.failedLiquidation(5_000L);

        RealPnlResult result = ledger.all();

        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.feesLamports()).isEqualTo(5_000L);
        assertThat(unowned.reasons()).containsExactly("UNOWNED_NO_COST");
        assertMatch(result);
    }

    /** BR6.3: the fee paid by the bot wallet comes only from the NETWORK_FEE row, never also from the order. */
    @Test
    void theFeeIsNeverCountedTwice() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100L, 5_000L);
        ledger.signedBuy(B, 100, 100L, 7_000L);
        ledger.signedOwnSale(B, 50, 80L, 3_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, B).feesLamports()).isEqualTo(10_000L);
        assertThat(result.pnl().total().feesLamports()).isEqualTo(15_000L);
        assertMatch(result);
    }

    /** BR6.4 (AC3.7.4): the fee of a sale without bot is shared with the received and the parts sum it. */
    @Test
    void theFeeOfASaleWithoutBotIsSharedWithTheReceived() {
        PnlScenario ledger = abc();
        ledger.buy(A, 50, 400_000_000L, 0);
        ledger.buy(B, 30, 210_000_000L, 0);
        ledger.buy(C, 20, 160_000_000L, 0);
        ledger.conditional(100, 777_777_777L, 7_001L);

        RealPnlResult result = ledger.all();

        long fees = line(result, A).feesLamports()
                + line(result, B).feesLamports()
                + line(result, C).feesLamports();
        assertThat(fees).isEqualTo(7_001L);
        assertThat(line(result, A).feesLamports()).isEqualTo(3_501L);
        assertMatch(result);
    }

    /** BR6.5: deposits, withdrawals and their fees, reserves and compensations never enter the P&L. */
    @Test
    void onlyTheTradingRowsEnterThePnl() {
        PnlScenario ledger = abc();
        ledger.notTrading();
        ledger.buy(A, 100, 100L, 1_000L);
        ledger.notTrading();

        RealPnlResult result = ledger.all();

        assertThat(result.pnl().total().feesLamports()).isEqualTo(1_000L);
        assertThat(result.pnl().total().realizedLamports()).isZero();
        assertThat(result.positions().getFirst().tokenBaseUnits()).isEqualTo(100L);
        assertMatch(result);
    }

    /** BR6.6: a RENT outside a buy goes to the fees of the holder of its order, and the mode counts it once. */
    @Test
    void aRentOutsideABuyGoesToTheFeesOfTheHolderOfItsOrder() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100L, 0);
        ledger.ownSaleWithRent(A, 50, 100L, 0, 2_000L);
        ledger.liquidationWithRent(50, 100L, 0, 3_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(2_000L);
        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.feesLamports()).isEqualTo(3_000L);
        assertThat(unowned.reasons()).containsExactly("UNOWNED_NO_COST");
        assertThat(result.pnl().total().feesLamports()).isEqualTo(5_000L);
        assertMatch(result);
    }
}
