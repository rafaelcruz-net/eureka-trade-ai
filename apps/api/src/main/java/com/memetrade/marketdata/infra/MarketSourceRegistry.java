package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoSource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Registers the configured sources at start-up (NFR10.6): a new source starts PENDING, an existing one keeps its
 * stored state (a restart never rewrites it, unlike U1 which rewrote FAILED) and the sources no longer configured are
 * disabled. Registration is retried before a collection of a source that is still missing.
 */
public class MarketSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarketSourceRegistry.class);

    private final List<MarketSourceAdapter> adapters;
    private final MarketStore store;
    private final MarketDataProperties properties;

    public MarketSourceRegistry(
            List<MarketSourceAdapter> adapters, MarketStore store, MarketDataProperties properties) {
        this.adapters = List.copyOf(adapters);
        this.store = store;
        this.properties = properties;
    }

    /**
     * Registers every configured source; a database failure is logged and retried before the next collection. U2: the
     * real source {@code coingecko} is registered by both processes (it starts PENDING), though only the worker has
     * its adapter; a source stays enabled while {@code MARKET_SOURCES} names it, whichever process registers it.
     */
    public void registerAll() {
        try {
            Map<String, SourceRef> refs = new LinkedHashMap<>();
            adapters.forEach(adapter -> refs.put(adapter.source().sourceId(), adapter.source()));
            if (properties.coingeckoConfigured()) {
                refs.putIfAbsent(MarketDataProperties.COINGECKO, CoinGeckoSource.REF);
            }
            refs.values().forEach(ref -> store.registerSource(ref, properties.collectInterval()));
            Set<String> enabled = new LinkedHashSet<>(refs.keySet());
            enabled.addAll(properties.sources());
            store.disableSourcesOtherThan(List.copyOf(enabled));
        } catch (DataAccessException unavailable) {
            log.error(
                    "Market sources could not be registered: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    public void register(MarketSourceAdapter adapter) {
        store.registerSource(adapter.source(), properties.collectInterval());
    }

    public void ensureRegistered(MarketSourceAdapter adapter) {
        if (store.sourceState(adapter.source().sourceId()).isEmpty()) {
            register(adapter);
        }
    }
}
