package com.memetrade.marketdata.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch read of the inputs of the risk indicator (F5, performance-design.md): two queries for every token with an
 * observation in the last 24 hours, the resolution of each field in memory. Used only by the RiskAssessment module.
 */
public interface MarketRiskInputs {

    /** Every token with an observation in the last 24 hours (BR1.8), whatever the state of its sources. */
    List<TokenInputs> recentTokens();

    /** The inputs of one token; empty when it has no observation in the last 24 hours. */
    Optional<TokenInputs> inputsFor(TokenId token);

    /**
     * @param fields the resolved PRICE, LIQUIDITY, VOLUME_24H and HOLDERS (F2), stale computed now
     * @param allSourcesFailed every source that informs the token is FAILED (BR3.8)
     * @param priceOneHourAgo the most recent KNOWN PRICE sample observed 60 to 65 minutes ago, of a source that is not
     *     FAILED, or {@code Unknown(NOT_YET_OBSERVED)} (BR1.10)
     */
    record TokenInputs(
            TokenId token,
            boolean allSourcesFailed,
            Map<MarketField, ResolvedField> fields,
            MarketValue priceOneHourAgo) {

        public TokenInputs {
            fields = Map.copyOf(fields);
        }

        public MarketValue value(MarketField field) {
            ResolvedField resolved = fields.get(field);
            return resolved == null
                    ? new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty())
                    : resolved.value();
        }
    }
}
