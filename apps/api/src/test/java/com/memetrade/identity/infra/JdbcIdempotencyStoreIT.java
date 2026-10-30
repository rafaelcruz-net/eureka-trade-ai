package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.idempotency.IdempotencyRecord;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Written before the implementation (Red): idempotency on the real PostgreSQL with the application migrations. */
class JdbcIdempotencyStoreIT extends AbstractPostgresIT {

    private static final String DIGEST = "a".repeat(64);

    @Autowired
    private JdbcIdempotencyStore store;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void claimCompleteAndReplay() {
        String scope = "wallet:" + UUID.randomUUID() + ":auth.challenge";
        UUID key = UUID.randomUUID();
        Instant now = clock.instant();

        assertThat(store.tryClaim(scope, key, DIGEST, now, now.plus(Duration.ofHours(24))))
                .isTrue();
        store.complete(scope, key, 200, "{\"nonce\":\"n\"}", now.plus(Duration.ofMinutes(5)));

        IdempotencyRecord record = store.findActive(scope, key, now).orElseThrow();
        assertThat(record.state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
        assertThat(record.responseStatus()).isEqualTo(200);
        assertThat(record.responseBody()).contains("\"nonce\"");
        assertThat(store.tryClaim(scope, key, DIGEST, now, now.plusSeconds(60))).isFalse();
    }

    @Test
    void anExpiredRecordIsReplacedByANewClaim() {
        String scope = "wallet:" + UUID.randomUUID() + ":auth.challenge";
        UUID key = UUID.randomUUID();
        Instant now = clock.instant();
        store.tryClaim(scope, key, DIGEST, now, now.plusSeconds(10));
        store.complete(scope, key, 200, "{}", now.plusSeconds(10));

        Instant later = now.plusSeconds(10);
        assertThat(store.findActive(scope, key, later)).isEmpty();
        assertThat(store.tryClaim(scope, key, "b".repeat(64), later, later.plusSeconds(60)))
                .isTrue();
        assertThat(store.findActive(scope, key, later).orElseThrow().requestDigest())
                .isEqualTo("b".repeat(64));
    }

    @Test
    void aConcurrentClaimWaitsForTheFirstTransactionAndThenLoses() throws Exception {
        String scope = "tenant:" + UUID.randomUUID() + ":user:x:auth.logout";
        UUID key = UUID.randomUUID();
        Instant now = clock.instant();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            boolean claimed = store.tryClaim(scope, key, DIGEST, now, now.plusSeconds(3600));
            firstClaimed.countDown();
            awaitQuietly(releaseFirst);
            store.complete(scope, key, 204, "", now.plusSeconds(3600));
            return claimed;
        }));
        assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(
                () -> tx.execute(status -> store.tryClaim(scope, key, DIGEST, now, now.plusSeconds(3600))));
        releaseFirst.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void releaseRemovesTheClaimAndCleanupDeletesOnlyExpiredRecordsInBatches() {
        Instant now = clock.instant();
        String releasedScope = "wallet:" + UUID.randomUUID() + ":auth.challenge";
        UUID releasedKey = UUID.randomUUID();
        store.tryClaim(releasedScope, releasedKey, DIGEST, now, now.plusSeconds(60));
        store.release(releasedScope, releasedKey);
        assertThat(store.findActive(releasedScope, releasedKey, now)).isEmpty();

        String prefix = "cleanup:" + UUID.randomUUID() + ":";
        for (int i = 0; i < 3; i++) {
            store.tryClaim(prefix + i, UUID.randomUUID(), DIGEST, now.minusSeconds(120), now.minusSeconds(60));
        }
        UUID liveKey = UUID.randomUUID();
        store.tryClaim(prefix + "live", liveKey, DIGEST, now, now.plusSeconds(3600));

        int deleted = 0;
        int batch;
        while ((batch = store.deleteExpired(now, 2)) > 0) {
            assertThat(batch).isLessThanOrEqualTo(2);
            deleted += batch;
        }
        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(store.findActive(prefix + "live", liveKey, now)).isPresent();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
