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

    // ----- u2-retention (BR2.1, BR2.15, J6): the wallet field. -----

    @Test
    void anAnonymousWalletEventHasTheMaskedActorAndTheWalletInItsOwnField() {
        String address = com.memetrade.testsupport.TestWallet.generate().address();

        AuditEventCommand event = AuditEventCommand.anonymousWallet(
                AuditActions.LOGIN_REJECTED, address, null, "corr", NOW, Map.of("reason", "EXPIRED"));

        assertThat(event.actor()).isEqualTo(com.memetrade.shared.wallet.WalletAddress.mask(address));
        assertThat(event.wallet()).isEqualTo(address);
        assertThat(event.details()).doesNotContainValue(address);
        assertThat(event.toString()).doesNotContain(address).contains("wallet=" + event.actor());
    }

    @Test
    void withWalletKeepsEveryOtherFieldAndTheFactoriesOfBeforeCarryNoWallet() {
        String address = com.memetrade.testsupport.TestWallet.generate().address();
        AuditEventCommand person = AuditEventCommand.person(
                UUID.randomUUID(), UUID.randomUUID(), AuditActions.LOGIN_SUCCEEDED, "session:1", "corr", NOW, Map.of());

        AuditEventCommand withWallet = person.withWallet(address);

        assertThat(person.wallet()).isNull();
        assertThat(person.toString()).contains("wallet=none");
        assertThat(withWallet.wallet()).isEqualTo(address);
        assertThat(withWallet.eventId()).isEqualTo(person.eventId());
        assertThat(withWallet.actor()).isEqualTo(person.actor());
        assertThat(withWallet.tenantId()).isEqualTo(person.tenantId());
    }

    @Test
    void textThatIsNotAWalletIsRefusedWithoutEchoingIt() {
        String notAWallet = "not-a-wallet!";

        assertThatThrownBy(() -> AuditEventCommand.anonymousWallet(
                        AuditActions.LOGIN_REJECTED, notAWallet, null, "corr", NOW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(notAWallet);
        AuditEventCommand system = AuditEventCommand.system("ACTION", null, "corr", NOW, Map.of());
        assertThatThrownBy(() -> system.withWallet(notAWallet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(notAWallet);
    }
}
