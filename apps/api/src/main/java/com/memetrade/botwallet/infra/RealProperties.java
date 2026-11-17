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
        String founderUserId,
        Exits exits) {

    /**
     * 261113-trading-real-bot U7 (BR0.3, BR9.3, U7-NFR4.10, infrastructure-specification.md "Caminho simulado"): the
     * conditional exits are OFF by default in every profile; the simulated adapter is accepted only on the LOCALNET.
     */
    public RealProperties {
        exits = exits == null ? Exits.OFF : exits;
        if (Exits.SIMULATED.equals(exits.adapter()) && network != Network.LOCALNET) {
            throw new IllegalArgumentException(
                    "memetrade.real.exits.adapter=simulated is accepted only on the LOCALNET network");
        }
    }

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

    /**
     * 261113-trading-real-bot U7: {@code memetrade.real.exits}. {@code adapter} is {@code jupiter} (the real Trigger API,
     * built only after the recording of BR0) or {@code simulated} (LOCALNET and the test profile only, never a
     * transaction). The optional Jupiter API key is never printed (BR9.4, U7-NFR1.5).
     */
    public record Exits(boolean enabled, String adapter, String jupiterApiKey) {

        public static final String SIMULATED = "simulated";
        public static final String JUPITER = "jupiter";

        /** The default: off, with the real adapter (which does not exist until the recording). */
        public static final Exits OFF = new Exits(false, JUPITER, "");

        public Exits {
            adapter = adapter == null || adapter.isBlank() ? JUPITER : adapter.trim();
            if (!SIMULATED.equals(adapter) && !JUPITER.equals(adapter)) {
                throw new IllegalArgumentException("memetrade.real.exits.adapter must be jupiter or simulated");
            }
            jupiterApiKey = jupiterApiKey == null ? "" : jupiterApiKey;
        }

        @Override
        public String toString() {
            return "Exits[enabled=" + enabled + ", adapter=" + adapter + ", jupiterApiKey="
                    + (jupiterApiKey.isEmpty() ? "<none>" : "<masked>") + "]";
        }
    }
}
