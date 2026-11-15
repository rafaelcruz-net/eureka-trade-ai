package com.memetrade.riskcontrol.real.infra;

import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 261113-trading-real-bot U3 (U3-NFR8.3, infrastructure-specification.md "Configuração"): the keys
 * {@code memetrade.real.risk.*} with the defaults of {@code application.yml}. Checked at start-up by
 * {@link RealRiskConfig} through {@link RealRiskLimits#checked(boolean)}; a change needs a new deployment.
 */
@ConfigurationProperties(prefix = "memetrade.real.risk")
public record RealRiskProperties(
        Integer orderMaxBps,
        Integer dailyLossMaxBps,
        Integer dailyBuysMax,
        Duration signalMaxAge,
        Duration reservationTtl) {

    /** The limits, each missing key taking its default. */
    public RealRiskLimits limits() {
        RealRiskLimits defaults = RealRiskLimits.DEFAULTS;
        return new RealRiskLimits(
                orderMaxBps == null ? defaults.orderMaxBps() : orderMaxBps,
                dailyLossMaxBps == null ? defaults.dailyLossMaxBps() : dailyLossMaxBps,
                dailyBuysMax == null ? defaults.dailyBuysMax() : dailyBuysMax,
                signalMaxAge == null ? defaults.signalMaxAge() : signalMaxAge,
                reservationTtl == null ? defaults.reservationTtl() : reservationTtl);
    }
}
