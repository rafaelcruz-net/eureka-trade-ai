package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.testsupport.TestWallet;
import com.memetrade.testsupport.TestWalletHmac;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The outbox payload (U1); u2-retention adds the top-level {@code walletHmac} and never the wallet (BR2.1, BR2.18). */
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

        DecodedAuditEvent decoded = AuditPayloadCodec.decode(AuditPayloadCodec.encode(event));

        assertThat(decoded.command()).isEqualTo(event);
        assertThat(decoded.walletHmac()).isEmpty();
    }

    @Test
    void roundTripsAnAnonymousEventWithoutTenantOrTarget() {
        AuditEventCommand event =
                AuditEventCommand.anonymous(AuditActions.LOGIN_REJECTED, "AbCd…WxYz", null, "corr-2", NOW, Map.of());

        String json = AuditPayloadCodec.encode(event);

        assertThat(json).contains("\"tenantId\":null").contains("\"actorType\":\"ANONYMOUS\"");
        assertThat(json).doesNotContain("walletHmac");
        assertThat(AuditPayloadCodec.decode(json).command()).isEqualTo(event);
        assertThat(AuditPayloadCodec.encodeDetails(event)).isEqualTo("{}");
    }

    @Test
    void theWalletIsNeverWrittenOnlyItsHmacAsATopLevelField() {
        String address = TestWallet.generate().address();
        String hmac = TestWalletHmac.of(address);
        AuditEventCommand event = AuditEventCommand.anonymousWallet(
                AuditActions.LOGIN_REJECTED, address, null, "corr-3", NOW, Map.of("reason", "EXPIRED"));

        String json = AuditPayloadCodec.encode(event, Optional.of(hmac));
        DecodedAuditEvent decoded = AuditPayloadCodec.decode(json);

        assertThat(json).doesNotContain(address).contains("\"walletHmac\":\"" + hmac + "\"");
        assertThat(decoded.walletHmac()).contains(hmac);
        assertThat(decoded.command().wallet()).isNull();
        assertThat(decoded.command().actor()).isEqualTo(event.actor());
        assertThat(decoded.command().details()).isEqualTo(Map.of("reason", "EXPIRED"));
    }

    @Test
    void anOlderPayloadWithoutTheFieldOrWithANullOneDecodesWithoutHmac() {
        String older = """
                {"eventId":"%s","tenantId":null,"actor":"system","actorType":"SYSTEM","action":"OLD_ACTION",
                 "targetRef":null,"correlationId":"c","occurredAt":"2026-10-01T00:00:00Z","details":{},
                 "unknownField":1}
                """.formatted(UUID.randomUUID());
        String nullHmac = older.replace("\"unknownField\":1", "\"walletHmac\":null");

        assertThat(AuditPayloadCodec.decode(older).walletHmac()).isEmpty();
        assertThat(AuditPayloadCodec.decode(nullHmac).walletHmac()).isEmpty();
    }

    @Test
    void corruptPayloadFailsLoudly() {
        assertThatThrownBy(() -> AuditPayloadCodec.decode("{not json")).isInstanceOf(IllegalStateException.class);
    }
}
