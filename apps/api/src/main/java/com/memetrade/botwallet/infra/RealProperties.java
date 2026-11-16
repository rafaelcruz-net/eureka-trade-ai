package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.Network;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the REAL under {@code memetrade.real} (see application.yml); REAL is off by default (BR1.1).
 *
 * <p>261113-trading-real-bot U5 (BR1.3, BR1.5, U5-NFR8.2): {@code founderUserId} names the ordinary account (BETA_USER)
 * of the founder, released first on the MAINNET; the start-up checks of {@link RealOpsStartupChecks} refuse the MAINNET
 * outside the production profile, without the founder, or with a configured CI result.
 */
@ConfigurationProperties(prefix = "memetrade.real")
public record RealProperties(
        boolean enabled,
        String requiredTermsVersion,
        Network network,
        long maxCapLamports,
        long feeReserveLamports,
        long maxWithdrawalFeeLamports,
        long rentExemptMinimumLamports,
        long baseFeeLamports,
        int computeUnitLimit,
        long computeUnitPrice,
        boolean seedTestRelease,
        TxSigner txsigner,
        Rpc rpc,
        Jobs jobs,
        String founderUserId) {

    /** U5 (BR1.3): the founder, or empty when the property is blank. */
    public java.util.Optional<java.util.UUID> founder() {
        return founderUserId == null || founderUserId.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(java.util.UUID.fromString(founderUserId.trim()));
    }

    public record TxSigner(String url, String token, Duration timeout) {}

    public record Rpc(String primaryUrl, String secondaryUrl, int maxRps, Duration timeout) {}

    public record Jobs(
            Duration depositScanInterval,
            Duration withdrawalInterval,
            int withdrawalBatchSize,
            Duration withdrawalLease,
            Duration stuckChallengeInterval) {}
}
