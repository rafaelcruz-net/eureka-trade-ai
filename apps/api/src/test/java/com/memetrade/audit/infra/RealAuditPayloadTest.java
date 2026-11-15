package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Step 14, written before the code (BR6.2): the typed REAL fields travel in the outbox payload and back. */
class RealAuditPayloadTest {

    @Test
    void theTypedFieldsRoundTripThroughTheOutboxPayload() {
        RealReferences real =
                new RealReferences(null, null, TestWallet.generate().address());
        AuditEventCommand command = AuditEventCommand.person(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REAL_BOT_WALLET_CREATED",
                        null,
                        "corr",
                        Instant.parse("2026-11-14T12:00:00Z"),
                        Map.of())
                .withReal(real);

        DecodedAuditEvent decoded = AuditPayloadCodec.decode(AuditPayloadCodec.encode(command, Optional.empty()));

        assertThat(decoded.command().real()).isEqualTo(real);
        assertThat(decoded.command().details()).isEmpty();
    }

    @Test
    void anEventWithoutTypedFieldsDecodesWithoutThem() {
        AuditEventCommand command =
                AuditEventCommand.system("AUDIT_JOB", null, "corr", Instant.parse("2026-11-14T12:00:00Z"), Map.of());

        assertThat(AuditPayloadCodec.decode(AuditPayloadCodec.encode(command))
                        .command()
                        .real())
                .isNull();
    }
}
