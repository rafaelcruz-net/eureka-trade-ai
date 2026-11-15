package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U4 (U4-NFR8.1, infrastructure-specification.md "Chave determinística no KMS simulado"): the production
 * profile refuses the seed of the simulated KMS, and the MAINNET network needs the production profile in every
 * process.
 */
class ProductionChecksSwapTest {

    private static final String ACCOUNT = "123456789012";

    private static ProductionChecks.Input input(boolean seed) {
        return new ProductionChecks.Input(
                "", List.of(), ACCOUNT, "secrets-manager", "hmac-key-id", Set.of("production"), seed);
    }

    @Test
    void theSeedOfTheSimulatedKmsIsRefusedInProduction() {
        assertThat(ProductionChecks.violations(input(true), () -> ACCOUNT))
                .singleElement()
                .asString()
                .contains("KMS_SIM_SEED");
        assertThat(ProductionChecks.violations(input(false), () -> ACCOUNT)).isEmpty();
    }

    @Test
    void mainnetNeedsTheProductionProfile() {
        assertThat(ProductionChecks.networkViolation("MAINNET", Set.of("kms-sim")))
                .hasValueSatisfying(v -> assertThat(v).contains("TXSIGNER_NETWORK"));
        assertThat(ProductionChecks.networkViolation("MAINNET", Set.of())).isPresent();
        assertThat(ProductionChecks.networkViolation("MAINNET", Set.of("production")))
                .isEmpty();
        assertThat(ProductionChecks.networkViolation("LOCALNET", Set.of("kms-sim")))
                .isEmpty();
        assertThat(ProductionChecks.networkViolation("DEVNET", Set.of())).isEmpty();
    }

    @Test
    void anUnknownNetworkIsRefusedEverywhere() {
        assertThat(ProductionChecks.networkViolation("TESTNET", Set.of("production")))
                .isPresent();
        assertThat(ProductionChecks.networkViolation(null, Set.of())).isPresent();
    }
}
