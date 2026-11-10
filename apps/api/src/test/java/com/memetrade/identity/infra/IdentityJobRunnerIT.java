package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeOutcome;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Passo 4 of u2-retention (tests written after the implementation; NFR3.7, NFR7.1, NFR7.4, NFR8.1, NFR8.3, NFR8.4,
 * NFR2.1): the common executor of the Identity purges on the real api context.
 */
@org.junit.jupiter.api.Tag("u2-retention")
@ExtendWith(OutputCaptureExtension.class)
class IdentityJobRunnerIT extends AbstractIdentityJobIT {

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private PurgeRunStore runs;

    @Autowired
    private AuditEventPublisher audit;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IdentityJobMetrics metrics;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private MockMvc mvc;

    @Test
    void twoConcurrentRunsDeleteOnceOnlyOneHoldsTheLock() {
        List<String> old = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            old.add(challenge(clock.instant().minus(Duration.ofDays(2))));
        }

        List<Concurrently.Outcome<Optional<PurgeOutcome>>> outcomes = Concurrently.run(
                () -> runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE),
                () -> runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        List<PurgeOutcome> executed = outcomes.stream()
                .map(Concurrently.Outcome::value)
                .flatMap(Optional::stream)
                .toList();
        assertThat(executed).hasSize(1);
        assertThat(executed.getFirst().deleted()).isEqualTo(20);
        assertThat(old).noneMatch(this::challengeExists);
    }

    @Test
    void aFailureInTheSecondBatchKeepsTheFirstOneAndRecordsFailed() throws Exception {
        for (int i = 0; i < 5; i++) {
            challenge(clock.instant().minus(Duration.ofDays(3)));
        }
        IdentityPurgeProperties twoPerBatch = PurgeFixtures.withBatchSize(2);
        LoginChallengePurgeJob real = new LoginChallengePurgeJob(jdbcClient, twoPerBatch);
        AtomicInteger batches = new AtomicInteger();
        IdentityPurgeJob failingSecondBatch = new DelegatingJob(real) {
            @Override
            public BatchCount deleteBatch(Instant cutoff, int limit) {
                if (batches.incrementAndGet() == 2) {
                    throw new DataAccessResourceFailureException("injected failure of the second batch");
                }
                return real.deleteBatch(cutoff, limit);
            }
        };
        Set<UUID> before = purgeEventIds(IdentityPurge.LOGIN_CHALLENGE_PURGE);

        PurgeOutcome outcome = runnerWith(locks, twoPerBatch, failingSecondBatch)
                .runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        assertThat(outcome.deleted()).isEqualTo(2);
        assertThat(remainingOldChallenges()).isEqualTo(3);
        PurgeRun recorded = runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();
        assertThat(recorded.lastResult()).isEqualTo(PurgeResult.FAILED);
        assertThat(recorded.lastSuccessAt()).isEmpty();
        assertThat(recorded.deleted()).isEqualTo(2);
        List<JsonNode> events = purgeEventsSince(IdentityPurge.LOGIN_CHALLENGE_PURGE, before);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("details").get("result").asText()).isEqualTo("FAILED");
        assertThat(events.getFirst().get("details").get("deleted").asText()).isEqualTo("2");
    }

    @Test
    void aFailedRunKeepsTheLastSuccessOfAnEarlierRun() {
        runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();
        Instant firstSuccess = runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow()
                .lastSuccessAt()
                .orElseThrow();
        challenge(clock.instant().minus(Duration.ofDays(3)));
        clock.advance(Duration.ofDays(1));
        releaseLocks();
        IdentityPurgeJob alwaysFailing = new DelegatingJob(new LoginChallengePurgeJob(jdbcClient, settings())) {
            @Override
            public BatchCount deleteBatch(Instant cutoff, int limit) {
                throw new DataAccessResourceFailureException("database down");
            }
        };

        PurgeOutcome outcome = runnerWith(locks, settings(), alwaysFailing)
                .runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        PurgeRun recorded = runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();
        assertThat(recorded.lastResult()).isEqualTo(PurgeResult.FAILED);
        assertThat(recorded.lastSuccessAt()).contains(firstSuccess);
    }

    @Test
    void anUnavailableDatabaseNeverReachesTheSchedulerNorTakesTheApiDown(CapturedOutput output) throws Exception {
        LockingTaskExecutor brokenLocks = new DefaultLockingTaskExecutor(configuration -> {
            throw new DataAccessResourceFailureException("lock table unreachable");
        });
        IdentityJobRunner broken =
                runnerWith(brokenLocks, settings(), new LoginChallengePurgeJob(jdbcClient, settings()));

        assertThatCode(() -> broken.runScheduled(IdentityPurge.LOGIN_CHALLENGE_PURGE))
                .doesNotThrowAnyException();
        IdentityPurgeJob unreachable = new DelegatingJob(new SessionPurgeJob(jdbcClient, settings())) {
            @Override
            public BatchCount deleteBatch(Instant cutoff, int limit) {
                throw new DataAccessResourceFailureException("connection refused");
            }
        };
        assertThatCode(() -> runnerWith(locks, settings(), unreachable).runScheduled(IdentityPurge.SESSION_PURGE))
                .doesNotThrowAnyException();

        assertThat(output.getOut())
                .contains("Purge could not start: purge=loginChallengePurge, cause=DataAccessResourceFailureException")
                .contains("Purge failed: purge=sessionPurge, deletedSoFar=0")
                .doesNotContain("lock table unreachable")
                .doesNotContain("connection refused");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/legal/current"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theMetricsCarryOnlyThePurgeAndTheResultLabels() {
        challenge(clock.instant().minus(Duration.ofDays(2)));
        double runsBefore = runsCounter("loginChallengePurge", "SUCCEEDED");
        double deletedBefore = meters.get("memetrade.purge.deleted")
                .tag("purge", "loginChallengePurge")
                .counter()
                .count();

        runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();

        assertThat(runsCounter("loginChallengePurge", "SUCCEEDED")).isEqualTo(runsBefore + 1);
        assertThat(meters.get("memetrade.purge.deleted")
                        .tag("purge", "loginChallengePurge")
                        .counter()
                        .count())
                .isEqualTo(deletedBefore + 1);
        assertThat(meters.get("memetrade.purge.last.success")
                        .tag("purge", "loginChallengePurge")
                        .gauge()
                        .value())
                .isEqualTo((double) clock.instant().getEpochSecond());
        List<Meter> purgeMeters = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("memetrade.purge"))
                .toList();
        assertThat(purgeMeters).isNotEmpty();
        for (Meter meter : purgeMeters) {
            assertThat(meter.getId().getTags())
                    .extracting(Tag::getKey)
                    .allMatch(key -> key.equals("purge") || key.equals("result"));
            // u4-data-requests: the Privacy purge registers the same meters with its own label.
            assertThat(meter.getId().getTag("purge"))
                    .isIn("loginChallengePurge", "sessionPurge", "dataSubjectRequestPurge");
        }
    }

    @Test
    void theLogsCarryTotalsOnlyNeverAWalletANonceOrAPerson(CapturedOutput output) {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, wallet.address());
        UUID session = endedSession(person, clock.instant().minus(Duration.ofDays(40)), 2);
        String nonce = challenge(clock.instant().minus(Duration.ofDays(2)));

        runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();
        runner.runLocked(IdentityPurge.SESSION_PURGE).orElseThrow();

        assertThat(output.getOut())
                .contains("Purge finished: purge=loginChallengePurge, deleted=1")
                .contains("Purge finished: purge=sessionPurge, deleted=1, related=2")
                .doesNotContain(nonce)
                .doesNotContain(wallet.address())
                .doesNotContain(session.toString())
                .doesNotContain(person.userId().toString())
                .doesNotContain(person.tenantId().toString());
    }

    @Test
    void sessionsOfTwoTenantsArePurgedAndTheEventExposesNeither() throws Exception {
        TestAccounts.Seeded first =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        TestAccounts.Seeded second =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID firstSession = endedSession(first, clock.instant().minus(Duration.ofDays(31)), 1);
        UUID secondSession = endedSession(second, clock.instant().minus(Duration.ofDays(90)), 3);
        UUID keptSession = endedSession(second, clock.instant().minus(Duration.ofDays(3)), 1);
        Set<UUID> before = purgeEventIds(IdentityPurge.SESSION_PURGE);

        PurgeOutcome outcome = runner.runLocked(IdentityPurge.SESSION_PURGE).orElseThrow();

        assertThat(outcome.deleted()).isEqualTo(2);
        assertThat(outcome.related()).isEqualTo(4);
        assertThat(sessionExists(firstSession)).isFalse();
        assertThat(sessionExists(secondSession)).isFalse();
        assertThat(sessionExists(keptSession)).isTrue();
        List<JsonNode> events = purgeEventsSince(IdentityPurge.SESSION_PURGE, before);
        assertThat(events).hasSize(1);
        JsonNode event = events.getFirst();
        assertThat(event.get("actor").asText()).isEqualTo("system");
        assertThat(event.get("tenantId").isNull()).isTrue();
        assertThat(event.get("details").get("digestsDeleted").asText()).isEqualTo("4");
        assertThat(event.toString())
                .doesNotContain(first.tenantId().toString())
                .doesNotContain(second.tenantId().toString())
                .doesNotContain(first.userId().toString())
                .doesNotContain(second.userId().toString());
        assertThat(lastResult(IdentityPurge.SESSION_PURGE)).isEqualTo("SUCCEEDED");
    }

    private double runsCounter(String purge, String result) {
        return meters.get("memetrade.purge.runs")
                .tag("purge", purge)
                .tag("result", result)
                .counter()
                .count();
    }

    private long remainingOldChallenges() {
        return jdbc.sql("select count(*) from login_challenge where expires_at <= :cutoff")
                .param("cutoff", java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(24))))
                .query(Long.class)
                .single();
    }

    private IdentityPurgeProperties settings() {
        return IdentityPurgeProperties.defaults();
    }

    private IdentityJobRunner runnerWith(
            LockingTaskExecutor lockExecutor, IdentityPurgeProperties settings, IdentityPurgeJob job) {
        return new IdentityJobRunner(lockExecutor, List.of(job), runs, audit, transactions, metrics, settings, clock);
    }

    /** A purge that behaves like {@code delegate} except where a test overrides it. */
    private static class DelegatingJob implements IdentityPurgeJob {
        private final IdentityPurgeJob delegate;

        DelegatingJob(IdentityPurgeJob delegate) {
            this.delegate = delegate;
        }

        @Override
        public IdentityPurge purge() {
            return delegate.purge();
        }

        @Override
        public Instant cutoff(Instant now) {
            return delegate.cutoff(now);
        }

        @Override
        public BatchCount deleteBatch(Instant cutoff, int limit) {
            return delegate.deleteBatch(cutoff, limit);
        }

        @Override
        public Optional<String> relatedDetail() {
            return delegate.relatedDetail();
        }
    }
}
