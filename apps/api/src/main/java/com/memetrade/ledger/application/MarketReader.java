package com.memetrade.ledger.application;

import com.memetrade.ledger.domain.MarketInputs;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads C11 for a fill or a close (BR1.10): a local query of U4, no network inside the cycle (NFR3.1). After the first
 * buy the decimals recorded in the position win; a different value from C11 is logged with identifiers only.
 */
@Component
public class MarketReader {

    private static final Logger log = LoggerFactory.getLogger(MarketReader.class);

    private final MarketDataQuery market;
    private final Clock clock;

    public MarketReader(MarketDataQuery market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    public MarketInputs read(TokenId token, Optional<Integer> recordedDecimals, UUID botId) {
        MarketValue decimals = market.decimals(token);
        if (recordedDecimals.isPresent()) {
            if (decimals instanceof MarketValue.Known known
                    && known.value().intValueExact() != recordedDecimals.get()) {
                log.warn(
                        "Token decimals differ from the recorded position; the recorded value is used: botId={}",
                        botId);
            }
            decimals = new MarketValue.Known(
                    BigDecimal.valueOf(recordedDecimals.get()),
                    decimals instanceof MarketValue.Known known ? known.source() : RECORDED,
                    clock.instant(),
                    false);
        }
        return new MarketInputs(market.referencePrice(token), market.liquidity(token), decimals, clock.instant());
    }

    private static final com.memetrade.marketdata.api.SourceRef RECORDED =
            new com.memetrade.marketdata.api.SourceRef("position", "Posição gravada", false);
}
