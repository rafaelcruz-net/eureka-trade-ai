package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Step 8, adapter tested after the code (NFR1.6, security-design.md "Configuração de produção"): the production profile
 * refuses a foreign KMS endpoint, another account, the kms-sim profile and a credential outside the secrets manager.
 */
class ProductionChecksTest {

    private static final String ACCOUNT = "123456789012";
    private static final String VPC_ENDPOINT = "https://vpce-0abc.kms.us-east-1.vpce.amazonaws.com";

    private static ProductionChecks.Input input(String endpoint, String profile, String source) {
        return new ProductionChecks.Input(
                endpoint, List.of(VPC_ENDPOINT), ACCOUNT, source, "hmac-key-id", Set.of(profile));
    }

    @Test
    void theDefaultEndpointOrAnAllowedVpcEndpointWithTheRightAccountPasses() {
        assertThat(ProductionChecks.violations(input("", "production", "secrets-manager"), () -> ACCOUNT))
                .isEmpty();
        assertThat(ProductionChecks.violations(input(VPC_ENDPOINT, "production", "secrets-manager"), () -> ACCOUNT))
                .isEmpty();
    }

    @Test
    void anotherEndpointIsRefused() {
        assertThat(ProductionChecks.violations(
                        input("http://kms-sim:4599", "production", "secrets-manager"), () -> ACCOUNT))
                .singleElement()
                .asString()
                .contains("TXSIGNER_KMS_ENDPOINT");
    }

    @Test
    void anotherAccountIsRefused() {
        assertThat(ProductionChecks.violations(input("", "production", "secrets-manager"), () -> "999999999999"))
                .singleElement()
                .asString()
                .contains("account");
    }

    @Test
    void theKmsSimProfileAndAnEnvironmentCredentialAreRefused() {
        List<String> violations = ProductionChecks.violations(input("", "kms-sim", "environment"), () -> ACCOUNT);

        assertThat(violations).hasSize(2);
        assertThat(String.join(" ", violations)).contains("kms-sim").contains("TXSIGNER_CREDENTIAL_SOURCE");
    }

    @Test
    void aMissingAccountOrHmacKeyIsRefusedWithoutCallingSts() {
        ProductionChecks.Input missing =
                new ProductionChecks.Input(null, List.of(), "", "secrets-manager", " ", Set.of("production"));

        List<String> violations = ProductionChecks.violations(missing, () -> {
            throw new AssertionError("STS must not be called");
        });

        assertThat(String.join(" ", violations))
                .contains("TXSIGNER_AWS_ACCOUNT_ID")
                .contains("TXSIGNER_KMS_HMAC_KEY_ID");
    }
}
