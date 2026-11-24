package com.memetrade.bots.infra;

import com.memetrade.bots.api.OwnQuantitySell;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 261121-paper-real-unico U4 (security-design.md "Flag"; BR5.1, NFR3.6, NFR8.7): the only source of the value of the
 * flag of the own quantity in both processes, bound from {@code memetrade.real.own-quantity-sell}
 * ({@code ${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}} in {@code application.yml}); {@code false} when absent. It lives
 * next to the {@code RealProperties} of the same prefix, each record bound only to its own fields. No
 * {@code @ProcessRole}: the api reads it for the page of the bots (C7) and the worker for the cycle.
 */
@ConfigurationProperties(prefix = "memetrade.real")
public record OwnQuantitySellProperties(boolean ownQuantitySell) implements OwnQuantitySell {

    @Override
    public boolean enabled() {
        return ownQuantitySell;
    }
}
