package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditRetentionDataSource;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob.Round;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.RiskRows;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 of U6, WRITTEN BEFORE THE CODE (infrastructure-specification.md, NFR2.5, NFR10.6): the purge has its own
 * pool of one connection with the retention login of U2 (30 s wait), so two rounds in a row reuse it and a round runs
 * while the trail purge of U2 holds its own connection with the same login.
 */
class RiskRetentionPoolIT extends AbstractU6IT {

    @Autowired
    private RiskEvaluationPurgeJob job;

    @Autowired
    private RiskRetentionDataSource riskPool;

    @Autowired
    private AuditRetentionDataSource auditPool;

    @Test
    void thePoolOfU6HasOneConnectionWithA30SecondWaitAndTheRetentionLogin() {
        assertThat(riskPool.maximumPoolSize()).isEqualTo(1);
        assertThat(riskPool.connectionTimeoutMillis()).isEqualTo(30_000);
        assertThat(riskPool.jdbc()
                        .sql("select current_user")
                        .query(String.class)
                        .single())
                .isEqualTo(RETENTION.user());
        assertThat(riskPool.jdbc()
                        .sql("select pg_has_role(current_user, 'risk_retention', 'MEMBER')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void twoRoundsInARowReuseTheSingleConnection() {
        Principal person = newPerson();
        new RiskRows(appRole()).evaluation(person, UUID.randomUUID(), "APPROVED", "33 days");

        Round first = job.runOnce();
        Round second = job.runOnce();

        assertThat(first.result()).isEqualTo("SUCCEEDED");
        assertThat(second.result()).isEqualTo("SUCCEEDED");
        assertThat(second.deleted()).isZero();
    }

    @Test
    void aRoundRunsWhileTheTrailPurgeOfU2HoldsItsOwnConnectionWithTheSameLogin() throws Exception {
        Principal person = newPerson();
        UUID old = new RiskRows(appRole()).evaluation(person, UUID.randomUUID(), "APPROVED", "34 days");
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService u2 = Executors.newSingleThreadExecutor();
        try {
            // The U2 pool (also one connection) is busy for 3 seconds, as during a long trail purge.
            Future<?> busy = u2.submit(() -> {
                started.countDown();
                return auditPool.jdbc().sql("select pg_sleep(3)").query().listOfRows();
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            Round round = job.runOnce();

            assertThat(round.result()).isEqualTo("SUCCEEDED");
            assertThat(busy.isDone())
                    .as("the U6 round did not wait for the U2 connection")
                    .isFalse();
            busy.get(30, TimeUnit.SECONDS);
        } finally {
            u2.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from order_evaluation where evaluation_id = :id")
                        .param("id", old)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
