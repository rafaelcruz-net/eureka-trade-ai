package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 18, rules written before the code (BR3.1, BR3.2, AC2.2.1, NFR4.6): a finalized deposit is recorded once, by its
 * signature; the cursor moves with the page; a source other than the main wallet is marked unknown; a small deposit
 * enters the balance without a notice; a network outage loses nothing.
 */
class DepositScannerTest {

    private static final String BOT = "GHtXQBsoZHVnNFa9YevAzFr17DJjgHXk3ycTKD5xD3Zi";
    private static final String MAIN = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T";
    private static final Instant AT = Instant.parse("2026-11-14T12:00:00Z");

    private final FakeChain chain = new FakeChain();
    private final DepositStore.WalletToScan wallet =
            new DepositStore.WalletToScan(UUID.randomUUID(), UUID.randomUUID(), BOT, MAIN, null);
    private final Set<String> recorded = new HashSet<>();
    private final List<DepositStore.RecordedDeposit> notices = new ArrayList<>();
    private final Map<UUID, String> cursors = new HashMap<>();

    private final DepositStore store = new DepositStore() {
        @Override
        public List<WalletToScan> activeWallets() {
            return List.of(new WalletToScan(
                    wallet.tenantId(), wallet.botWalletId(), BOT, MAIN, cursors.get(wallet.botWalletId())));
        }

        @Override
        public boolean record(WalletToScan target, RecordedDeposit deposit) {
            if (!recorded.add(deposit.transfer().txSignature())) {
                return false;
            }
            notices.add(deposit);
            return true;
        }

        @Override
        public void advanceCursor(WalletToScan target, String lastSignature) {
            cursors.put(target.botWalletId(), lastSignature);
        }
    };

    private final DepositScanner scanner = new DepositScanner(chain, store);

    private void page(String after, String last, ChainPort.IncomingTransfer... transfers) {
        chain.pages.put(BOT + "@" + after, new ChainPort.IncomingPage(List.of(transfers), last));
    }

    @Test
    void aDepositIsRecordedOnceAndTheCursorMoves() {
        page(null, "s1", new ChainPort.IncomingTransfer("s1", MAIN, 1_000_000_000L, AT));
        page("s1", null);

        assertThat(scanner.scan()).isEqualTo(1);
        assertThat(scanner.scan()).isZero();

        assertThat(recorded).containsExactly("s1");
        assertThat(cursors).containsEntry(wallet.botWalletId(), "s1");
    }

    @Test
    void aRepeatedEntryIsIgnoredWithoutError() {
        page(
                null,
                "s2",
                new ChainPort.IncomingTransfer("s2", MAIN, 5L, AT),
                new ChainPort.IncomingTransfer("s2", MAIN, 5L, AT));

        assertThat(scanner.scan()).isEqualTo(1);
    }

    @Test
    void aSourceOtherThanTheMainWalletIsMarkedUnknown() {
        page(null, "s3", new ChainPort.IncomingTransfer("s3", "Stranger1111111111111111111111111111111111", 7L, AT));

        scanner.scan();

        assertThat(notices).singleElement().satisfies(d -> {
            assertThat(d.knownSource()).isFalse();
            assertThat(d.notifyPerson()).isFalse();
        });
    }

    @Test
    void aSmallDepositEntersTheBalanceWithoutANoticeAndALargeOneNotifies() {
        page(
                null,
                "s5",
                new ChainPort.IncomingTransfer("s4", MAIN, 999_999L, AT),
                new ChainPort.IncomingTransfer("s5", MAIN, 1_000_000L, AT));

        scanner.scan();

        assertThat(notices)
                .extracting(DepositStore.RecordedDeposit::notifyPerson)
                .containsExactly(false, true);
        assertThat(notices).allMatch(DepositStore.RecordedDeposit::knownSource);
    }

    @Test
    void aNetworkOutageLosesNothingAndTheNextCycleResumesFromTheCursor() {
        chain.down = true;
        assertThat(scanner.scan()).isZero();
        assertThat(cursors).isEmpty();

        chain.down = false;
        page(null, "s6", new ChainPort.IncomingTransfer("s6", MAIN, 2L, AT));
        assertThat(scanner.scan()).isEqualTo(1);
    }
}
