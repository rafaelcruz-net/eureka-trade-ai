package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 6 (written before the code; BR1.1 to BR1.3, BR1.5, BR1.6, C12, NFR2.4, NFR2.10,
 * NFR2.12, NFR4.3): the entries of the bot wallets come one by one, grouped by wallet and in network order, with the
 * slot of {@code real_entry_slot} or, without it, the one inherited from the last recorded row before it in recording
 * order {@code (created_at, reference, kind_rank, idempotency_key)}; every tie-break of text is by bytes
 * ({@code COLLATE "C"}); the kinds asked for are filtered after the inheritance; only the tenant's wallets, never
 * PAPER. The rows are written directly with chosen instants and signatures so each case is exact.
 */
@Tag("u2-real-bot-pnl")
class RealLedgerNetworkOrderIT extends AbstractPostgresIT {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Set<RealEntryKind> ALL = EnumSet.allOf(RealEntryKind.class);

    @Autowired
    private RealLedger ledger;

    @Autowired
    private MeterRegistry registry;

    private record Wallet(UUID tenantId, UUID botWalletId) {}

    /** AC3.6.1: in the same slot, the buy of B with the smaller signature comes before the sale of A. */
    @Test
    void inTheSameSlotTheBuyWithTheSmallerSignatureComesBeforeTheSale() {
        Wallet wallet = wallet();
        String[] signatures = sortedSignatures(2);
        String buy = signatures[0];
        String sale = signatures[1];
        // The sale is written first: the order of writing never decides between two recorded signatures.
        entry(wallet, RealEntryKind.SWAP_FILL_OUT, 400_000_000L, sale, "order:sale", "sale:fill", T0, 200L);
        entry(wallet, RealEntryKind.NETWORK_FEE, -5_000L, sale, "order:sale", "sale:fee", T0, null);
        entry(wallet, RealEntryKind.SWAP_FILL_IN, -300_000_000L, buy, "order:buy", "buy:fill", T0.plusSeconds(5), 100L);
        entry(wallet, RealEntryKind.NETWORK_FEE, -5_000L, buy, "order:buy", "buy:fee", T0.plusSeconds(5), null);
        slot(sale, 777L);
        slot(buy, 777L);

        List<NetworkOrderedEntry> read = read(wallet, ALL);

        assertThat(read)
                .extracting(RealLedgerNetworkOrderIT::shortKey)
                .containsExactly("buy:fill", "buy:fee", "sale:fill", "sale:fee");
        assertThat(read).allSatisfy(e -> {
            assertThat(e.slotStatus()).isEqualTo(SlotStatus.RECORDED);
            assertThat(e.key().effectiveSlot()).isEqualTo(777L);
            assertThat(e.key().inherited()).isFalse();
        });
    }

    /** AC3.6.2: writing the same rows in another order gives the same keys and the same sequence. */
    @Test
    void writingTheRowsInAnotherOrderGivesTheSameSequence() {
        String[] signatures = sortedSignatures(3);
        Wallet first = wallet();
        Wallet second = wallet();
        List<Object[]> rows = List.of(
                new Object[] {RealEntryKind.SWAP_FILL_IN, -100L, signatures[2], "order:a", 30L, 500L},
                new Object[] {RealEntryKind.SWAP_FILL_OUT, 90L, signatures[0], "order:b", 10L, 501L},
                new Object[] {RealEntryKind.SWAP_FILL_IN, -200L, signatures[1], "order:c", 20L, 500L});
        for (int i = 0; i < rows.size(); i++) {
            insertRow(first, rows.get(i), T0.plusSeconds(i));
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            insertRow(second, rows.get(i), T0.plusSeconds(10 - i));
        }

        List<String> firstOrder = references(read(first, ALL));
        List<String> secondOrder = references(read(second, ALL));

        assertThat(firstOrder).containsExactly("order:c", "order:a", "order:b");
        assertThat(secondOrder).isEqualTo(firstOrder);
    }

    /** BR1.3 and the example "same slot without a recorded slot": inheritance over every row of the wallet. */
    @Test
    void aRowWithoutRecordedSlotInheritsTheLastRecordedSlotBeforeItAndComesAfterTheRecordedRowsOfThatSlot() {
        Wallet wallet = wallet();
        String[] signatures = sortedSignatures(4);
        entry(wallet, RealEntryKind.RESERVE, 1_000L, null, "order:r", "r:reserve", T0, null);
        entry(wallet, RealEntryKind.SWAP_FILL_IN, -1_000L, signatures[2], "order:r", "r:fill", T0.plusSeconds(1), 10L);
        entry(
                wallet,
                RealEntryKind.SWAP_FILL_IN,
                -2_000L,
                signatures[1],
                "order:old1",
                "old1:fill",
                T0.plusSeconds(2),
                5L);
        entry(
                wallet,
                RealEntryKind.SWAP_FILL_OUT,
                3_000L,
                signatures[3],
                "order:old2",
                "old2:fill",
                T0.plusSeconds(3),
                5L);
        entry(wallet, RealEntryKind.SWAP_FILL_IN, -4_000L, signatures[0], "order:s", "s:fill", T0.plusSeconds(4), 10L);
        slot(signatures[2], 900L);
        slot(signatures[0], 900L);

        List<NetworkOrderedEntry> read = read(wallet, ALL);

        assertThat(read)
                .extracting(RealLedgerNetworkOrderIT::shortKey)
                .containsExactly("r:reserve", "s:fill", "r:fill", "old1:fill", "old2:fill");
        assertThat(read.get(0).key().effectiveSlot()).isZero();
        assertThat(read.get(0).key().inherited()).isTrue();
        assertThat(read.get(0).slotStatus()).isEqualTo(SlotStatus.NO_NETWORK_TX);
        assertThat(read.subList(1, 3))
                .allSatisfy(e -> assertThat(e.slotStatus()).isEqualTo(SlotStatus.RECORDED));
        assertThat(read.subList(3, 5)).allSatisfy(e -> {
            assertThat(e.slotStatus()).isEqualTo(SlotStatus.SLOT_MISSING);
            assertThat(e.key().effectiveSlot()).isEqualTo(900L);
            assertThat(e.key().inherited()).isTrue();
        });
    }

    /** BR1.6: the kinds asked for are filtered AFTER the inheritance, so every reader sees the same key. */
    @Test
    void theKindsAreFilteredAfterTheInheritance() {
        Wallet wallet = wallet();
        String recorded = SyntheticIds.signature();
        entry(wallet, RealEntryKind.NETWORK_FEE, -5_000L, recorded, "order:f", "f:fee", T0, null);
        entry(
                wallet,
                RealEntryKind.SWAP_FILL_IN,
                -1_000L,
                SyntheticIds.signature(),
                "order:g",
                "g:fill",
                T0.plusSeconds(1),
                7L);
        slot(recorded, 321L);

        List<NetworkOrderedEntry> fills = read(wallet, EnumSet.of(RealEntryKind.SWAP_FILL_IN));

        assertThat(fills).singleElement().satisfies(e -> {
            assertThat(shortKey(e)).isEqualTo("g:fill");
            assertThat(e.key().effectiveSlot()).isEqualTo(321L);
            assertThat(e.key().inherited()).isTrue();
        });
    }

    /** NFR2.12: three rows SLOT_MISSING delivered count three. */
    @Test
    void threeRowsSlotMissingCountThree() {
        Wallet wallet = wallet();
        for (int i = 0; i < 3; i++) {
            entry(
                    wallet,
                    RealEntryKind.SWAP_FILL_IN,
                    -1_000L,
                    SyntheticIds.signature(),
                    "order:m" + i,
                    "m" + i,
                    T0.plusSeconds(i),
                    1L);
        }
        entry(wallet, RealEntryKind.RESERVE, 1_000L, null, "order:m0", "m0:reserve", T0, null);
        double before = registry.get("ledger.real.slot_missing").counter().count();

        read(wallet, ALL);

        assertThat(registry.get("ledger.real.slot_missing").counter().count() - before)
                .isEqualTo(3.0);
    }

    /** NFR3.1, NFR4.3: a wallet of another tenant gives nothing; only REAL rows of the wallets asked for. */
    @Test
    void aWalletOfAnotherTenantGivesNothingAndOnlyTheWalletsAskedForAreRead() {
        Wallet mine = wallet();
        Wallet theirs = wallet();
        entry(mine, RealEntryKind.SWAP_FILL_IN, -1_000L, SyntheticIds.signature(), "order:mine", "mine", T0, 1L);
        entry(theirs, RealEntryKind.SWAP_FILL_IN, -1_000L, SyntheticIds.signature(), "order:theirs", "theirs", T0, 1L);

        List<NetworkOrderedEntry> crossed = new ArrayList<>();
        ledger.streamInNetworkOrder(mine.tenantId(), List.of(theirs.botWalletId()), ALL, crossed::add);
        List<NetworkOrderedEntry> both = new ArrayList<>();
        ledger.streamInNetworkOrder(mine.tenantId(), List.of(mine.botWalletId(), theirs.botWalletId()), ALL, both::add);

        assertThat(crossed).isEmpty();
        assertThat(both).extracting(RealLedgerNetworkOrderIT::shortKey).containsExactly("mine");
        assertThat(both).allSatisfy(e -> assertThat(e.botWalletId()).isEqualTo(mine.botWalletId()));
    }

    /** NFR2.4: rows older than the migration, all without a slot, read in the order of the inheritance fallback. */
    @Test
    void oldRowsWithoutSlotAreReadInTheRecordingOrderOfTheFallback() {
        Wallet wallet = wallet();
        entry(wallet, RealEntryKind.SWAP_FILL_IN, -1_000L, SyntheticIds.signature(), "order:b", "b:fill", T0, 5L);
        entry(wallet, RealEntryKind.SWAP_FILL_IN, -1_000L, SyntheticIds.signature(), "order:a", "a:fill", T0, 5L);
        entry(wallet, RealEntryKind.NETWORK_FEE, -5L, SyntheticIds.signature(), "order:a", "a:fee", T0, null);
        entry(
                wallet,
                RealEntryKind.SWAP_FILL_OUT,
                9_000L,
                SyntheticIds.signature(),
                "order:c",
                "c:fill",
                T0.minusSeconds(1),
                5L);

        List<NetworkOrderedEntry> read = read(wallet, ALL);

        assertThat(read)
                .extracting(RealLedgerNetworkOrderIT::shortKey)
                .containsExactly("c:fill", "a:fill", "a:fee", "b:fill");
        assertThat(read).allSatisfy(e -> {
            assertThat(e.key().effectiveSlot()).isZero();
            assertThat(e.slotStatus()).isEqualTo(SlotStatus.SLOT_MISSING);
        });
    }

    /** F-1, review R-05: every tie-break of text is by bytes, whatever the collation of the database. */
    @Test
    void theSqlOrdersTheSignaturesAndTheReferencesByBytesLikeTheOracle() {
        Wallet wallet = wallet();
        List<String> signatures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String signature = SyntheticIds.signature();
            signatures.add(signature);
            entry(wallet, RealEntryKind.SWAP_FILL_IN, -1_000L, signature, "order:s" + i, "s" + i, T0, 1L);
            slot(signature, 42L);
        }
        List<String> references = List.of("dep:B", "dep:a", "dep:A", "dep:b", "dep:_", "dep:1");
        for (String reference : references) {
            entry(wallet, RealEntryKind.DEPOSIT, 10L, null, reference, "k-" + reference, T0.plusSeconds(60), null);
        }

        List<NetworkOrderedEntry> read = read(wallet, ALL);

        List<String> bytesOrder = new ArrayList<>(signatures);
        bytesOrder.sort(BYTES);
        List<String> expectedReferences = new ArrayList<>(references);
        expectedReferences.sort(BYTES);
        assertThat(read.subList(0, 20))
                .extracting(e -> e.txSignature().orElseThrow())
                .containsExactlyElementsOf(bytesOrder);
        assertThat(read.subList(20, 26))
                .extracting(NetworkOrderedEntry::reference)
                .containsExactlyElementsOf(expectedReferences);
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private static final Comparator<String> BYTES =
            (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private List<NetworkOrderedEntry> read(Wallet wallet, Set<RealEntryKind> kinds) {
        List<NetworkOrderedEntry> read = new ArrayList<>();
        ledger.streamInNetworkOrder(wallet.tenantId(), List.of(wallet.botWalletId()), kinds, read::add);
        return read;
    }

    /** The idempotency key of the test without the wallet prefix that keeps it unique in the shared database. */
    private static String shortKey(NetworkOrderedEntry entry) {
        String key = entry.key().idempotencyKey();
        return key.substring(key.indexOf(':') + 1);
    }

    private static List<String> references(List<NetworkOrderedEntry> read) {
        return read.stream().map(NetworkOrderedEntry::reference).toList();
    }

    private static String[] sortedSignatures(int count) {
        String[] signatures = new String[count];
        for (int i = 0; i < count; i++) {
            signatures[i] = SyntheticIds.signature();
        }
        Arrays.sort(signatures, BYTES);
        return signatures;
    }

    private Wallet wallet() {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botWalletId = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(T0.minusSeconds(3600)))
                .update();
        return new Wallet(person.tenantId(), botWalletId);
    }

    private void insertRow(Wallet wallet, Object[] row, Instant at) {
        RealEntryKind kind = (RealEntryKind) row[0];
        String signature = (String) row[2];
        entry(wallet, kind, (Long) row[1], signature, (String) row[3], row[3] + ":" + kind, at, (Long) row[4]);
        jdbc.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, :slot, now())"
                        + " on conflict do nothing")
                .param("s", signature)
                .param("slot", (Long) row[5])
                .update();
    }

    private void entry(
            Wallet wallet,
            RealEntryKind kind,
            long lamports,
            String signature,
            String reference,
            String key,
            Instant at,
            Long units) {
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                          tx_signature, reference, idempotency_key, created_at, mint, token_base_units)
                        values (:id, :tenant, :wallet, :kind, :lamports, :sig, :ref, :key, :at, :mint, :units)""")
                .param("id", UUID.randomUUID())
                .param("tenant", wallet.tenantId())
                .param("wallet", wallet.botWalletId())
                .param("kind", kind.name())
                .param("lamports", lamports)
                .param("sig", signature)
                .param("ref", reference)
                .param("key", wallet.botWalletId() + ":" + key)
                .param("at", Timestamp.from(at))
                .param("mint", units == null ? null : "MintNetworkOrder1111111111111111111111111")
                .param("units", units == null ? null : java.math.BigDecimal.valueOf(units))
                .update();
    }

    private void slot(String signature, long slot) {
        jdbc.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, :slot, now())")
                .param("s", signature)
                .param("slot", slot)
                .update();
    }
}
