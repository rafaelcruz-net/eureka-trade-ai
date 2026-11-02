package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import java.util.List;

/** The adapters of {@code MARKET_SOURCES}, in configuration order. */
public record ConfiguredSources(List<MarketSourceAdapter> adapters) {

    public ConfiguredSources {
        adapters = List.copyOf(adapters);
    }
}
