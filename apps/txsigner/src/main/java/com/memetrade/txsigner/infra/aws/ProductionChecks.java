package com.memetrade.txsigner.infra.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Start-up checks of the production profile (NFR1.6, security-design.md "Configuração de produção"): the TxSigner does
 * not start when the KMS endpoint is neither the AWS default nor one of the account's VPC endpoints listed, when the
 * account of the credential is not the configured one, when the {@code kms-sim} profile is active, or when the service
 * credential does not come from the secrets manager. Each violation names the setting to fix, never a secret.
 */
public final class ProductionChecks {

    public static final String KMS_SIM_PROFILE = "kms-sim";

    private ProductionChecks() {}

    /**
     * @param kmsSimSeedPresent the variable {@code KMS_SIM_SEED} of the simulated KMS is set (U4: refused in production)
     */
    public record Input(
            String kmsEndpoint,
            List<String> allowedKmsEndpoints,
            String expectedAccountId,
            String credentialSource,
            String hmacKeyId,
            Set<String> activeProfiles,
            boolean kmsSimSeedPresent) {

        /** The input of the U1, without the seed of the simulated KMS. */
        public Input(
                String kmsEndpoint,
                List<String> allowedKmsEndpoints,
                String expectedAccountId,
                String credentialSource,
                String hmacKeyId,
                Set<String> activeProfiles) {
            this(
                    kmsEndpoint,
                    allowedKmsEndpoints,
                    expectedAccountId,
                    credentialSource,
                    hmacKeyId,
                    activeProfiles,
                    false);
        }
    }

    /** The networks of the REAL (FR10.4). */
    private static final Set<String> NETWORKS = Set.of("LOCALNET", "DEVNET", "MAINNET");

    /**
     * U4-NFR8.1: checked in EVERY profile at start-up. {@code TXSIGNER_NETWORK=MAINNET} needs the production profile;
     * a network outside the three is refused everywhere.
     */
    public static java.util.Optional<String> networkViolation(String network, Set<String> activeProfiles) {
        if (network == null || !NETWORKS.contains(network)) {
            return java.util.Optional.of("TXSIGNER_NETWORK must be LOCALNET, DEVNET or MAINNET");
        }
        if ("MAINNET".equals(network) && !activeProfiles.contains("production")) {
            return java.util.Optional.of("TXSIGNER_NETWORK=MAINNET needs the production profile");
        }
        return java.util.Optional.empty();
    }

    public static List<String> violations(Input input, Supplier<String> callerAccountId) {
        List<String> violations = new ArrayList<>();
        if (input.activeProfiles().contains(KMS_SIM_PROFILE)) {
            violations.add("the profile kms-sim is active (SPRING_PROFILES_ACTIVE)");
        }
        if (input.kmsSimSeedPresent()) {
            violations.add("KMS_SIM_SEED is set: the deterministic key of the simulated KMS is test only");
        }
        String endpoint = input.kmsEndpoint();
        if (endpoint != null
                && !endpoint.isBlank()
                && !input.allowedKmsEndpoints().contains(endpoint)) {
            violations.add("TXSIGNER_KMS_ENDPOINT is neither the AWS default nor an allowed VPC endpoint");
        }
        if (!"secrets-manager".equals(input.credentialSource())) {
            violations.add("TXSIGNER_CREDENTIAL_SOURCE must be secrets-manager");
        }
        if (input.hmacKeyId() == null || input.hmacKeyId().isBlank()) {
            violations.add("TXSIGNER_KMS_HMAC_KEY_ID is missing");
        }
        if (input.expectedAccountId() == null || !input.expectedAccountId().matches("^[0-9]{12}$")) {
            violations.add("TXSIGNER_AWS_ACCOUNT_ID is missing or not an AWS account id");
        } else if (violations.isEmpty() && !input.expectedAccountId().equals(callerAccountId.get())) {
            violations.add("the account of the credential differs from TXSIGNER_AWS_ACCOUNT_ID");
        }
        return violations;
    }
}
