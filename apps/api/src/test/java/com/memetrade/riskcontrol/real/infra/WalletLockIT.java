package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.testsupport.Concurrently;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 6 of the U3, written before the code (BR6.1, U3-NFR3.3, U3-NFR4.3, U3-NFR4.8): the transaction lock of the
 * wallet of a person, tried every 20 ms for at most 1 s, held until the end of the transaction, never aborting it.
 */
class WalletLockIT extends AbstractRealRiskIT {

    @Autowired
    private WalletLock lock;

    @Test
    void aLockHeldByAnotherTransactionFailsAfterOneSecondWithoutAbortingTheTransaction() throws Exception {
        UUID user = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            lock.acquire(user);
            held.countDown();
            await(release);
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            Integer stillUsable = transactions.execute(status -> {
                long started = System.nanoTime();
                assertThatThrownBy(() -> lock.acquire(user))
                        .isInstanceOfSatisfying(RealGateUnavailable.class, failure -> assertThat(failure.dependency())
                                .isEqualTo(RealGateUnavailable.Dependency.LOCK));
                Duration waited = Duration.ofNanos(System.nanoTime() - started);
                assertThat(waited).isBetween(Duration.ofMillis(950), Duration.ofMillis(2_500));
                return jdbc.sql("select 1").query(Integer.class).single();
            });
            assertThat(stillUsable).isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void theLockIsReleasedWithItsTransactionAndTheNextOneGetsIt() throws Exception {
        UUID user = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            lock.acquire(user);
            held.countDown();
            sleep(Duration.ofMillis(300));
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        Duration waited = transactions.execute(status -> lock.acquire(user));

        holder.join(10_000);
        assertThat(waited).isPositive().isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void walletsOfDifferentPeopleNeverWaitForEachOther() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        List<Concurrently.Outcome<Duration>> outcomes = Concurrently.run(
                () -> transactions.execute(status -> {
                    Duration wait = lock.acquire(first);
                    sleep(Duration.ofMillis(300));
                    return wait;
                }),
                () -> transactions.execute(status -> {
                    Duration wait = lock.acquire(second);
                    sleep(Duration.ofMillis(300));
                    return wait;
                }));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value()).isLessThan(Duration.ofMillis(100)));
    }

    @Test
    void theLockNeedsATransaction() {
        assertThatThrownBy(() -> lock.acquire(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
