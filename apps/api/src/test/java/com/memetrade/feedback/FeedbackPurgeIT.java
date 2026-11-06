package com.memetrade.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.feedback.infra.FeedbackPurgeHealthIndicator;
import com.memetrade.feedback.infra.FeedbackPurgeJob;
import com.memetrade.feedback.infra.FeedbackRetentionDataSource;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessException;

/**
 * Passo 9 of U8 (BR6.7, NFR2.6, NFR4.1, observability-design.md "feedbackPurge"), with the controlled clock: comments
 * older than 180 days go, younger ones stay, in batches of 1000 through the retention login, with one
 * {@code FEEDBACK_PURGED} by {@code system}; the application login cannot delete; the health indicator turns DOWN when
 * the last success is older than 48 hours.
 */
class FeedbackPurgeIT extends AbstractU8IT {

    @Autowired
    private FeedbackPurgeJob job;

    @Autowired
    private FeedbackPurgeHealthIndicator health;

    @Autowired
    private FeedbackRetentionDataSource retention;

    @Test
    void commentsOlderThanOneHundredEightyDaysAreDeletedAndYoungerOnesStay() {
        TestAccounts.Seeded guest = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        Instant now = clock.instant();
        UUID old = insert(guest, now.minus(Duration.ofDays(181)));
        UUID edge = insert(guest, now.minus(Duration.ofDays(180)).plusSeconds(1));
        UUID recent = insert(guest, now.minus(Duration.ofDays(1)));

        FeedbackPurgeJob.Round round = job.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.succeeded()).isTrue();
        assertThat(round.cutoff()).isEqualTo(now.minus(Duration.ofDays(180)));
        assertThat(exists(old)).isFalse();
        assertThat(exists(edge)).isTrue();
        assertThat(exists(recent)).isTrue();
        assertThat(round.deleted()).isPositive();
    }

    @Test
    void thePurgeWorksInBatchesOfOneThousand() {
        TestAccounts.Seeded guest = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        Timestamp old = Timestamp.from(clock.instant().minus(Duration.ofDays(200)));
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        select gen_random_uuid(), :tenant, :user, 'S1', 'lote ' || n, :at
                        from generate_series(1, 2100) as n
                        """)
                .param("tenant", guest.tenantId())
                .param("user", guest.userId())
                .param("at", old)
                .update();

        FeedbackPurgeJob.Round round = job.runOnce();

        assertThat(round.deleted()).isGreaterThanOrEqualTo(2100);
        assertThat(round.batches()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("select count(*) from feedback_entry where user_id = :user")
                        .param("user", guest.userId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** BR6.7, BR4.2: one FEEDBACK_PURGED by the system with the count, never a text nor a person. */
    @Test
    void eachRoundWritesOneEventOfTheSystemWithTheCount() {
        TestAccounts.Seeded guest = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        insert(guest, clock.instant().minus(Duration.ofDays(400)));
        long before = OutboxEvents.count(jdbc, FeedbackPurgeJob.FEEDBACK_PURGED, "feedback");

        job.runOnce();

        assertThat(OutboxEvents.count(jdbc, FeedbackPurgeJob.FEEDBACK_PURGED, "feedback"))
                .isEqualTo(before + 1);
        assertThat(OutboxEvents.payloads(jdbc, FeedbackPurgeJob.FEEDBACK_PURGED))
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"deleted\"")
                        .contains("system")
                        .doesNotContain(guest.userId().toString()));
    }

    /** NFR4.1: the application login never deletes a comment; only the retention pool does. */
    @Test
    void theApplicationLoginCannotDeleteAndTheRetentionPoolHasOneConnection() {
        TestAccounts.Seeded guest = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID old = insert(guest, clock.instant().minus(Duration.ofDays(365)));

        assertThatThrownBy(() -> jdbc.sql("delete from feedback_entry where feedback_id = :id")
                        .param("id", old)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(retention.maximumPoolSize()).isEqualTo(1);
        assertThat(exists(old)).isTrue();
    }

    /** observability-design.md: DOWN when the last success (or the start, before any) is older than 48 hours. */
    @Test
    void theHealthIsDownAfterFortyEightHoursWithoutASuccess() {
        Instant start = job.startedAt();
        clock.set(start.plus(Duration.ofHours(49)));
        if (job.lastSuccessAt().isEmpty()) {
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.health().getDetails()).containsEntry("code", "FEEDBACK_PURGE_STALE");
        }

        job.runOnce();

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        clock.advance(Duration.ofHours(48).plusSeconds(1));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void theHealthRuleUsesTheStartBeforeTheFirstSuccess() throws Exception {
        java.lang.reflect.Method evaluate = FeedbackPurgeHealthIndicator.class.getDeclaredMethod(
                "evaluate", java.util.Optional.class, Instant.class, Instant.class, Duration.class);
        evaluate.setAccessible(true);
        Instant start = Instant.parse("2026-11-01T00:00:00Z");

        Object fresh = evaluate.invoke(
                null, java.util.Optional.empty(), start, start.plus(Duration.ofHours(48)), Duration.ofHours(48));
        Object stale = evaluate.invoke(
                null, java.util.Optional.empty(), start, start.plus(Duration.ofHours(49)), Duration.ofHours(48));

        assertThat(((org.springframework.boot.actuate.health.Health) fresh).getStatus())
                .isEqualTo(Status.UP);
        assertThat(((org.springframework.boot.actuate.health.Health) stale).getStatus())
                .isEqualTo(Status.DOWN);
    }

    private UUID insert(TestAccounts.Seeded guest, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        values (:id, :tenant, :user, 'S4', 'Comentário antigo', :at)
                        """)
                .param("id", id)
                .param("tenant", guest.tenantId())
                .param("user", guest.userId())
                .param("at", Timestamp.from(at))
                .update();
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.sql("select count(*) from feedback_entry where feedback_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1;
    }
}
