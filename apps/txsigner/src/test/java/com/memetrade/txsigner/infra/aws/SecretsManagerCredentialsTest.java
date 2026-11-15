package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Step 9, adapter tested after the code (NFR1.5): during a rotation both AWSCURRENT and AWSPREVIOUS are accepted, the
 * age of the current version feeds the alert at 80 days, and a failed refresh keeps the last tokens.
 */
class SecretsManagerCredentialsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final SecretsManagerClient client = mock(SecretsManagerClient.class);
    private final SecretsManagerCredentials credentials =
            new SecretsManagerCredentials(client, "txsigner/service-token", Clock.fixed(NOW, ZoneOffset.UTC));

    private void answer(String stage, String value, Instant created) {
        when(client.getSecretValue(GetSecretValueRequest.builder()
                        .secretId("txsigner/service-token")
                        .versionStage(stage)
                        .build()))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString(value)
                        .createdDate(created)
                        .build());
    }

    @Test
    void bothVersionsAreAcceptedDuringARotation() {
        answer("AWSCURRENT", "new-token", NOW.minusSeconds(3_600));
        answer("AWSPREVIOUS", "old-token", NOW.minusSeconds(86_400 * 90L));

        credentials.refresh();

        assertThat(credentials.accepts("new-token")).isTrue();
        assertThat(credentials.accepts("old-token")).isTrue();
        assertThat(credentials.accepts("other")).isFalse();
        assertThat(credentials.accepts(null)).isFalse();
        assertThat(credentials.currentAgeDays()).isZero();
    }

    @Test
    void withoutAPreviousVersionOnlyTheCurrentIsAcceptedAndItsAgeIsReported() {
        answer("AWSCURRENT", "only-token", NOW.minusSeconds(86_400 * 81L));
        when(client.getSecretValue(GetSecretValueRequest.builder()
                        .secretId("txsigner/service-token")
                        .versionStage("AWSPREVIOUS")
                        .build()))
                .thenThrow(ResourceNotFoundException.builder()
                        .message("no previous")
                        .build());

        credentials.refresh();

        assertThat(credentials.acceptedTokens()).containsExactly("only-token");
        assertThat(credentials.currentAgeDays()).isEqualTo(81);
    }

    @Test
    void aFailedRefreshKeepsTheLastTokensRead() {
        answer("AWSCURRENT", "kept-token", NOW);
        answer("AWSPREVIOUS", "kept-previous", NOW);
        credentials.refresh();
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(SdkClientException.create("network down"));

        credentials.refresh();

        assertThat(credentials.accepts("kept-token")).isTrue();
    }

    @Test
    void beforeTheFirstReadNothingIsAcceptedAndTheAgeIsUnknown() {
        assertThat(credentials.accepts("anything")).isFalse();
        assertThat(credentials.currentAgeDays()).isEqualTo(-1);
    }
}
