package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.domain.AuditEventPage;
import com.memetrade.audit.domain.AuditEventQuery;
import com.memetrade.audit.domain.AuditEventRecord;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keyset reading of the trail (BR2.3, BR2.4, NFR3.1). Each test method owns a fixed one-day period in 2024 (derived
 * from its name, no random seed) and starts by removing whatever an earlier run left in that period, through the
 * retention login (the period is older than 12 months, so the trigger allows it). Regression of a flaky test: a
 * random period could fall on the period of another test and read its events.
 */
@Tag("u2")
class JdbcAuditEventReaderIT extends AbstractPostgresIT {

    @Autowired
    private JdbcAuditEventReader reader;

    @Autowired
    private TransactionTemplate tx;

    private Instant base;

    @BeforeEach
    void ownPeriod(TestInfo test) {
        base = Instant.parse("2024-01-01T00:00:00Z")
                .plus(Duration.ofDays(2L
                        * Math.floorMod(
                                test.getTestMethod().orElseThrow().getName().hashCode(), 150)));
        connect(RETENTION)
                .sql("delete from audit_event where occurred_at >= :from and occurred_at < :to")
                .param("from", Timestamp.from(base))
                .param("to", Timestamp.from(base.plus(Duration.ofDays(1))))
                .update();
    }

    @Test
    void eventsComeMostRecentFirstAndTiesAreBrokenByEventIdDescending() {
        List<UUID> tied = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tied.add(insert(base.plusSeconds(10), null, "TIE_ACTION", Map.of()));
        }
        UUID newest = insert(base.plusSeconds(20), null, "TIE_ACTION", Map.of());
        UUID oldest = insert(base, null, "TIE_ACTION", Map.of());

        List<UUID> read = ids(reader.read(query(Map.of()), 100));

        // PostgreSQL orders uuid byte by byte (unsigned), which is the order of the canonical text form.
        List<UUID> expectedTies = tied.stream()
                .sorted(Comparator.comparing(UUID::toString).reversed())
                .toList();
        assertThat(read).hasSize(6);
        assertThat(read.getFirst()).isEqualTo(newest);
        assertThat(read.subList(1, 5)).containsExactlyElementsOf(expectedTies);
        assertThat(read.getLast()).isEqualTo(oldest);
    }

    @Test
    void pagesFollowTheCursorWithoutGapsOrRepeatsEvenWithTies() {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            all.add(insert(base.plusSeconds(i / 3), null, "PAGE_ACTION", Map.of()));
        }
        List<UUID> expected = ids(reader.read(query(Map.of()), 100));

        List<UUID> paged = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, String> extra = new LinkedHashMap<>(Map.of("limit", "3"));
            if (cursor != null) {
                extra.put("cursor", cursor);
            }
            AuditEventQuery query = query(extra);
            AuditEventPage page = AuditEventPage.of(reader.read(query, query.rowsToRead()), query.limit());
            paged.addAll(ids(page.items()));
            cursor = page.nextCursor().map(c -> c.encode()).orElse(null);
        } while (cursor != null);

        assertThat(paged).containsExactlyElementsOf(expected).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void everyTenantAndTheEventsWithoutTenantAreReturned() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insert(base, tenantA, "TENANT_ACTION", Map.of());
        insert(base.plusSeconds(1), tenantB, "TENANT_ACTION", Map.of());
        insert(base.plusSeconds(2), null, "TENANT_ACTION", Map.of());

        List<AuditEventRecord> read = reader.read(query(Map.of()), 100);

        assertThat(read).extracting(r -> r.tenantId().orElse(null)).containsExactly(null, tenantB, tenantA);
    }

    @Test
    void theFullWalletAddressInDetailsIsReturnedAsRecorded() {
        String address = SyntheticIds.mint();
        insert(base, null, "DETAIL_ACTION", Map.of("walletAddress", address, "reason", "EXPIRED"));

        AuditEventRecord record = reader.read(query(Map.of()), 10).getFirst();

        assertThat(record.details()).containsEntry("walletAddress", address).containsEntry("reason", "EXPIRED");
        assertThat(record.recordedAt()).isPresent();
    }

    @Test
    void theActionFilterIsAnExactMatchAndThePeriodIsFromInclusiveToExclusive() {
        UUID atFrom = insert(base, null, "FILTER_ONE", Map.of());
        insert(base.plusSeconds(5), null, "FILTER_TWO", Map.of());
        insert(base.plus(Duration.ofHours(1)), null, "FILTER_ONE", Map.of());

        AuditEventQuery onlyOne = AuditEventQuery.fromParameters(Map.of(
                "from", List.of(base.toString()),
                "to", List.of(base.plus(Duration.ofHours(1)).toString()),
                "action", List.of("FILTER_ONE")));

        assertThat(ids(reader.read(onlyOne, 10))).containsExactly(atFrom);
    }

    @Test
    void bothPreparedFormsReadFromTheIndexWithoutASeparateSort() {
        insert(base, null, "PLAN_ACTION", Map.of());
        // With only a handful of rows in the window the planner may scan the time index and filter the action, which
        // depends on what other tests left in the shared database. Enough rows of another action in the same window,
        // with fresh statistics, make the action index the cheaper path every time.
        for (int i = 0; i < 300; i++) {
            insert(base.plusMillis(i + 1), null, "PLAN_NOISE", Map.of());
        }
        jdbc.sql("analyze audit_event").update();
        for (boolean withAction : new boolean[] {false, true}) {
            for (boolean withCursor : new boolean[] {false, true}) {
                String plan = explain(JdbcAuditEventReader.sql(withAction, withCursor));
                assertThat(plan)
                        .as("action=%s cursor=%s", withAction, withCursor)
                        .contains(withAction ? "audit_event_action_time_id_idx" : "audit_event_time_id_idx")
                        .doesNotContain("Sort");
            }
        }
    }

    /**
     * Plans the statement with literal values; enable_sort=off makes the planner prefer any plan that already
     * delivers the order, so a remaining Sort node means the index cannot provide it.
     */
    private String explain(String sql) {
        String literal = sql.replace(":from", "timestamptz '" + base + "'")
                .replace(":to", "timestamptz '" + base.plus(Duration.ofDays(1)) + "'")
                .replace(":action", "'PLAN_ACTION'")
                .replace(":cursorAt", "timestamptz '" + base.plusSeconds(30) + "'")
                .replace(":cursorId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":rows", "51");
        return tx.execute(status -> {
            jdbc.sql("set local enable_sort = off").update();
            jdbc.sql("set local enable_seqscan = off").update();
            jdbc.sql("set local enable_bitmapscan = off").update();
            return String.join(
                    "\n", jdbc.sql("explain " + literal).query(String.class).list());
        });
    }

    private AuditEventQuery query(Map<String, String> extra) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        parameters.put("from", List.of(base.toString()));
        parameters.put("to", List.of(base.plus(Duration.ofDays(1)).toString()));
        extra.forEach((k, v) -> parameters.put(k, List.of(v)));
        return AuditEventQuery.fromParameters(parameters);
    }

    private UUID insert(Instant occurredAt, UUID tenant, String action, Map<String, String> details) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into audit_event (event_id, tenant_id, actor, actor_type, action, correlation_id,
                                                 occurred_at, recorded_at, details)
                        values (:id, :tenant, 'system', 'SYSTEM', :action, 'corr', :at, :at, cast(:details as jsonb))
                        """)
                .param("id", id)
                .param("tenant", tenant)
                .param("action", action)
                .param("at", Timestamp.from(occurredAt))
                .param("details", toJson(details))
                .update();
        return id;
    }

    private static String toJson(Map<String, String> details) {
        StringBuilder json = new StringBuilder("{");
        details.forEach((k, v) -> json.append(json.length() > 1 ? "," : "")
                .append('"')
                .append(k)
                .append("\":\"")
                .append(v)
                .append('"'));
        return json.append('}').toString();
    }

    private static List<UUID> ids(List<AuditEventRecord> records) {
        return records.stream().map(AuditEventRecord::eventId).toList();
    }
}
