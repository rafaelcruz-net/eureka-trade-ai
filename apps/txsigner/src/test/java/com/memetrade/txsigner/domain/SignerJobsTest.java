package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 10, written before the code (reliability-design.md "Trabalhos", NFR5.15): the nonce cleanup deletes only the
 * nonces whose consent expired more than one day ago; the daily check alerts on each key tagged for the bot wallets
 * that has no policy and is older than ten minutes, and ignores keys of other applications.
 */
class SignerJobsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final SignerJobs jobs = new SignerJobs(clock, kms, store, events, Duration.ofMinutes(10));

    @BeforeEach
    void useTheTestClockForKeys() {
        kms.useClock(clock);
    }

    @Test
    void theNonceCleanupDeletesOnlyExpiredNonces() {
        store.insertPolicy(
                policy("A", UUID.randomUUID()), "old", clock.instant().minusSeconds(1));
        store.insertPolicy(
                policy("B", UUID.randomUUID()), "fresh", clock.instant().plusSeconds(60));

        int purged = jobs.purgeNonces();

        assertThat(purged).isEqualTo(1);
        assertThat(store.isNonceUsed("old")).isFalse();
        assertThat(store.isNonceUsed("fresh")).isTrue();
    }

    @Test
    void aBotWalletKeyWithoutPolicyOlderThanTenMinutesIsAnOrphan() {
        String orphan = kms.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));
        clock.advance(Duration.ofMinutes(11));

        int orphans = jobs.reportOrphanKeys();

        assertThat(orphans).isEqualTo(1);
        assertThat(events.all()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SignerEvent.Type.ORPHAN_KEY);
            assertThat(event.botWalletAddress()).isEqualTo(Base58.encode(kms.publicKey(orphan)));
        });
    }

    @Test
    void aYoungKeyAKeyWithPolicyAndAKeyOfAnotherAppAreNotOrphans() {
        UUID request = UUID.randomUUID();
        String withPolicy = kms.createKey(new KeyTags("aa", request.toString(), "LOCALNET"));
        store.insertPolicy(policy(Base58.encode(kms.publicKey(withPolicy)), request), "n1", clock.instant());
        kms.createKeyWithTags(Map.of(
                KeyTags.APP,
                KeyTags.APP_VALUE,
                KeyTags.MAIN_WALLET_MAC,
                "bb",
                KeyTags.CREATION_REQUEST_ID,
                UUID.randomUUID().toString(),
                KeyTags.NETWORK,
                "LOCALNET"));
        clock.advance(Duration.ofMinutes(5));

        assertThat(jobs.reportOrphanKeys()).isZero();
        assertThat(events.all()).isEmpty();
    }

    private WalletPolicy policy(String address, UUID request) {
        String paddedAddress = address.length() >= 32 ? address : (address + "1".repeat(32 - address.length()));
        return new WalletPolicy(
                paddedAddress,
                "ref-" + request,
                paddedAddress,
                0,
                OperationMode.SIGNED,
                false,
                false,
                false,
                request,
                100_000L,
                clock.instant(),
                clock.instant());
    }
}
