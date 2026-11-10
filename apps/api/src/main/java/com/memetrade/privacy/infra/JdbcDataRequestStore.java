package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.DataRequestOrigin;
import com.memetrade.privacy.domain.request.DataRequestPurgeStore;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.privacy.domain.request.OpenRequestConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The requests of the Privacy module over {@code data_subject_request} and {@code data_subject_verification}
 * (migration 0014), through the main pool. Every read of a person filters by tenant AND person (NFR2.1); the open
 * check, the list and the window of the limit use {@code data_subject_request_person_idx}, the administrator list and
 * the overdue pass {@code data_subject_request_status_due_idx}, the purge {@code data_subject_request_closed_idx}
 * (NFR3.5). The conditional updates of the timers lock the request rows first (as a transition does), skip the rows
 * another transaction holds and return only what they changed, so an event is never published twice (NFR3.4, NFR3.7).
 */
@Repository
public class JdbcDataRequestStore implements DataRequestStore, DataRequestPurgeStore {

    static final String OPEN_INDEX = "uq_open_request_per_type";

    private static final String COLUMNS = """
            request_id, tenant_id, user_id, type, origin, details, contact, status, received_at, due_at, response,
            refusal_reason, closed_at, identity_verified, access_summary::text as access_summary, overdue_notified_at,
            reply_sent_at""";

    private static final String OPEN = "('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')";

    private final JdbcClient jdbc;

    public JdbcDataRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DataSubjectRequest request) {
        try {
            jdbc.sql("""
                            insert into data_subject_request
                              (request_id, tenant_id, user_id, type, origin, details, contact, status, received_at,
                               due_at, identity_verified)
                            values (:id, :tenant, :user, :type, :origin, :details, :contact, :status, :receivedAt,
                                    :dueAt, :verified)
                            """)
                    .param("id", request.requestId())
                    .param("tenant", request.tenantId().orElse(null))
                    .param("user", request.userId().orElse(null))
                    .param("type", request.type().name())
                    .param("origin", request.origin().name())
                    .param("details", request.details())
                    .param("contact", request.contact().orElse(null))
                    .param("status", request.status().name())
                    .param("receivedAt", Timestamp.from(request.receivedAt()))
                    .param("dueAt", Timestamp.from(request.dueAt()))
                    .param("verified", request.identityVerified())
                    .update();
        } catch (DuplicateKeyException duplicate) {
            if (String.valueOf(duplicate.getMessage()).contains(OPEN_INDEX)) {
                throw new OpenRequestConflictException(duplicate);
            }
            throw duplicate;
        }
    }

    @Override
    public boolean existsOpen(UUID tenantId, UUID userId, DataRequestType type) {
        Boolean exists = jdbc.sql("select exists (select 1 from data_subject_request where tenant_id = :tenant"
                        + " and user_id = :user and type = :type and status in " + OPEN + ")")
                .param("tenant", tenantId)
                .param("user", userId)
                .param("type", type.name())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<DataSubjectRequest> findOwned(UUID tenantId, UUID userId, UUID requestId) {
        return jdbc.sql("select " + COLUMNS + " from data_subject_request"
                        + " where request_id = :id and tenant_id = :tenant and user_id = :user")
                .param("id", requestId)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcDataRequestStore::map)
                .optional();
    }

    @Override
    public List<DataSubjectRequest> listOwned(UUID tenantId, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from data_subject_request where tenant_id = :tenant and user_id = :user"
                        + " order by received_at desc, request_id desc")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcDataRequestStore::map)
                .list();
    }

    @Override
    public Optional<DataSubjectRequest> findById(UUID requestId) {
        return jdbc.sql("select " + COLUMNS + " from data_subject_request where request_id = :id")
                .param("id", requestId)
                .query(JdbcDataRequestStore::map)
                .optional();
    }

    @Override
    public Optional<DataSubjectRequest> lockById(UUID requestId) {
        return jdbc.sql("select " + COLUMNS + " from data_subject_request where request_id = :id for update")
                .param("id", requestId)
                .query(JdbcDataRequestStore::map)
                .optional();
    }

    @Override
    public void update(DataSubjectRequest request) {
        int rows = jdbc.sql("""
                        update data_subject_request set
                            status = :status, response = :response, refusal_reason = :reason, closed_at = :closedAt,
                            identity_verified = :verified, access_summary = cast(:summary as jsonb),
                            overdue_notified_at = :notifiedAt, reply_sent_at = :sentAt
                        where request_id = :id
                        """)
                .param("status", request.status().name())
                .param("response", request.response().orElse(null))
                .param("reason", request.refusalReason().orElse(null))
                .param("closedAt", timestamp(request.closedAt()))
                .param("verified", request.identityVerified())
                .param(
                        "summary",
                        request.accessSummary().map(AccessSummaryJson::write).orElse(null))
                .param("notifiedAt", timestamp(request.overdueNotifiedAt()))
                .param("sentAt", timestamp(request.replySentAt()))
                .param("id", request.requestId())
                .update();
        if (rows != 1) {
            throw new IllegalStateException("The request to update does not exist: " + request.requestId());
        }
    }

    @Override
    public List<DataSubjectRequest> page(Optional<DataRequestStatus> status, Optional<PageKey> after, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from data_subject_request where true");
        status.ifPresent(any -> sql.append(" and status = :status"));
        after.ifPresent(any -> sql.append(" and (due_at, request_id) > (:afterDue, :afterId)"));
        sql.append(" order by due_at, request_id limit :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (status.isPresent()) {
            statement = statement.param("status", status.get().name());
        }
        if (after.isPresent()) {
            statement = statement
                    .param("afterDue", Timestamp.from(after.get().dueAt()))
                    .param("afterId", after.get().requestId());
        }
        return statement.query(JdbcDataRequestStore::map).list();
    }

    @Override
    public List<DataSubjectRequest> refuseExpiredVerifications(Instant now, String reason, int limit) {
        return jdbc.sql("""
                        with due as (
                            select r.request_id from data_subject_request r
                            join data_subject_verification v on v.request_id = r.request_id
                            where v.state = 'PENDING' and v.expires_at <= :now
                              and r.status = 'AWAITING_VERIFICATION'
                            order by v.expires_at, r.request_id
                            limit :limit
                            for update of r skip locked
                        ), expired as (
                            update data_subject_verification v set state = 'EXPIRED'
                            from due where v.request_id = due.request_id and v.state = 'PENDING'
                            returning v.request_id
                        )
                        update data_subject_request r set status = 'REFUSED', refusal_reason = :reason,
                            closed_at = :now
                        from expired e
                        where r.request_id = e.request_id and r.status = 'AWAITING_VERIFICATION'
                        returning\s""" + prefixed("r"))
                .param("now", Timestamp.from(now))
                .param("reason", reason)
                .param("limit", limit)
                .query(JdbcDataRequestStore::map)
                .list();
    }

    @Override
    public List<DataSubjectRequest> markOverdue(Instant now, int limit) {
        return jdbc.sql("""
                        with due as (
                            select request_id from data_subject_request
                            where status in ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')
                              and due_at <= :now and overdue_notified_at is null
                            order by due_at, request_id
                            limit :limit
                            for update skip locked
                        )
                        update data_subject_request r set overdue_notified_at = :now
                        from due
                        where r.request_id = due.request_id and r.overdue_notified_at is null
                        returning\s""" + prefixed("r"))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(JdbcDataRequestStore::map)
                .list();
    }

    @Override
    public PurgeBatch deleteClosedUpTo(Instant cutoff, int limit) {
        List<UUID> doomed = jdbc.sql("""
                        select request_id from data_subject_request
                        where closed_at is not null and closed_at <= :cutoff
                        order by closed_at, request_id
                        limit :limit
                        for update skip locked
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query((rs, n) -> rs.getObject("request_id", UUID.class))
                .list();
        if (doomed.isEmpty()) {
            return new PurgeBatch(0, 0);
        }
        int verifications = jdbc.sql("delete from data_subject_verification where request_id in (:ids)")
                .param("ids", doomed)
                .update();
        int requests = jdbc.sql("delete from data_subject_request where request_id in (:ids)")
                .param("ids", doomed)
                .update();
        return new PurgeBatch(requests, verifications);
    }

    /** The column list of a RETURNING clause, qualified by {@code alias}. */
    private static String prefixed(String alias) {
        return COLUMNS.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim)
                .filter(column -> !column.isEmpty())
                .map(column -> alias + "." + column)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    static DataSubjectRequest map(ResultSet rs, int row) throws SQLException {
        String summary = rs.getString("access_summary");
        return new DataSubjectRequest(
                rs.getObject("request_id", UUID.class),
                Optional.ofNullable(rs.getObject("tenant_id", UUID.class)),
                Optional.ofNullable(rs.getObject("user_id", UUID.class)),
                DataRequestType.valueOf(rs.getString("type")),
                DataRequestOrigin.valueOf(rs.getString("origin")),
                rs.getString("details"),
                Optional.ofNullable(rs.getString("contact")),
                DataRequestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(),
                Optional.ofNullable(rs.getString("response")),
                Optional.ofNullable(rs.getString("refusal_reason")),
                instant(rs.getTimestamp("closed_at")),
                rs.getBoolean("identity_verified"),
                Optional.ofNullable(summary).map(AccessSummaryJson::read),
                instant(rs.getTimestamp("overdue_notified_at")),
                instant(rs.getTimestamp("reply_sent_at")));
    }

    private static Optional<Instant> instant(Timestamp value) {
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }

    private static Timestamp timestamp(Optional<Instant> value) {
        return value.map(Timestamp::from).orElse(null);
    }
}
