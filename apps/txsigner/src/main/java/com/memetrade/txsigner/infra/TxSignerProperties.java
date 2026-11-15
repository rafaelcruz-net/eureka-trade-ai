package com.memetrade.txsigner.infra;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of {@code application.yml} under {@code txsigner} (see the comments there). */
@ConfigurationProperties("txsigner")
public record TxSignerProperties(
        Real real,
        String network,
        long maxCapLamports,
        long maxWithdrawalFeeLamports,
        int maxWithdrawalComputeUnits,
        long baseFeeLamportsPerSignature,
        long maxSwapPriorityFeeLamports,
        Recovery recovery,
        int maxKeysPerHour,
        Credential credential,
        Kms kms,
        Aws aws,
        Jobs jobs) {

    public record Real(boolean enabled) {}

    public record Recovery(boolean enabled, Duration waitDuration) {}

    public record Credential(
            String source, String current, String previous, String secretId, Duration refreshInterval) {}

    public record Kms(String endpoint, String region, String hmacKeyId, Duration callTimeout, String keyPolicy) {}

    /** Production checks (NFR1.6): the account of the credential and the VPC endpoints of the KMS allowed. */
    public record Aws(String accountId, List<String> allowedKmsEndpoints) {}

    public record Jobs(String nonceCleanupCron, String orphanKeysCron, Duration orphanKeyMinAge) {}
}
