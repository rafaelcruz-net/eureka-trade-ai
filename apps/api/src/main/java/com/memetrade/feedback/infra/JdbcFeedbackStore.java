package com.memetrade.feedback.infra;

import com.memetrade.feedback.domain.FeedbackEntry;
import com.memetrade.feedback.domain.FeedbackScreen;
import com.memetrade.feedback.domain.FeedbackStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FeedbackStore} over {@code feedback_entry}. The lock of one person is a TRANSACTION advisory lock of the
 * feedback namespace keyed by the person ({@code pg_advisory_xact_lock(namespace, hashtext(user_id))}): it serialises
 * the submissions of that person until commit or rollback (NFR4.3) without reading or locking any table of the identity
 * module (team.md: a module never reads the tables of another). Two people whose hashes collide only wait for each
 * other; the count stays exact.
 */
@Repository
public class JdbcFeedbackStore implements FeedbackStore {

    /** Namespace of the person locks of the feedback ("U8FB"), the first key of the two-key advisory lock. */
    static final int LOCK_NAMESPACE = 0x55384642;

    private final JdbcClient jdbc;

    public JdbcFeedbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockPerson(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(cast(:namespace as integer), hashtext(cast(:user as text)))")
                .param("namespace", LOCK_NAMESPACE)
                .param("user", userId.toString())
                .query()
                .listOfRows();
    }

    @Override
    public List<Instant> createdSince(UUID userId, Instant since) {
        return jdbc.sql("""
                        select created_at from feedback_entry
                        where user_id = :user and created_at > :since
                        order by created_at
                        """)
                .param("user", userId)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> rs.getTimestamp("created_at").toInstant())
                .list();
    }

    @Override
    public void insert(FeedbackEntry entry) {
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        values (:id, :tenant, :user, :screen, :text, :at)
                        """)
                .param("id", entry.feedbackId())
                .param("tenant", entry.tenantId())
                .param("user", entry.userId())
                .param("screen", entry.screen().name())
                .param("text", entry.text())
                .param("at", Timestamp.from(entry.createdAt()))
                .update();
    }

    @Override
    public List<FeedbackEntry> page(Optional<PageKey> after, int limit) {
        String keyset = after.isPresent() ? " where (created_at, feedback_id) < (:afterAt, :afterId)" : "";
        var statement = jdbc.sql("select feedback_id, tenant_id, user_id, screen, text, created_at from feedback_entry"
                        + keyset
                        + " order by created_at desc, feedback_id desc limit :limit")
                .param("limit", limit);
        if (after.isPresent()) {
            statement = statement
                    .param("afterAt", Timestamp.from(after.get().createdAt()))
                    .param("afterId", after.get().feedbackId());
        }
        return statement
                .query((rs, n) -> new FeedbackEntry(
                        rs.getObject("feedback_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        FeedbackScreen.valueOf(rs.getString("screen")),
                        rs.getString("text"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
