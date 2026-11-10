package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (NFR5.4; BR5.6): a bot lock held by another transaction for longer
 * than the {@code lock_timeout} of 2 s (here until the anonymization has answered, about 2 s later, without any sleep)
 * makes the anonymization answer 503 LOCK_TIMEOUT with nothing changed and the failure counted as LOCK_TIMEOUT; the same
 * Idempotency-Key concludes once the lock is released.
 */
class AnonymizationLockTimeoutIT extends AbstractAnonymizationIT {

    @Autowired
    private LockOrder lockOrder;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aLockHeldTooLongIs503AndTheSameKeyConcludesLater() throws Exception {
        Deletion deletion = deletionInProgress();
        UUID botId = seedBot(deletion.guest().principal(), "ACTIVE");
        TenantScope scope = new TenantScope(deletion.tenantId(), deletion.userId());
        UUID key = UUID.randomUUID();
        double before = failures("LOCK_TIMEOUT");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder =
                CompletableFuture.runAsync(() -> transaction().executeWithoutResult(status -> {
                    lockOrder.lockBot(botId, scope).orElseThrow();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        Throwable refused = catchThrowable(() -> anonymize(deletion.admin(), deletion.requestId(), key));
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);

        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).code()).isEqualTo(ErrorCode.LOCK_TIMEOUT);
        assertThat(((ApiException) refused).headers()).containsKey("Retry-After");
        assertThat(waitedMillis).as("it waited the lock_timeout, not forever").isBetween(1500L, 10_000L);
        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
        assertThat(botState(botId)).isEqualTo("ACTIVE");
        assertThat(status(deletion.requestId())).isEqualTo("IN_PROGRESS");
        assertThat(anonymizedEvents(deletion.userId())).isZero();
        assertThat(failures("LOCK_TIMEOUT")).isEqualTo(before + 1);

        AnonymizationResult later = anonymize(deletion.admin(), deletion.requestId(), key);

        assertThat(later.alreadyAnonymized()).isFalse();
        assertThat(later.botsStopped()).isEqualTo(1);
        assertThat(status(deletion.requestId())).isEqualTo("DONE");
    }

    private double failures(String reason) {
        Counter counter = meters.find("memetrade.account.anonymization.failures")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
