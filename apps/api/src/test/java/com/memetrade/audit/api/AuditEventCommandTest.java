package com.memetrade.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class AuditEventCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void anonymousEventsHaveNoTenantAndAnAnonymousActor() {
        AuditEventCommand event = AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED, "AbCd…WxYz", null, "corr", NOW, Map.of("reason", "EXPIRED"));

        assertThat(event.tenantId()).isEmpty();
        assertThat(event.actorType()).isEqualTo(ActorType.ANONYMOUS);
        assertThat(event.actor()).isEqualTo("AbCd…WxYz");
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    void personEventsCarryTheTenantAndTheUserAsActor() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        AuditEventCommand event =
                AuditEventCommand.person(tenant, user, AuditActions.LOGOUT, "session:1", "corr", NOW, Map.of());

        assertThat(event.tenantId()).contains(tenant);
        assertThat(event.actor()).isEqualTo(user.toString());
        assertThat(event.actorType()).isEqualTo(ActorType.PERSON);
    }

    @Test
    void secretsTokensNoncesAndSignaturesAreRefusedAsDetailKeys() {
        for (String key :
                new String[] {"nonce", "signature", "accessToken", "refreshToken", "Authorization", "message"}) {
            assertThatThrownBy(() -> AuditEventCommand.anonymous(
                            AuditActions.LOGIN_REJECTED, "actor", null, "corr", NOW, Map.of(key, "value")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAnonymousEventCannotCarryATenant() {
        assertThatThrownBy(() -> new AuditEventCommand(
                        UUID.randomUUID(),
                        Optional.of(UUID.randomUUID()),
                        "actor",
                        ActorType.ANONYMOUS,
                        AuditActions.LOGIN_REJECTED,
                        null,
                        "corr",
                        NOW,
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredFieldsAreEnforcedAndDetailsAreImmutableCopies() {
        assertThatThrownBy(() -> AuditEventCommand.anonymous("", "actor", null, "corr", NOW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEventCommand.anonymous("ACTION", "actor", null, " ", NOW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> details = new HashMap<>(Map.of("reason", "EXPIRED"));
        AuditEventCommand event = AuditEventCommand.anonymous("ACTION", "actor", null, "corr", NOW, details);
        details.put("reason", "CHANGED");

        assertThat(event.details()).containsEntry("reason", "EXPIRED");
        assertThatThrownBy(() -> event.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
