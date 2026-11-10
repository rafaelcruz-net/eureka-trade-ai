package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.audit.infra.AuditProperties;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AuditOutboxIT extends AbstractPostgresIT {

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private com.memetrade.audit.infra.AuditDeliveryMetrics deliveryMetrics;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void aRolledBackActionLeavesNoEvent() {
        AuditEventCommand event = rejected("ROLLBACK");

        tx.executeWithoutResult(status -> {
            publisher.publish(event);
            status.setRollbackOnly();
        });

        assertThat(outboxCount(event.eventId())).isZero();
    }

    @Test
    void publishingOutsideATransactionFails() {
        assertThatThrownBy(() -> publisher.publish(rejected("NO_TX")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theRelayDeliversEachEventExactlyOnce() {
        AuditEventCommand event = rejected("ONCE");
        tx.executeWithoutResult(status -> publisher.publish(event));

        relay.relayBatch();
        relay.relayBatch();

        assertThat(trailCount(event.eventId())).isEqualTo(1);
        assertThat(jdbc.sql("select delivered_at is not null from audit_outbox where event_id = :id")
                        .param("id", event.eventId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void aRepeatedEventIdIsDiscardedWithoutError() {
        AuditEventCommand event = rejected("DUPLICATE");
        jdbc.sql("""
                        insert into audit_event (event_id, actor, actor_type, action, correlation_id, occurred_at)
                        values (:id, 'x', 'ANONYMOUS', 'LOGIN_REJECTED', 'c', now())
                        """).param("id", event.eventId()).update();
        tx.executeWithoutResult(status -> publisher.publish(event));

        relay.relayBatch();

        assertThat(trailCount(event.eventId())).isEqualTo(1);
        assertThat(outboxPending(event.eventId())).isFalse();
    }

    @Test
    void pendingEventsSurviveAStopAndAreDeliveredWhenTheRelayComesBack() {
        AuditEventCommand event = rejected("RESTART");
        tx.executeWithoutResult(status -> publisher.publish(event));
        assertThat(outboxPending(event.eventId()))
                .as("committed but not yet relayed")
                .isTrue();

        AuditOutboxRelay restarted = new AuditOutboxRelay(
                jdbc,
                clock,
                AuditProperties.relayOnly(Duration.ofSeconds(1), 100, Duration.ofSeconds(60)),
                deliveryMetrics);
        tx.executeWithoutResult(status -> restarted.relayBatch());

        assertThat(trailCount(event.eventId())).isEqualTo(1);
    }

    /** u2-retention (BR2.1, BR2.18): the trail keeps the masked actor and the HMAC, never the full address. */
    @Test
    void preLoginEventsHaveAnonymousActorNoTenantAndOnlyTheHmacOfTheWallet() {
        String address = SyntheticIds.mint();
        AuditEventCommand event = AuditEventCommand.anonymousWallet(
                AuditActions.LOGIN_REJECTED,
                address,
                null,
                UUID.randomUUID().toString(),
                clock.instant(),
                Map.of("reason", "EXPIRED"));
        tx.executeWithoutResult(status -> publisher.publish(event));
        tx.executeWithoutResult(status -> {
            while (relay.relayBatch() > 0) {
                // deliver every pending row, this event included
            }
        });

        Map<String, Object> row =
                jdbc.sql("""
                        select tenant_id, actor, actor_type, details::text as details, wallet_hmac
                        from audit_event where event_id = :id
                        """).param("id", event.eventId()).query().singleRow();
        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("actor_type")).isEqualTo("ANONYMOUS");
        assertThat(row.get("actor")).isEqualTo(WalletAddress.mask(address));
        assertThat((String) row.get("details")).doesNotContain(address).contains("EXPIRED");
        assertThat(row.get("wallet_hmac")).isEqualTo(com.memetrade.testsupport.TestWalletHmac.of(address));
    }

    @Test
    void theStoredPayloadHasNoNonceSignatureOrToken() {
        AuditEventCommand event = rejected("PAYLOAD");
        tx.executeWithoutResult(status -> publisher.publish(event));

        String payload = jdbc.sql("select payload::text from audit_outbox where event_id = :id")
                .param("id", event.eventId())
                .query(String.class)
                .single();

        assertThat(payload.toLowerCase())
                .doesNotContain("nonce")
                .doesNotContain("signature")
                .doesNotContain("accesstoken")
                .doesNotContain("refreshtoken")
                .doesNotContain("authorization");
    }

    @Test
    void lagReportsPendingCountAndOldestAge() {
        AuditEventCommand event = rejected("LAG");
        tx.executeWithoutResult(status -> publisher.publish(event));
        clock.advance(Duration.ofSeconds(30));

        AuditOutboxRelay.OutboxLag lag = relay.lag();

        assertThat(lag.pending()).isPositive();
        assertThat(lag.oldestAge()).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
        relay.relayBatch();
    }

    private AuditEventCommand rejected(String reason) {
        return AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED,
                "AbCd…WxYz",
                null,
                UUID.randomUUID().toString(),
                clock.instant(),
                Map.of("reason", reason));
    }

    private int outboxCount(UUID id) {
        return jdbc.sql("select count(*) from audit_outbox where event_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private boolean outboxPending(UUID id) {
        return jdbc.sql("select delivered_at is null from audit_outbox where event_id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private int trailCount(UUID id) {
        return jdbc.sql("select count(*) from audit_event where event_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }
}
