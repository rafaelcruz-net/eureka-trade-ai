package com.memetrade.botwallet.infra.github;

import com.memetrade.botwallet.domain.CiResult;
import com.memetrade.botwallet.domain.CiStatus;
import com.memetrade.botwallet.infra.RealOpsStartupChecks;

/**
 * 261113-trading-real-bot U5 (BR1.4, infrastructure-specification.md "Configuração do teste"): the CI as a configured
 * value ({@code MEMETRADE_REAL_OPS_CI=GREEN} or {@code RED}) on the LOCALNET, the DEVNET and in the tests. The start-up
 * checks refuse it with the MAINNET, where only the GitHub counts.
 */
public final class ConfiguredCiStatus implements CiStatus {

    private final CiResult result;

    public ConfiguredCiStatus(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (!RealOpsStartupChecks.CONFIGURED_CI.contains(value)) {
            throw new IllegalArgumentException("MEMETRADE_REAL_OPS_CI must be GREEN or RED");
        }
        this.result = "GREEN".equals(value) ? CiResult.green("configured") : CiResult.red();
    }

    @Override
    public CiResult check(String commitSha) {
        return result;
    }
}
