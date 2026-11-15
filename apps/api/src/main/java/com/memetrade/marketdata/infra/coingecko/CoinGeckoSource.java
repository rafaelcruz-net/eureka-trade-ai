package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.infra.MarketDataProperties;

/** Identity of the real source of U2: {@code coingecko}, never simulated (BR1.5). */
public final class CoinGeckoSource {

    public static final SourceRef REF = new SourceRef(MarketDataProperties.COINGECKO, "CoinGecko", false);

    private CoinGeckoSource() {}
}
