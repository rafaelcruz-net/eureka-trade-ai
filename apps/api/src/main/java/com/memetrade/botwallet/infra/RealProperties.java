package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.Network;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the REAL under {@code memetrade.real} (see application.yml); REAL is off by default (BR1.1). */
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
        Jobs jobs) {

    public record TxSigner(String url, String token, Duration timeout) {}

    public record Rpc(String primaryUrl, String secondaryUrl, int maxRps, Duration timeout) {}

    public record Jobs(
            Duration depositScanInterval,
            Duration withdrawalInterval,
            int withdrawalBatchSize,
            Duration withdrawalLease,
            Duration stuckChallengeInterval) {}
}
