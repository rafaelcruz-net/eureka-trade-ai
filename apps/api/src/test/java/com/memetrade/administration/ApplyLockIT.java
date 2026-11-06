package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.infra.ApplyLock;
import com.memetrade.administration.infra.KillSwitchApplier;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.Concurrently;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 5 of U8, written BEFORE {@link ApplyLock} from the rules (BR2.8, NFR2.3, reliability-design.md Q1, review R-03
 * and R-10 of the NFR Design): one holder at a time inside the JVM and across sessions, a single budget of 10 s, and a
 * request that cannot take the lock answers in at most 11 s with {@code reconcileNeeded}, without calling U5.
 */
class ApplyLockIT extends AbstractU8IT {

    @Autowired
    private ApplyLock lock;

    @Autowired
    private KillSwitchApplier applier;

    @Autowired
    private KillSwitchStore store;

    @Test
    void theLockIsHeldByOneSessionAndReleasedOnClose() {
        JdbcClient other = connect(SUPERUSER);
        try (ApplyLock.Held held = lock.tryAcquire().orElseThrow()) {
            assertThat(held.stillValid()).isTrue();
            assertThat(tryAdvisory(other)).isFalse();
        }

        assertThat(tryAdvisory(other)).isTrue();
        unlockAdvisory(other);
    }

    /** R-03 / R-10: the advisory lock is re-entrant per session, so the JVM lock keeps two threads apart. */
    @Test
    void twoThreadsOfTheJvmNeverHoldTheLockTogether() {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Callable<Boolean> task = () -> {
            try (ApplyLock.Held held = lock.tryAcquire().orElseThrow()) {
                maximum.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(50);
                inside.decrementAndGet();
                return held.stillValid();
            }
        };

        List<Concurrently.Outcome<Boolean>> outcomes = Concurrently.run(task, task, task, task, task);

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes).allMatch(outcome -> Boolean.TRUE.equals(outcome.value()));
        assertThat(maximum.get()).isEqualTo(1);
    }

    /** NFR2.3: the whole wait of one application fits a single budget of 10 s. */
    @Test
    void theWaitHasOneBudgetOfTenSeconds() {
        assertThat(lock.budget()).isEqualTo(Duration.ofSeconds(10));
    }

    /** BR2.8 / NFR2.3: held by another connection, the applier gives up within the budget and U5 is never called. */
    @Test
    void aLockHeldByAnotherConnectionMarksTheReconciliationWithoutCallingU5() {
        JdbcClient other = connect(SUPERUSER);
        assertThat(lockAdvisory(other)).isTrue();
        try {
            long started = System.nanoTime();
            KillSwitchApplier.Outcome outcome = applier.applyCurrent("apply-lock-it");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.LOCK_TIMEOUT);
            assertThat(outcome.reconcileNeeded()).isTrue();
            assertThat(took).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(11));
            assertThat(u5.calls()).isEmpty();
            assertThat(store.read().reconcileNeeded()).isTrue();
        } finally {
            unlockAdvisory(other);
        }
    }

    /** NFR2.3 through the route: the PUT answers in at most 11 s with the state written and reconcileNeeded. */
    @Test
    void thePutAnswersWithinElevenSecondsWhenTheLockIsHeldElsewhere() throws Exception {
        JdbcClient other = connect(SUPERUSER);
        assertThat(lockAdvisory(other)).isTrue();
        try {
            long started = System.nanoTime();
            MockHttpServletResponse response = putKillSwitch(sessionOf(newAdministrator()), "ON", "Trava presa");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode answer = body(response);
            assertThat(answer.get("state").asText()).isEqualTo("ON");
            assertThat(answer.get("reconcileNeeded").asBoolean()).isTrue();
            assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(11));
            assertThat(u5.calls()).isEmpty();
        } finally {
            unlockAdvisory(other);
        }
    }

    /** After the other connection lets go, the next application takes the lock and clears the mark. */
    @Test
    void theNextApplicationAfterTheReleaseClearsTheMark() {
        JdbcClient other = connect(SUPERUSER);
        assertThat(lockAdvisory(other)).isTrue();
        try {
            applier.applyCurrent("held-elsewhere");
        } finally {
            unlockAdvisory(other);
        }

        KillSwitchApplier.Outcome next = applier.applyCurrent("released");

        assertThat(next.result()).isEqualTo(KillSwitchApplier.Result.APPLIED);
        assertThat(store.read().reconcileNeeded()).isFalse();
        assertThat(u5.calls()).hasSize(1);
    }

    private static boolean tryAdvisory(JdbcClient session) {
        return session.sql("select pg_try_advisory_lock(:key)")
                .param("key", ApplyLock.KEY)
                .query(Boolean.class)
                .single();
    }

    private static boolean lockAdvisory(JdbcClient session) {
        return tryAdvisory(session);
    }

    private static void unlockAdvisory(JdbcClient session) {
        session.sql("select pg_advisory_unlock(:key)")
                .param("key", ApplyLock.KEY)
                .query(Boolean.class)
                .single();
    }
}
