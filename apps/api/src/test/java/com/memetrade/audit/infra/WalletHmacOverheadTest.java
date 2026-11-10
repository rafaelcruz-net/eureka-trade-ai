package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.domain.AuditEventValidator;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * u2-retention NFR3.3, the target check of Build and Test: the HMAC of the wallet and the check of {@code details}
 * add at most 1 ms (p95) to the publication of one event, measured over 10 000 events after a warm-up. Only the work
 * U2 added to the publisher is timed (the hash and the details check); the database write is unchanged by U2.
 */
@Tag("u2-retention")
class WalletHmacOverheadTest {

    private static final String KEY = "unit-test-wallet-hmac-key-not-secret-0123";
    private static final int EVENTS = 10_000;
    private static final int WARM_UP = 2_000;
    private static final Duration LIMIT = Duration.ofMillis(1);

    private final WalletHasher hasher = new WalletHasher(KEY);
    private final AuditEventValidator validator = new AuditEventValidator();

    @Test
    void theHmacAndTheDetailsCheckAddAtMostOneMillisecondAtP95() {
        List<String> wallets = IntStream.range(0, 100)
                .mapToObj(i -> TestWallet.generate().address())
                .toList();
        Map<String, String> details = Map.of("provider", "PHANTOM", "version", "2", "outcome", "ACCEPTED");

        for (int i = 0; i < WARM_UP; i++) {
            addedWork(wallets.get(i % wallets.size()), details);
        }
        long[] samples = new long[EVENTS];
        for (int i = 0; i < EVENTS; i++) {
            long start = System.nanoTime();
            addedWork(wallets.get(i % wallets.size()), details);
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        long p95 = samples[(int) Math.ceil(EVENTS * 0.95) - 1];

        assertThat(Duration.ofNanos(p95)).isLessThanOrEqualTo(LIMIT);
    }

    private void addedWork(String wallet, Map<String, String> details) {
        String hmac = hasher.hmac(wallet);
        validator.validateDetails(details);
        assertThat(hmac).hasSize(64);
    }
}
