package com.memetrade.txsigner.infra.aws;

import com.memetrade.txsigner.infra.credential.ServiceCredentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Cloud credential (infrastructure-specification.md "mTLS e credencial de serviço"): the versions AWSCURRENT and
 * AWSPREVIOUS of the secret are both accepted and re-read every five minutes, so the 24 h overlap of a rotation needs
 * no restart. A failed refresh keeps the last tokens read and is logged without the value.
 */
public final class SecretsManagerCredentials implements ServiceCredentials {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerCredentials.class);

    private final SecretsManagerClient client;
    private final String secretId;
    private final Clock clock;
    private volatile List<String> tokens = List.of();
    private volatile Instant currentCreatedAt;

    public SecretsManagerCredentials(SecretsManagerClient client, String secretId, Clock clock) {
        this.client = client;
        this.secretId = secretId;
        this.clock = clock;
    }

    /** Re-reads both versions; called at start-up and every refresh interval. */
    public void refresh() {
        try {
            Optional<GetSecretValueResponse> current = read("AWSCURRENT");
            Optional<GetSecretValueResponse> previous = read("AWSPREVIOUS");
            List<String> fresh = new ArrayList<>();
            current.map(GetSecretValueResponse::secretString).ifPresent(fresh::add);
            previous.map(GetSecretValueResponse::secretString).ifPresent(fresh::add);
            tokens = List.copyOf(fresh);
            currentCreatedAt = current.map(GetSecretValueResponse::createdDate).orElse(null);
        } catch (SdkException failure) {
            LOG.warn(
                    "Service credential refresh failed; keeping the last tokens read ({})",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public List<String> acceptedTokens() {
        return tokens;
    }

    @Override
    public long currentAgeDays() {
        Instant created = currentCreatedAt;
        return created == null ? -1 : Duration.between(created, clock.instant()).toDays();
    }

    private Optional<GetSecretValueResponse> read(String stage) {
        try {
            return Optional.of(client.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(secretId)
                    .versionStage(stage)
                    .build()));
        } catch (ResourceNotFoundException noSuchVersion) {
            return Optional.empty();
        }
    }
}
