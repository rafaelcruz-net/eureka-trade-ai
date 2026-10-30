package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.AdminSessions.AdminSession;
import com.memetrade.testsupport.SyntheticIds;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Administrator query of the trail through HTTP (F3, BR2.1 to BR2.5, NFR6.7, NFR7.5), with the TEST-ONLY
 * administrator session of {@link AdminSessions}. Each test writes its own events in a period of its own in 2023,
 * away from the periods of other tests, and reads only that period. The negative 401 and guest-403 cases live in
 * {@link AdminAuditRouteIT}.
 */
@Tag("u2")
class AdminAuditQueryIT extends AbstractPostgresIT {

    private static final String ROUTE = "/api/v1/admin/audit-events";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AdminSessions adminSessions;

    private AdminSession admin;
    private Instant base;

    @BeforeEach
    void setUp(TestInfo test) {
        admin = adminSessions.openAdminSession();
        // Deterministic period of each test method (no random seed): a day and a second derived from its name.
        int hash = Math.abs(test.getDisplayName().hashCode() % 1_000_000);
        base = Instant.parse("2023-01-01T00:00:00Z")
                .plus(Duration.ofDays(hash % 300))
                .plusSeconds((hash / 300) * 61L % 80_000);
    }

    @Test
    void anAdministratorGets200WithTheEventsMostRecentFirstAndACursor() throws Exception {
        UUID oldest = insert(base, null, "QUERY_IT_ACTION", "{}");
        UUID middle = insert(base.plusSeconds(1), UUID.randomUUID(), "QUERY_IT_ACTION", "{}");
        UUID newest = insert(base.plusSeconds(2), UUID.randomUUID(), "QUERY_IT_ACTION", "{}");

        MockHttpServletResponse first = query(period() + "&limit=2");
        JsonNode page = json.readTree(first.getContentAsString(StandardCharsets.UTF_8));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(ids(page)).containsExactly(newest, middle);
        assertThat(page.get("nextCursor").isTextual()).isTrue();

        JsonNode second = json.readTree(
                query(period() + "&limit=2&cursor=" + page.get("nextCursor").asText())
                        .getContentAsString(StandardCharsets.UTF_8));
        assertThat(ids(second)).containsExactly(oldest);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void theResponseCarriesEveryTenantTheEventsWithoutTenantAndTheFullAddressInDetails() throws Exception {
        String address = SyntheticIds.mint();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insert(base, tenantA, "QUERY_IT_ACTION", "{\"walletAddress\":\"" + address + "\"}");
        insert(base.plusSeconds(1), tenantB, "QUERY_IT_ACTION", "{}");
        insert(base.plusSeconds(2), null, "QUERY_IT_ACTION", "{}");

        JsonNode page = json.readTree(query(period()).getContentAsString(StandardCharsets.UTF_8));

        List<String> tenants = new ArrayList<>();
        page.get("items")
                .forEach(item -> tenants.add(
                        item.get("tenantId").isNull()
                                ? "null"
                                : item.get("tenantId").asText()));
        assertThat(tenants).containsExactly("null", tenantB.toString(), tenantA.toString());
        assertThat(page.get("items").get(2).get("details").get("walletAddress").asText())
                .isEqualTo(address);
        assertThat(page.get("items").get(0).get("recordedAt").isTextual()).isTrue();
    }

    @Test
    void theAnswerIsNeverCached() throws Exception {
        MockHttpServletResponse response = query(period());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void everyAnsweredQueryPublishesOneAuditQueriedWithTheFiltersAndNoItems() throws Exception {
        String address = SyntheticIds.mint();
        insert(base, UUID.randomUUID(), "QUERY_IT_ACTION", "{\"walletAddress\":\"" + address + "\"}");

        MockHttpServletResponse response = query(period() + "&action=QUERY_IT_ACTION");
        assertThat(response.getStatus()).isEqualTo(200);

        List<JsonNode> queried = outboxEvents(response.getHeader("X-Correlation-Id"));
        assertThat(queried).hasSize(1);
        JsonNode event = queried.getFirst();
        assertThat(event.get("action").asText()).isEqualTo("AUDIT_QUERIED");
        assertThat(event.get("actor").asText()).isEqualTo(admin.adminId().toString());
        assertThat(event.get("actorType").asText()).isEqualTo("ADMIN");
        assertThat(event.get("tenantId").isNull()).isTrue();
        assertThat(event.get("targetRef").asText()).isEqualTo("GET /api/v1/admin/audit-events");
        JsonNode details = event.get("details");
        assertThat(details.get("from").asText()).isEqualTo(base.minusSeconds(1).toString());
        assertThat(details.get("to").asText()).isEqualTo(base.plusSeconds(60).toString());
        assertThat(details.get("action").asText()).isEqualTo("QUERY_IT_ACTION");
        assertThat(details.size()).isEqualTo(3);
        assertThat(event.toString()).doesNotContain(address).doesNotContain("items");
    }

    @Test
    void invalidParametersAre400WithTheListOfFieldsAndRecordNoQuery() throws Exception {
        String from = base.toString();
        String to = base.plus(Duration.ofDays(32)).toString();

        MockHttpServletResponse response = query("?from=" + from + "&to=" + to + "&limit=101&tenantId=x");
        JsonNode problem = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        problem.get("fields").forEach(field -> fields.add(field.get("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("tenantId", "to", "limit");
        assertThat(outboxEvents(response.getHeader("X-Correlation-Id"))).isEmpty();
    }

    @Test
    void aMissingPeriodIs400() throws Exception {
        MockHttpServletResponse response = query("");
        JsonNode problem = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        assertThat(response.getStatus()).isEqualTo(400);
        List<String> fields = new ArrayList<>();
        problem.get("fields").forEach(field -> fields.add(field.get("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("from", "to");
    }

    @Test
    void whenAuditQueriedCannotBeRecordedTheAnswerIs500WithoutData() throws Exception {
        String address = SyntheticIds.mint();
        insert(base, UUID.randomUUID(), "QUERY_IT_ACTION", "{\"walletAddress\":\"" + address + "\"}");
        JdbcClient owner = connect(OWNER);
        owner.sql("revoke insert on audit_outbox from " + APP.user()).update();
        MockHttpServletResponse response;
        try {
            response = query(period());
        } finally {
            owner.sql("grant insert on audit_outbox to " + APP.user()).update();
        }
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body)
                .contains("INTERNAL_ERROR")
                .doesNotContain("\"items\"")
                .doesNotContain(address)
                .doesNotContain("QUERY_IT_ACTION")
                .doesNotContainIgnoringCase("audit_outbox");
        assertThat(outboxEvents(response.getHeader("X-Correlation-Id"))).isEmpty();
    }

    private String period() {
        return "?from=" + base.minusSeconds(1) + "&to=" + base.plusSeconds(60);
    }

    private MockHttpServletResponse query(String queryString) throws Exception {
        return mvc.perform(get(ROUTE + queryString).header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();
    }

    private List<JsonNode> outboxEvents(String correlationId) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String payload : jdbc.sql("select payload::text from audit_outbox where payload->>'correlationId' = :c")
                .param("c", correlationId)
                .query(String.class)
                .list()) {
            events.add(json.readTree(payload));
        }
        return events;
    }

    private UUID insert(Instant occurredAt, UUID tenant, String action, String detailsJson) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into audit_event (event_id, tenant_id, actor, actor_type, action, correlation_id,
                                                 occurred_at, recorded_at, details)
                        values (:id, :tenant, 'system', 'SYSTEM', :action, 'query-it', :at, :at,
                                cast(:details as jsonb))
                        """)
                .param("id", id)
                .param("tenant", tenant)
                .param("action", action)
                .param("at", Timestamp.from(occurredAt))
                .param("details", detailsJson)
                .update();
        return id;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("eventId").asText())));
        return ids;
    }
}
