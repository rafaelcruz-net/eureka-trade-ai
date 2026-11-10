package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 7 of u5-anonymization (NFR8.1, NFR8.3, NFR8.4; monitoring-design.md): the success counter grows after the commit
 * only, the failure counter exists from the start with both closed reasons and no label of a person or request, a
 * refusal or a repetition counts nothing, the duration timer records the attempt, and the INFO log carries the request id
 * and the counts only.
 */
class AnonymizationObservabilityIT extends AbstractAnonymizationIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void theFailureCounterIsRegisteredAtZeroForBothReasonsWithTheReasonLabelOnly() {
        for (String reason : new String[] {"LOCK_TIMEOUT", "ROLLED_BACK"}) {
            var counter = meters.find("memetrade.account.anonymization.failures")
                    .tag("reason", reason)
                    .counter();
            assertThat(counter).as(reason).isNotNull();
            assertThat(counter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsExactly("reason");
        }
        assertThat(meters.find("memetrade.accounts.anonymized")
                        .counter()
                        .getId()
                        .getTags())
                .isEmpty();
    }

    @Test
    void aSuccessIsCountedLoggedAndTimedAndARepetitionIsNot() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Deletion deletion = deletionInProgress();
        double before = anonymized();
        long timed = timer().count();

        try (logs) {
            anonymize(deletion);
            anonymize(deletion);
        }

        assertThat(anonymized()).isEqualTo(before + 1);
        assertThat(timer().count()).isEqualTo(timed + 1);
        assertThat(logs.text())
                .contains("Account anonymized: requestId=" + deletion.requestId())
                .doesNotContain(deletion.wallet());
    }

    @Test
    void aRefusalCountsNeitherASuccessNorAFailure() throws Exception {
        WalletGuest guest = newWalletGuest();
        String received = created(guest.bearer(), "DELETION");
        double before = anonymized();
        double failures = failures();

        org.assertj.core.api.Assertions.catchThrowable(
                () -> anonymize(newAdministrator(), received, UUID.randomUUID()));

        assertThat(anonymized()).isEqualTo(before);
        assertThat(failures()).isEqualTo(failures);
    }

    private double anonymized() {
        return meters.find("memetrade.accounts.anonymized").counter().count();
    }

    private double failures() {
        return meters.find("memetrade.account.anonymization.failures").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private Timer timer() {
        return meters.find("memetrade.account.anonymization").timer();
    }
}
