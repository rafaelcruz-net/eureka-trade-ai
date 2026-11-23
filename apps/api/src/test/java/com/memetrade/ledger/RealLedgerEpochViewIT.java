package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.ledger.api.SlotWriter;
import com.memetrade.ledger.domain.RealEpochs;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (written before the code; decision of the founder of 2026-11-22, NFR1.26,
 * BR1.3, BR2.5): the {@code view} restricted by the marks of zero ({@code real_position_epoch}) equals the read of the
 * whole history, after every transaction: the quantities, the open lots (the rows the cost reads, with their kinds,
 * amounts and RENT), the network order key of each lot (the inheritance of BR1.3 included), the balance, the flows and
 * the count of rows. The writers keep the marks in their own transaction ({@code markEpochs}); a late confirmation that
 * lands before the latest mark writes a new one (OUT_OF_ORDER) and never changes an old one; a mark a writer did not
 * keep makes the read fall back to the whole history, counted with its reason.
 */
@Tag("u2-real-bot-pnl")
class RealLedgerEpochViewIT extends AbstractRealRiskIT {

    @Autowired
    private MeterRegistry registry;

    private long slot = 50_000;

    @Test
    void severalZerosSlotMissingTiesRentWithdrawalsAndAnOverSaleKeepTheViewOfTheWholeHistory() {
        Wallet wallet = releasedWallet(5L * SOL);
        double fallbacks = fallbacks();

        // Several zeros of XYZ, with the RENT of each buy.
        step(wallet, w -> buy(w, "XYZ", 100, 2_039_280, recorded()));
        step(wallet, w -> sell(w, "XYZ", 100, recorded()));
        step(wallet, w -> buy(w, "XYZ", 50, 2_039_280, recorded()));
        step(wallet, w -> sell(w, "XYZ", 50, recorded()));
        // A mint that never reaches zero.
        step(wallet, w -> buy(w, "ABC", 70, 0, recorded()));
        step(wallet, w -> sell(w, "ABC", 20, recorded()));
        // Rows without a recorded slot (SLOT_MISSING) inherit the slot of the previous recorded row.
        step(wallet, w -> buy(w, "XYZ", 30, 2_039_280, Optional.empty()));
        step(wallet, w -> sell(w, "XYZ", 30, Optional.empty()));
        // Two signatures in the same slot (BR1.2), in one transaction.
        step(wallet, w -> {
            long same = ++slot;
            buy(w, "DEF", 10, 0, Optional.of(same));
            buy(w, "DEF", 15, 0, Optional.of(same));
        });
        // A deposit and a withdrawal with its fee, with their slots, between fills.
        step(wallet, w -> wallet(w, Optional.of(++slot)));
        step(wallet, w -> sell(w, "DEF", 25, Optional.empty()));
        // A sale beyond the position.
        step(wallet, w -> buy(w, "GHI", 10, 0, recorded()));
        step(wallet, w -> sell(w, "GHI", 12, recorded()));
        step(wallet, w -> buy(w, "GHI", 5, 2_039_280, recorded()));

        assertThat(marks(wallet)).as("the zeros were marked").isGreaterThanOrEqualTo(4);
        assertThat(fallbacks()).as("no read fell back to the whole history").isEqualTo(fallbacks);
        assertThat(ledger.view(wallet.tenantId(), wallet.botWalletId(), Optional.empty())
                        .positions())
                .extracting(RealPosition::mint, RealPosition::tokenBaseUnits)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ABC", 50L),
                        org.assertj.core.groups.Tuple.tuple("GHI", 5L));
    }

    @Test
    void aLateConfirmationBeforeTheLatestMarkWritesANewMarkAndNeverChangesTheOldOne() {
        Wallet wallet = releasedWallet(5L * SOL);
        step(wallet, w -> buy(w, "XYZ", 100, 0, Optional.of(1_000L)));
        step(wallet, w -> sell(w, "XYZ", 100, Optional.of(1_010L)));
        List<String> before = markRows(wallet);
        double fallbacks = fallbacks();

        // A buy confirmed late: its slot is before the sale that zeroed the mint.
        step(wallet, w -> buy(w, "XYZ", 40, 2_039_280, Optional.of(1_005L)));

        assertThat(markRows(wallet))
                .as("the old marks are kept as they were")
                .startsWith(before.toArray(String[]::new));
        assertThat(reasons(wallet)).contains("OUT_OF_ORDER");
        assertThat(fallbacks())
                .as("the writer re-marked, so the read stays restricted")
                .isEqualTo(fallbacks);
        // In network order the late buy comes before the sale: 100 + 40, then 100 sold, 40 left and no zero any more,
        // so the new mark of XYZ is a full-read mark.
        assertThat(ledger.view(wallet.tenantId(), wallet.botWalletId(), Optional.empty())
                        .positions())
                .extracting(RealPosition::mint, RealPosition::tokenBaseUnits)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("XYZ", 40L));
    }

    @Test
    void aFillWrittenWithoutKeepingTheMarksMakesTheReadFallBackToTheWholeHistory() {
        Wallet wallet = releasedWallet(5L * SOL);
        step(wallet, w -> buy(w, "XYZ", 100, 0, Optional.of(2_000L)));
        step(wallet, w -> sell(w, "XYZ", 100, Optional.of(2_010L)));
        double fallbacks = fallbacks();

        // A late buy written by a path that does not keep the marks: the count of fills disagrees with the mark.
        transactions.executeWithoutResult(status -> buy(wallet, "XYZ", 40, 0, Optional.of(2_005L)));
        assertSameAsTheWholeHistory(wallet, Optional.empty());

        assertThat(fallbacks()).isEqualTo(fallbacks + 1);
    }

    @Test
    void aRowWrittenBeforeTheWindowOfAMarkLowersTheWindowAndTheViewStaysExact() {
        Wallet wallet = releasedWallet(5L * SOL);
        step(wallet, w -> buy(w, "XYZ", 100, 0, Optional.empty()));
        step(wallet, w -> sell(w, "XYZ", 100, Optional.empty()));
        step(wallet, w -> buy(w, "XYZ", 30, 0, Optional.empty()));
        Instant now = clock.instant();

        // The clock of another process is behind: the next rows are created before the window of the mark.
        clock.set(now.minus(Duration.ofHours(1)));
        try {
            step(wallet, w -> wallet(w, Optional.empty()));
            step(wallet, w -> sell(w, "XYZ", 10, Optional.empty()));
        } finally {
            clock.set(now.plus(Duration.ofMinutes(1)));
        }

        assertThat(reasons(wallet)).contains("EARLY_ROW");
        assertSameAsTheWholeHistory(wallet, Optional.of(now.minus(Duration.ofDays(1))));
    }

    // ----- the steps
    // ---------------------------------------------------------------------------------------------------

    private interface Write {
        void apply(Wallet wallet);
    }

    /** One transaction of a writer: its entries, their slots and the marks, then the view checked. */
    private void step(Wallet wallet, Write write) {
        clock.advance(Duration.ofSeconds(30));
        transactions.executeWithoutResult(status -> {
            write.apply(wallet);
            ledger.markEpochs(wallet.tenantId(), wallet.botWalletId());
        });
        assertSameAsTheWholeHistory(wallet, Optional.empty());
        assertSameAsTheWholeHistory(wallet, Optional.of(clock.instant().minus(Duration.ofMinutes(3))));
    }

    private Optional<Long> recorded() {
        return Optional.of(++slot);
    }

    private void buy(Wallet wallet, String mint, long units, long rent, Optional<Long> at) {
        String reference = "order:" + UUID.randomUUID();
        String signature = SyntheticIds.signature();
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.SWAP_FILL_IN,
                -10_000_000L * units,
                signature,
                reference,
                reference + ":IN",
                mint,
                units));
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.NETWORK_FEE,
                -5_000L,
                signature,
                reference,
                reference + ":FEE"));
        if (rent > 0) {
            ledger.record(new RealEntry(
                    wallet.tenantId(),
                    wallet.botWalletId(),
                    RealEntryKind.RENT,
                    -rent,
                    signature,
                    reference,
                    reference + ":RENT"));
        }
        at.ifPresent(s -> ledger.recordSlot(wallet.tenantId(), signature, s, SlotWriter.ORDER_CONFIRMATION));
    }

    private void sell(Wallet wallet, String mint, long units, Optional<Long> at) {
        String reference = "order:" + UUID.randomUUID();
        String signature = SyntheticIds.signature();
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.SWAP_FILL_OUT,
                11_000_000L * units,
                signature,
                reference,
                reference + ":OUT",
                mint,
                units));
        at.ifPresent(s -> ledger.recordSlot(wallet.tenantId(), signature, s, SlotWriter.ORDER_CONFIRMATION));
    }

    /** A deposit, then a withdrawal with its fee in its own signature. */
    private void wallet(Wallet wallet, Optional<Long> at) {
        String deposit = SyntheticIds.signature();
        String dep = "dep:" + deposit;
        ledger.record(new RealEntry(
                wallet.tenantId(), wallet.botWalletId(), RealEntryKind.DEPOSIT, 3_000_000L, deposit, dep, dep));
        String withdrawal = SyntheticIds.signature();
        String wd = "wd:" + UUID.randomUUID();
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.WITHDRAWAL,
                -1_000_000L,
                withdrawal,
                wd,
                wd + ":WITHDRAWAL"));
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.NETWORK_FEE,
                -5_000L,
                withdrawal,
                wd,
                wd + ":FEE"));
        at.ifPresent(s -> {
            ledger.recordSlot(wallet.tenantId(), deposit, s, SlotWriter.WALLET_CONFIRMATION);
            ledger.recordSlot(wallet.tenantId(), withdrawal, s + 1, SlotWriter.WALLET_CONFIRMATION);
        });
    }

    // ----- the oracle: the whole history, read by the stream in network order --------------------------------------

    private void assertSameAsTheWholeHistory(Wallet wallet, Optional<Instant> flowsSince) {
        RealLedgerView view = ledger.view(wallet.tenantId(), wallet.botWalletId(), flowsSince);

        RealEpochs whole = new RealEpochs();
        long[] rows = {0};
        ledger.streamInNetworkOrder(
                wallet.tenantId(), List.of(wallet.botWalletId()), EnumSet.allOf(RealEntryKind.class), entry -> {
                    rows[0]++;
                    whole.accept(entry);
                });

        assertThat(view.positions())
                .as("the quantities")
                .containsExactlyInAnyOrderElementsOf(whole.quantities().positions());
        assertThat(lots(view.openLots())).as("the open lots and their keys").isEqualTo(lots(whole.openLots()));
        assertThat(view.balance()).as("the balance").isEqualTo(ledger.balance(wallet.tenantId(), wallet.botWalletId()));
        assertThat(view.walletRowCount()).as("the rows of the wallet").isEqualTo(rows[0]);
        flowsSince.ifPresent(since -> assertThat(view.flows())
                .as("the flows")
                .isEqualTo(ledger.flowsSince(wallet.tenantId(), wallet.botWalletId(), since)));
    }

    /** What the read of cost uses of a lot: never the entry id, the signature only when its slot is recorded. */
    private record Lot(
            RealEntryKind kind,
            long lamports,
            Optional<String> mint,
            Optional<Long> units,
            String reference,
            long effectiveSlot,
            boolean inherited,
            Optional<String> recordedSignature,
            Instant createdAt,
            int kindRank,
            String idempotencyKey,
            SlotStatus status) {}

    private static List<Lot> lots(List<NetworkOrderedEntry> entries) {
        List<Lot> lots = new ArrayList<>();
        for (NetworkOrderedEntry e : entries) {
            lots.add(new Lot(
                    e.kind(),
                    e.lamports(),
                    e.mint(),
                    e.tokenBaseUnits(),
                    e.reference(),
                    e.key().effectiveSlot(),
                    e.key().inherited(),
                    e.key().inherited() ? Optional.empty() : e.key().txSignature(),
                    e.key().createdAt(),
                    e.key().kindRank(),
                    e.key().idempotencyKey(),
                    e.slotStatus()));
        }
        return lots;
    }

    private long marks(Wallet wallet) {
        return jdbc.sql("select count(*) from real_position_epoch where bot_wallet_id = :w")
                .param("w", wallet.botWalletId())
                .query(Long.class)
                .single();
    }

    private List<String> markRows(Wallet wallet) {
        return jdbc.sql("select concat_ws('|', epoch_seq, mint, zero_slot, zero_idempotency_key, fills_through,"
                        + " window_from, reason) from real_position_epoch where bot_wallet_id = :w order by epoch_seq")
                .param("w", wallet.botWalletId())
                .query(String.class)
                .list();
    }

    private List<String> reasons(Wallet wallet) {
        return jdbc.sql("select reason from real_position_epoch where bot_wallet_id = :w order by epoch_seq")
                .param("w", wallet.botWalletId())
                .query(String.class)
                .list();
    }

    private double fallbacks() {
        return registry.find("ledger.real.epoch.fallbacks").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
