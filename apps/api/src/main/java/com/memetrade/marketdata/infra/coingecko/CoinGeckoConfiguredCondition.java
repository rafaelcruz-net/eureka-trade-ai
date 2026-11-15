package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.infra.MarketDataProperties;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when {@code MARKET_SOURCES} ({@code memetrade.market.sources}) names the real source {@code coingecko}. */
public class CoinGeckoConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("memetrade.market.sources", Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .map(String::trim)
                .anyMatch(MarketDataProperties.COINGECKO::equals);
    }
}
