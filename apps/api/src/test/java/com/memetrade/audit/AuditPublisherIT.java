package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** C9 with the U2 checks: exactly one event (BR1.1), refusals before writing (BR1.2, BR1.4), tenant from C10. */
@Tag("u2")
@ExtendWith(OutputCaptureExtension.class)
class AuditPublisherIT extends AbstractPostgresIT {

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void anActionThatRollsBackLeavesNeitherOutboxRowNorTrailEvent() {
        AuditEventCommand event = event(Map.of("reason", "ROLLBACK"));

        tx.executeWithoutResult(status -> {
            publisher.publish(event);
            status.setRollbackOnly();
        });
        relay.relayBatch();

        assertThat(outboxCount(event.eventId())).isZero();
        assertThat(trailCount(event.eventId())).isZero();
    }

    @Test
    void theSameEventIdPublishedTwiceGivesOneOutboxRowAndOneEvent() {
        AuditEventCommand event = event(Map.of("reason", "RETRY"));

        tx.executeWithoutResult(status -> publisher.publish(event));
        tx.executeWithoutResult(status -> publisher.publish(event));
        relay.relayBatch();
        relay.relayBatch();

        assertThat(outboxCount(event.eventId())).isEqualTo(1);
        assertThat(trailCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void publishingOutsideATransactionIsRefusedAndCounted() {
        double before = rejected("NO_TRANSACTION");
        AuditEventCommand event = event(Map.of());

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxCount(event.eventId())).isZero();
        assertThat(rejected("NO_TRANSACTION")).isEqualTo(before + 1);
    }

    @Test
    void aForbiddenValueRefusesTheEventAndRollsTheWholeActionBack() {
        double before = rejected("FORBIDDEN_VALUE");
        UUID tenant = UUID.randomUUID();
        AuditEventCommand event = event(Map.of("note", jwt()));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    jdbc.sql("insert into tenant (tenant_id, name, status, created_at)"
                                    + " values (:id, 'action', 'ACTIVE', now())")
                            .param("id", tenant)
                            .update();
                    publisher.publish(event);
                }))
                .isInstanceOf(AuditPublishRejectedException.class);

        assertThat(jdbc.sql("select count(*) from tenant where tenant_id = :id")
                        .param("id", tenant)
                        .query(Long.class)
                        .single())
                .as("the audited action rolled back with its event")
                .isZero();
        assertThat(outboxCount(event.eventId())).isZero();
        assertThat(rejected("FORBIDDEN_VALUE")).isEqualTo(before + 1);
    }

    @Test
    void anInvalidActionIsRefusedAndTheLogNamesFieldAndReasonButNotTheValue(CapturedOutput output) {
        String token = jwt();
        AuditEventCommand badAction = AuditEventCommand.anonymous(
                "lower_case", "anonymous", null, UUID.randomUUID().toString(), clock.instant(), Map.of());
        AuditEventCommand badValue = event(Map.of("note", token));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publish(badAction)))
                .isInstanceOf(AuditPublishRejectedException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publish(badValue)))
                .isInstanceOf(AuditPublishRejectedException.class);

        assertThat(output.getAll())
                .contains("Audit event rejected by the publisher")
                .contains("reason=INVALID_FORMAT")
                .contains("field=details.note")
                .contains("action=<invalid>")
                .doesNotContain(token);
    }

    @Test
    void thePublishedCounterCountsOnlyCommittedEvents() {
        String action = "TEST_COUNTED_" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        AuditEventCommand committed =
                AuditEventCommand.system(action, null, UUID.randomUUID().toString(), clock.instant(), Map.of());
        AuditEventCommand rolledBack =
                AuditEventCommand.system(action, null, UUID.randomUUID().toString(), clock.instant(), Map.of());

        tx.executeWithoutResult(status -> publisher.publish(committed));
        tx.executeWithoutResult(status -> {
            publisher.publish(rolledBack);
            status.setRollbackOnly();
        });

        assertThat(registry.get("audit_events_published")
                        .tag("action", action)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        relay.relayBatch();
    }

    @Test
    void theTenantOfTheEventComesFromThePrincipalNeverFromTheRequest() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded account = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));
        UUID otherTenant = UUID.randomUUID();

        MockHttpServletResponse refused = mvc.perform(
                        get("/api/v1/scanner/tokens?mode=REAL&tenantId={tenant}", otherTenant)
                                .header("X-Tenant-Id", otherTenant.toString())
                                .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();

        assertThat(refused.getStatus()).isEqualTo(403);
        String correlationId = refused.getHeader("X-Correlation-Id");
        String payloadTenant = jdbc.sql("""
                        select payload->>'tenantId' from audit_outbox
                        where payload->>'correlationId' = :c and payload->>'action' = :a
                        """)
                .param("c", correlationId)
                .param("a", AuditActions.REQUEST_FORBIDDEN)
                .query(String.class)
                .single();
        assertThat(payloadTenant).isEqualTo(account.tenantId().toString()).isNotEqualTo(otherTenant.toString());
        relay.relayBatch();
    }

    private AuditEventCommand event(Map<String, String> details) {
        return AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED,
                "AbCd…WxYz",
                null,
                UUID.randomUUID().toString(),
                clock.instant(),
                details);
    }

    private double rejected(String reason) {
        return registry.get("audit_publish_rejected")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private long outboxCount(UUID id) {
        return jdbc.sql("select count(*) from audit_outbox where event_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long trailCount(UUID id) {
        return jdbc.sql("select count(*) from audit_event where event_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private static String jwt() {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        return url.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + url.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
    }
}
