package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditPublishRejectedException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of u5-anonymization (NFR8.2): ACCOUNT_ANONYMIZED carries only {@code botsStopped}, {@code sessionsRevoked}
 * and {@code feedbackDeleted}; the validator refuses any other key of {@code details}, naming the key and never the
 * value, and keeps the rules of every other action as they were.
 */
@Tag("u5-anonymization")
class AnonymizedAccountEventValidatorTest {

    private final AuditEventValidator validator = new AuditEventValidator();

    @Test
    void theThreeCountsAreAccepted() {
        assertThatCode(() -> validator.validate(event(
                        AuditActions.ACCOUNT_ANONYMIZED,
                        Map.of("botsStopped", "2", "sessionsRevoked", "3", "feedbackDeleted", "0"))))
                .doesNotThrowAnyException();
    }

    @Test
    void fewerCountsAreAcceptedToo() {
        assertThatCode(() -> validator.validate(event(AuditActions.ACCOUNT_ANONYMIZED, Map.of("botsStopped", "1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void anyOtherKeyIsRefusedByName() {
        for (String key : new String[] {"requestId", "wallet", "name", "text", "walletMasked"}) {
            assertThatThrownBy(() -> validator.validate(
                            event(AuditActions.ACCOUNT_ANONYMIZED, Map.of("botsStopped", "1", key, "x"))))
                    .isInstanceOfSatisfying(AuditPublishRejectedException.class, rejected -> {
                        org.assertj.core.api.Assertions.assertThat(rejected.getMessage())
                                .contains("details." + key)
                                .doesNotContain("\"x\"");
                    });
        }
    }

    @Test
    void anotherActionKeepsAcceptingItsOwnDetails() {
        assertThatCode(() -> validator.validate(event(
                        AuditActions.DATA_REQUEST_TRANSITIONED,
                        Map.of("requestId", UUID.randomUUID().toString(), "from", "IN_PROGRESS", "to", "DONE"))))
                .doesNotThrowAnyException();
    }

    private static AuditEventCommand event(String action, Map<String, String> details) {
        return new AuditEventCommand(
                UUID.randomUUID(),
                Optional.of(UUID.randomUUID()),
                UUID.randomUUID().toString(),
                ActorType.ADMIN,
                action,
                "user:" + UUID.randomUUID(),
                "corr-u5",
                Instant.parse("2026-11-10T12:00:00Z"),
                details);
    }
}
