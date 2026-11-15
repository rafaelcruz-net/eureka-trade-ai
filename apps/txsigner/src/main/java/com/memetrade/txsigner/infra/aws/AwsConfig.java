package com.memetrade.txsigner.infra.aws;

import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.infra.TxSignerProperties;
import com.memetrade.txsigner.infra.credential.EnvironmentCredentials;
import com.memetrade.txsigner.infra.credential.ServiceCredentials;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * The only place that builds AWS clients (tech-stack-decisions.md): the KMS client behind the circuit, the service
 * credential from the secrets manager in the cloud, and the start-up checks of the production profile (NFR1.6).
 */
@Configuration
public class AwsConfig {

    @Bean(destroyMethod = "close")
    KmsClient kmsClient(TxSignerProperties properties) {
        KmsClientBuilder builder = KmsClient.builder()
                .region(Region.of(properties.kms().region()))
                .httpClient(UrlConnectionHttpClient.create());
        String endpoint = properties.kms().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    KmsPort kmsPort(KmsClient client, TxSignerProperties properties, MeterRegistry meters) {
        AwsKmsAdapter adapter = new AwsKmsAdapter(
                client,
                properties.kms().hmacKeyId(),
                properties.kms().keyPolicy(),
                properties.kms().callTimeout());
        return new ResilientKmsPort(adapter, meters);
    }

    @Bean
    ServiceCredentials serviceCredentials(TxSignerProperties properties, Clock clock) {
        TxSignerProperties.Credential credential = properties.credential();
        if ("secrets-manager".equals(credential.source())) {
            SecretsManagerCredentials fromSecret = new SecretsManagerCredentials(
                    SecretsManagerClient.builder()
                            .region(Region.of(properties.kms().region()))
                            .httpClient(UrlConnectionHttpClient.create())
                            .build(),
                    credential.secretId(),
                    clock);
            fromSecret.refresh();
            return fromSecret;
        }
        return new EnvironmentCredentials(credential.current(), credential.previous());
    }

    /** Re-reads AWSCURRENT and AWSPREVIOUS every five minutes (NFR1.5); nothing to do with local credentials. */
    @Scheduled(fixedDelayString = "${txsigner.credential.refresh-interval}")
    void refreshCredentials() {
        // The bean is looked up lazily so a local run never builds a secrets manager client.
        if (credentialsToRefresh != null) {
            credentialsToRefresh.refresh();
        }
    }

    private volatile SecretsManagerCredentials credentialsToRefresh;

    @Bean
    InitializingBean credentialRefreshRegistration(ServiceCredentials credentials) {
        return () -> {
            if (credentials instanceof SecretsManagerCredentials fromSecret) {
                credentialsToRefresh = fromSecret;
            }
        };
    }

    /** NFR1.6: the production profile refuses to start on a wrong KMS endpoint, account, profile or credential. */
    @Bean
    @Profile("production")
    InitializingBean productionChecks(TxSignerProperties properties, Environment environment) {
        return () -> {
            ProductionChecks.Input input = new ProductionChecks.Input(
                    properties.kms().endpoint(),
                    properties.aws() == null || properties.aws().allowedKmsEndpoints() == null
                            ? List.of()
                            : properties.aws().allowedKmsEndpoints(),
                    properties.aws() == null ? null : properties.aws().accountId(),
                    properties.credential().source(),
                    properties.kms().hmacKeyId(),
                    Set.of(environment.getActiveProfiles()),
                    environment.containsProperty("KMS_SIM_SEED"));
            List<String> violations = ProductionChecks.violations(input, () -> {
                try (StsClient sts = StsClient.builder()
                        .region(Region.of(properties.kms().region()))
                        .httpClient(UrlConnectionHttpClient.create())
                        .build()) {
                    return sts.getCallerIdentity().account();
                }
            });
            if (!violations.isEmpty()) {
                throw new IllegalStateException("TxSigner production checks failed: " + String.join("; ", violations));
            }
        };
    }
}
