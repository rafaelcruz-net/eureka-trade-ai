package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealCostMethod.CostPart;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 10 (written before the code; BR2.1 to BR2.4, BR2.6, BR3.1, BR4.1, BR4.3, BR4.4,
 * AC3.2.1 to AC3.2.3, AC3.4.2, Q1): the one REAL average cost, per holder, in lamports and base units. A buy adds its
 * quantity and what it paid for the token (the SOL leg and the RENT of the account it created, never the network fee);
 * a partial sale removes the cost rounded UP, the last sale takes what remains; a holder never sells more than it holds;
 * a sale without bot and the excess of an own sale are shared by quantity with the largest remainder; what passes every
 * recorded position belongs to UNOWNED without cost. The cost of the wallet is the sum of its holders.
 *
 * <p>Bots of the examples (stories.md): A created 2026-11-01T10:00Z; B and C 2026-11-02T10:00Z with the id of B smaller
 * than the id of C. The tie-break is A, B, C, then the part without owner.
 */
class RealCostBasisTest {

    static final Holder A =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000a"), Instant.parse("2026-11-01T10:00:00Z"));
    static final Holder B =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000b"), Instant.parse("2026-11-02T10:00:00Z"));
    static final Holder C =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000c"), Instant.parse("2026-11-02T10:00:00Z"));
    static final Holder UNOWNED = Holder.UNOWNED;

    private static final String XYZ = "XYZ";

    private final RealCostMethod.Accumulator wallet = new RealCostBasis().start();
    private long slot = 1;

    private void buy(Holder holder, long units, long paid) {
        wallet.apply(new CostFill(next(), RealEntryKind.SWAP_FILL_IN, XYZ, units, -paid, Optional.of(holder)));
    }

    private List<CostPart> ownSale(Holder seller, long units, long received) {
        return wallet.apply(
                new CostFill(next(), RealEntryKind.SWAP_FILL_OUT, XYZ, units, received, Optional.of(seller)));
    }

    private List<CostPart> saleWithoutBot(long units, long received) {
        return wallet.apply(new CostFill(next(), RealEntryKind.SWAP_FILL_OUT, XYZ, units, received, Optional.empty()));
    }

    private NetworkOrderKey next() {
        long current = slot++;
        return new NetworkOrderKey(
                current, false, Optional.of("Sig" + current), Instant.EPOCH, "order:" + current, 0, "k" + current);
    }

    private RealPosition of(CostHolder holder) {
        List<RealPosition> open = wallet.openPositions(holder);
        assertThat(open).hasSize(1);
        return open.getFirst();
    }

    private static RealCost known(long lamports) {
        return new RealCost.Known(lamports);
    }

    /** AC3.2.1: A buys 100 for 100.000.000 and 100 for 300.000.000: 200 with cost 400.000.000. */
    @Test
    void ac321TheBuysOfABotAddTheirQuantityAndTheirCost() {
        buy(A, 100, 100_000_000L);
        buy(A, 100, 300_000_000L);

        assertThat(of(A)).isEqualTo(new RealPosition(XYZ, 200, known(400_000_000L)));
    }

    /** AC3.2.2: A sells 50 for 150.000.000: cost removed 100.000.000, realized +50.000.000; A keeps 150 for 300M. */
    @Test
    void ac322APartialSaleRemovesItsShareOfTheCost() {
        buy(A, 100, 100_000_000L);
        buy(A, 100, 300_000_000L);

        List<CostPart> parts = ownSale(A, 50, 150_000_000L);

        assertThat(parts).containsExactly(new CostPart(A, 50, 100_000_000L, false));
        assertThat(150_000_000L - parts.getFirst().costRemovedLamports()).isEqualTo(50_000_000L);
        assertThat(of(A)).isEqualTo(new RealPosition(XYZ, 150, known(300_000_000L)));
    }

    /** AC3.2.3: the buy of B is another holder; nothing of it enters the position of A. */
    @Test
    void ac323AnotherBotIsAnotherHolderAndTheWalletIsTheirSum() {
        buy(A, 100, 100_000_000L);
        buy(A, 100, 300_000_000L);
        ownSale(A, 50, 150_000_000L);
        buy(B, 100, 500_000_000L);

        assertThat(of(A)).isEqualTo(new RealPosition(XYZ, 150, known(300_000_000L)));
        assertThat(of(B)).isEqualTo(new RealPosition(XYZ, 100, known(500_000_000L)));
        assertThat(wallet.openPositions()).containsExactly(new RealPosition(XYZ, 250, known(800_000_000L)));
    }

    /** AC3.4.2: a partial conditional of 500 over A 600 (1,2B) and B 400 (1B): A gives 300 and B 200. */
    @Test
    void ac342APartialConditionalIsSharedByTheQuantityBeforeIt() {
        buy(A, 600, 1_200_000_000L);
        buy(B, 400, 1_000_000_000L);

        List<CostPart> parts = saleWithoutBot(500, 1_500_000_000L);

        assertThat(parts)
                .containsExactly(new CostPart(A, 300, 600_000_000L, false), new CostPart(B, 200, 500_000_000L, false));
        assertThat(of(A)).isEqualTo(new RealPosition(XYZ, 300, known(600_000_000L)));
        assertThat(of(B)).isEqualTo(new RealPosition(XYZ, 200, known(500_000_000L)));
    }

    /** Q1: 3 units with a cost of 10 sold one at a time remove 4, 3 and 3; the total is 10 and the end is exact. */
    @Test
    void q1ThePartialSaleRoundsUpAndTheLastSaleTakesWhatRemains() {
        buy(A, 3, 10);

        long first = ownSale(A, 1, 0).getFirst().costRemovedLamports();
        long second = ownSale(A, 1, 0).getFirst().costRemovedLamports();
        long third = ownSale(A, 1, 0).getFirst().costRemovedLamports();

        assertThat(List.of(first, second, third)).containsExactly(4L, 3L, 3L);
        assertThat(wallet.openPositions(A)).isEmpty();
        assertThat(wallet.openPositions()).isEmpty();
    }

    /** BR2.3, BR4.4, D2: A sells more than it holds; the excess goes to the other holders, UNOWNED included. */
    @Test
    void anOwnSaleBeyondTheSellerGivesTheExcessToTheOtherHoldersByQuantity() {
        buy(A, 100, 100_000_000L);
        buy(B, 50, 50_000_000L);
        buy(UNOWNED, 50, 60L);

        List<CostPart> parts = ownSale(A, 150, 1_500L);

        assertThat(parts)
                .containsExactly(
                        new CostPart(A, 100, 100_000_000L, false),
                        new CostPart(B, 25, 25_000_000L, false),
                        new CostPart(UNOWNED, 25, 30L, false));
        assertThat(of(B)).isEqualTo(new RealPosition(XYZ, 25, known(25_000_000L)));
        assertThat(of(UNOWNED)).isEqualTo(new RealPosition(XYZ, 25, known(30L)));
    }

    /** Q3, BR5.3: what a sale takes beyond every recorded position belongs to UNOWNED, without cost, opening nothing. */
    @Test
    void aSaleBeyondEveryPositionGivesTheRestToUnownedWithoutCost() {
        buy(A, 10, 1_000L);

        List<CostPart> parts = ownSale(A, 12, 1_200L);

        assertThat(parts).containsExactly(new CostPart(A, 10, 1_000L, false), new CostPart(UNOWNED, 2, 0L, true));
        assertThat(wallet.openPositions()).isEmpty();
    }

    /** BR3.1: a buy without a resolved bot is UNOWNED; BR2.1 the RENT of the buy is in its cost (the caller adds it). */
    @Test
    void aBuyWithoutResolvedBotIsUnownedAndAZeroedHolderHasCostZero() {
        buy(UNOWNED, 40, 2_039_280L + 8_000_000L);
        buy(A, 10, 100L);
        ownSale(A, 10, 300L);

        assertThat(of(UNOWNED)).isEqualTo(new RealPosition(XYZ, 40, known(10_039_280L)));
        assertThat(wallet.openPositions(A)).isEmpty();
    }

    /** BR4.1, BR4.2 (Q2): A 2 and B 1, a conditional of 2: the integer parts are A 1, B 0; the remainder goes to B. */
    @Test
    void q2TheQuantityOfASaleWithoutBotGoesByTheLargestRemainder() {
        buy(A, 2, 20L);
        buy(B, 1, 10L);

        List<CostPart> parts = saleWithoutBot(2, 100L);

        assertThat(parts).containsExactly(new CostPart(A, 1, 10L, false), new CostPart(B, 1, 10L, false));
    }

    /** BR4.2: on an equal remainder the older bot, then the smaller id, then UNOWNED, take the units left. */
    @Test
    void anEqualRemainderGoesToTheOlderBotThenTheSmallerIdThenUnowned() {
        buy(C, 1, 1L);
        buy(UNOWNED, 1, 1L);
        buy(B, 1, 1L);
        buy(A, 1, 1L);

        List<CostPart> parts = saleWithoutBot(3, 3L);

        assertThat(parts).extracting(CostPart::holder).containsExactly(A, B, C);
        assertThat(Holder.UNOWNED.compareTo(C)).isPositive();
        assertThat(B.compareTo(C)).isNegative();
        assertThat(A.compareTo(B)).isNegative();
    }

    /** C11: a fill out of network order is refused; nothing is applied. */
    @Test
    void aFillOutOfNetworkOrderIsRefused() {
        NetworkOrderKey later = next();
        NetworkOrderKey earlier = new NetworkOrderKey(0, false, Optional.of("S0"), Instant.EPOCH, "order:0", 0, "k0");
        wallet.apply(new CostFill(later, RealEntryKind.SWAP_FILL_IN, XYZ, 1, -1L, Optional.of(A)));

        assertThatThrownBy(() ->
                        wallet.apply(new CostFill(earlier, RealEntryKind.SWAP_FILL_IN, XYZ, 1, -1L, Optional.of(B))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.openPositions(B)).isEmpty();
    }
}
