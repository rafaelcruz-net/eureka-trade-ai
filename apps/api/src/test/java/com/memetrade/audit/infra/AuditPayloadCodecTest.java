package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class AuditPayloadCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void roundTripsAPersonEvent() {
        AuditEventCommand event = AuditEventCommand.person(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AuditActions.LOGOUT,
                "session:1",
                "corr-1",
                NOW,
                Map.of("k", "v"));

        assertThat(AuditPayloadCodec.decode(AuditPayloadCodec.encode(event))).isEqualTo(event);
    }

    @Test
    void roundTripsAnAnonymousEventWithoutTenantOrTarget() {
        AuditEventCommand event =
                AuditEventCommand.anonymous(AuditActions.LOGIN_REJECTED, "AbCd…WxYz", null, "corr-2", NOW, Map.of());

        String json = AuditPayloadCodec.encode(event);

        assertThat(json).contains("\"tenantId\":null").contains("\"actorType\":\"ANONYMOUS\"");
        assertThat(AuditPayloadCodec.decode(json)).isEqualTo(event);
        assertThat(AuditPayloadCodec.encodeDetails(event)).isEqualTo("{}");
    }

    @Test
    void corruptPayloadFailsLoudly() {
        assertThatThrownBy(() -> AuditPayloadCodec.decode("{not json")).isInstanceOf(IllegalStateException.class);
    }
}
