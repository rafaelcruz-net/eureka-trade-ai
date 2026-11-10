package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.DataRequestRateLimit;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link DataRequestRateLimit} (NFR2.4): at most {@code memetrade.privacy.requests-per-hour} requests of a person
 * (ACCOUNT origin) in the last hour. A TRANSACTION advisory lock of the Privacy namespace keyed by the person
 * serialises the creations of that person until commit or rollback, so the count and the insert are atomic across
 * instances; the count reads {@code data_subject_request_person_idx}. The 429 answers how long until the oldest request
 * of the window leaves it.
 */
@Component
public class JdbcDataRequestRateLimit implements DataRequestRateLimit {

    /** Namespace of the person locks of the data subject requests ("U4DR"), the first key of the advisory lock. */
    static final int LOCK_NAMESPACE = 0x55344452;

    static final Duration WINDOW = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final int maxPerHour;

    public JdbcDataRequestRateLimit(JdbcClient jdbc, PrivacyProperties properties) {
        this.jdbc = jdbc;
        this.maxPerHour = properties.requestsPerHour();
    }

    @Override
    public Optional<Duration> refuse(UUID tenantId, UUID userId, Instant now) {
        jdbc.sql("select pg_advisory_xact_lock(cast(:namespace as integer), hashtext(cast(:user as text)))")
                .param("namespace", LOCK_NAMESPACE)
                .param("user", userId.toString())
                .query()
                .listOfRows();
        Map<String, Object> window = jdbc.sql("""
                        select count(*) as total, min(received_at) as oldest from data_subject_request
                        where tenant_id = :tenant and user_id = :user and origin = 'ACCOUNT'
                          and received_at > :since
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("since", Timestamp.from(now.minus(WINDOW)))
                .query()
                .singleRow();
        long total = ((Number) window.get("total")).longValue();
        if (total < maxPerHour) {
            return Optional.empty();
        }
        Instant oldest = ((Timestamp) window.get("oldest")).toInstant();
        Duration wait = Duration.between(now, oldest.plus(WINDOW));
        return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
    }
}
