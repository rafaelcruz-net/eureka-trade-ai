package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.shared.config.ProcessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The cached counts of the gauges and of the health {@code dataRequests} (NFR8.1, NFR8.2): one aggregate query by type
 * of the open requests, of the overdue ones and of the ones due within {@code due-soon} (3 days), every 60 s in EACH
 * instance of the api (no ShedLock) and once when the api is ready, so no instance stays at zero after it starts. When
 * the query fails the previous values stay and the log carries the error class only.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class DataRequestCountsRefresher {

    private static final Logger log = LoggerFactory.getLogger(DataRequestCountsRefresher.class);

    private final JdbcClient jdbc;
    private final DataRequestMetrics metrics;
    private final PrivacyProperties settings;
    private final Clock clock;

    public DataRequestCountsRefresher(
            JdbcClient jdbc, DataRequestMetrics metrics, PrivacyProperties settings, Clock clock) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${memetrade.privacy.counts-refresh-interval:60s}",
            initialDelayString = "${memetrade.privacy.counts-refresh-interval:60s}")
    public void scheduledRefresh() {
        refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** One refresh; true when the cache was updated. */
    public boolean refresh() {
        Instant now = clock.instant();
        try {
            Map<DataRequestType, Long> open = new EnumMap<>(DataRequestType.class);
            long[] overdueAndSoon = new long[2];
            jdbc.sql("""
                            select type,
                                   count(*) as open,
                                   count(*) filter (where due_at <= :now) as overdue,
                                   count(*) filter (where due_at > :now and due_at <= :soon) as due_soon
                            from data_subject_request
                            where status in ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')
                            group by type
                            """)
                    .param("now", Timestamp.from(now))
                    .param("soon", Timestamp.from(now.plus(settings.dueSoon())))
                    .query(rs -> {
                        open.put(DataRequestType.valueOf(rs.getString("type")), rs.getLong("open"));
                        overdueAndSoon[0] += rs.getLong("overdue");
                        overdueAndSoon[1] += rs.getLong("due_soon");
                    });
            metrics.refreshed(new DataRequestMetrics.Counts(open, overdueAndSoon[0], overdueAndSoon[1]), now);
            return true;
        } catch (DataAccessException unavailable) {
            log.error(
                    "Data request counts could not be refreshed; the previous values stay: cause={}",
                    PrivacyJobRunner.technicalCause(unavailable));
            return false;
        }
    }
}
