package com.memetrade.botwallet.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 261113-trading-real-bot U5 (U5-NFR3.7, U5-NFR1.1, infrastructure-specification.md): the REAL operations under
 * {@code memetrade.real.ops}. Fixed in application.yml; only the reconciliation interval (the e2e uses 15 s), the
 * configured CI result (never with the MAINNET) and the GitHub repository and token come from the environment. The token
 * exists only in the worker and is never logged.
 *
 * @param reconciliationThreads the executor of the reconciliation (at most 4 wallets at once, U5-NFR3.2)
 * @param reconciliationTick how often the reconciliation job looks for a wallet that is due
 * @param ci {@code GREEN} or {@code RED} replaces the GitHub outside the MAINNET; empty reads the GitHub
 */
@ConfigurationProperties(prefix = "memetrade.real.ops")
public record RealOpsProperties(
        int reconciliationThreads,
        Duration gatesInterval,
        Duration reconciliationInterval,
        Duration reconciliationTick,
        Duration deferredAlertAfter,
        Duration killSwitchCancelInterval,
        Duration txsignerResendInterval,
        Duration restoreCheckInterval,
        Duration gaugesInterval,
        String ci,
        Github github) {

    /** The read of the CI (U5-NFR1.1): the REST API of the GitHub, 5 s, a fine-grained read-only Actions token. */
    public record Github(String apiUrl, String repository, String workflow, String token, Duration timeout) {

        @Override
        public String toString() {
            return "Github[apiUrl=" + apiUrl + ", repository=" + repository + ", workflow=" + workflow + "]";
        }
    }
}
