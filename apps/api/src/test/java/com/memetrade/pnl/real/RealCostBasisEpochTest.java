package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.ledger.domain.RealEpochs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2a, Etapa 14a (written before the code; decision of the founder of 2026-11-22, NFR1.4): the
 * cost read only from the EPOCH of each mint (the rows after the last time the quantity of the wallet in that mint
 * reached zero, in network order) equals the cost read from the whole history, per mint and per holder, in scenarios
 * with several zeros, sales without bot shared by quantity, the excess of an own sale, a conditional, the RENT of a buy
 * and rows without a recorded slot. At zero every holder of the mint is at zero (the wallet is the sum of its holders),
 * so nothing before the epoch counts.
 */
class RealCostBasisEpochTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;
    private static final Holder UNOWNED = Holder.UNOWNED;

    /** The rows of one wallet, built in network order, and the holder of each reference (the attribution). */
    static final class Ledger {
        final List<NetworkOrderedEntry> rows = new ArrayList<>();
        final Map<String, Optional<CostHolder>> holderOf = new HashMap<>();
        long slot;

        void buy(Holder holder, String mint, long units, long paid, long rent, boolean recorded) {
            String reference = "order:" + UUID.randomUUID();
            holderOf.put(reference, Optional.of(holder));
            row(RealEntryKind.SWAP_FILL_IN, mint, units, -paid, reference, recorded, 0);
            if (rent > 0) {
                row(RealEntryKind.RENT, null, 0, -rent, reference, recorded, 2);
            }
        }

        void ownSale(Holder seller, String mint, long units, long received) {
            String reference = "order:" + UUID.randomUUID();
            holderOf.put(reference, Optional.of(seller));
            row(RealEntryKind.SWAP_FILL_OUT, mint, units, received, reference, true, 0);
        }

        void saleWithoutBot(String mint, long units, long received, boolean conditional) {
            String reference = (conditional ? "conditional:" : "order:") + UUID.randomUUID();
            holderOf.put(reference, Optional.empty());
            row(RealEntryKind.SWAP_FILL_OUT, mint, units, received, reference, !conditional, 0);
        }

        private void row(
                RealEntryKind kind,
                String mint,
                long units,
                long lamports,
                String reference,
                boolean recorded,
                int rank) {
            if (rank == 0) {
                slot++;
            }
            Optional<String> signature = Optional.of("Sig" + reference);
            NetworkOrderKey key = new NetworkOrderKey(
                    slot, !recorded, signature, Instant.EPOCH.plusSeconds(slot), reference, rank, reference + rank);
            rows.add(new NetworkOrderedEntry(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    kind,
                    lamports,
                    Optional.ofNullable(mint),
                    kind == RealEntryKind.RENT ? Optional.empty() : Optional.of(units),
                    signature,
                    reference,
                    key.createdAt(),
                    key,
                    recorded ? SlotStatus.RECORDED : SlotStatus.SLOT_MISSING));
        }

        /** The accumulator fed with {@code rows}: a buy with the RENT of its reference (BR2.1). */
        RealCostMethod.Accumulator costOf(List<NetworkOrderedEntry> fed) {
            RealCostMethod.Accumulator wallet = new RealCostBasis().start();
            for (int i = 0; i < fed.size(); i++) {
                NetworkOrderedEntry row = fed.get(i);
                if (row.kind() == RealEntryKind.RENT) {
                    continue;
                }
                long lamports = row.lamports();
                if (row.kind() == RealEntryKind.SWAP_FILL_IN
                        && i + 1 < fed.size()
                        && fed.get(i + 1).kind() == RealEntryKind.RENT
                        && fed.get(i + 1).reference().equals(row.reference())) {
                    lamports = Math.addExact(lamports, fed.get(i + 1).lamports());
                }
                wallet.apply(new CostFill(
                        row.key(),
                        row.kind(),
                        row.mint().orElseThrow(),
                        row.tokenBaseUnits().orElseThrow(),
                        lamports,
                        holderOf.get(row.reference())));
            }
            return wallet;
        }

        void assertTheEpochGivesTheCostOfTheWholeHistory() {
            List<NetworkOrderedEntry> fromEpoch = RealEpochs.openLots(rows);
            RealCostMethod.Accumulator whole = costOf(rows);
            RealCostMethod.Accumulator epoch = costOf(fromEpoch);
            // The order of the mints in the list is the order they appeared in what was read; the values are compared.
            assertThat(epoch.openPositions())
                    .as("the wallet")
                    .containsExactlyInAnyOrderElementsOf(whole.openPositions());
            for (Holder holder : List.of(A, B, C, UNOWNED)) {
                assertThat(epoch.openPositions(holder))
                        .as("%s", holder)
                        .containsExactlyInAnyOrderElementsOf(whole.openPositions(holder));
            }
        }
    }

    @Test
    void severalZerosOfAMintKeepOnlyTheLastEpoch() {
        Ledger ledger = new Ledger();
        ledger.buy(A, "XYZ", 100, 1_000, 2_039, true);
        ledger.ownSale(A, "XYZ", 100, 1_500);
        ledger.buy(B, "XYZ", 50, 700, 0, true);
        ledger.saleWithoutBot("XYZ", 50, 800, false);
        ledger.buy(A, "XYZ", 30, 333, 11, true);
        ledger.buy(B, "XYZ", 70, 999, 0, false);

        List<NetworkOrderedEntry> lots = RealEpochs.openLots(ledger.rows);

        assertThat(lots)
                .as("only the rows after the last zero, the RENT of the buy included")
                .hasSize(3);
        ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
    }

    @Test
    void sharedSalesTheExcessAndAConditionalAfterTheEpochGiveTheSameCost() {
        Ledger ledger = new Ledger();
        ledger.buy(C, "ABC", 10, 100, 0, true);
        ledger.saleWithoutBot("ABC", 10, 90, true);
        ledger.buy(A, "ABC", 100, 100_000_000L, 2_039_280L, true);
        ledger.buy(B, "ABC", 50, 50_000_000L, 0, false);
        ledger.buy(UNOWNED, "ABC", 50, 60, 0, true);
        ledger.ownSale(A, "ABC", 150, 1_500);
        ledger.saleWithoutBot("ABC", 20, 400, true);
        ledger.buy(C, "DEF", 7, 70, 0, true);

        ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
    }

    @Test
    void aSaleBeyondEveryPositionZeroesTheMintAndOpensANewEpoch() {
        Ledger ledger = new Ledger();
        ledger.buy(A, "XYZ", 10, 1_000, 0, true);
        ledger.ownSale(A, "XYZ", 12, 1_200);
        ledger.buy(B, "XYZ", 5, 55, 0, true);

        assertThat(RealEpochs.openLots(ledger.rows)).hasSize(1);
        ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
    }

    @Test
    void aMintThatNeverReachedZeroKeepsItsWholeHistory() {
        Ledger ledger = new Ledger();
        ledger.buy(A, "XYZ", 100, 1_000, 0, true);
        ledger.ownSale(A, "XYZ", 40, 600);
        ledger.buy(B, "XYZ", 10, 99, 5, false);

        assertThat(RealEpochs.openLots(ledger.rows)).hasSize(4);
        ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
    }

    @Test
    void aClosedMintBringsNothing() {
        Ledger ledger = new Ledger();
        ledger.buy(A, "XYZ", 100, 1_000, 0, true);
        ledger.saleWithoutBot("XYZ", 100, 1_100, true);

        assertThat(RealEpochs.openLots(ledger.rows)).isEmpty();
        ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
    }

    @Test
    void aLongRandomSequenceWithAFixedSeedGivesTheSameCostFromTheEpoch() {
        Random random = new Random(14L);
        List<Holder> holders = List.of(A, B, C, UNOWNED);
        Ledger ledger = new Ledger();
        for (int i = 0; i < 400; i++) {
            String mint = random.nextBoolean() ? "XYZ" : "ABC";
            Holder holder = holders.get(random.nextInt(holders.size()));
            long units = 1 + random.nextInt(300);
            switch (random.nextInt(4)) {
                case 0, 1 ->
                    ledger.buy(
                            holder,
                            mint,
                            units,
                            1 + random.nextInt(1_000_000),
                            random.nextInt(3),
                            random.nextBoolean());
                case 2 -> ledger.ownSale(holder, mint, units, random.nextInt(1_000_000));
                default -> ledger.saleWithoutBot(mint, units, random.nextInt(1_000_000), random.nextBoolean());
            }
            ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
        }
    }
}
