package com.memetrade.bots.worker;

import com.memetrade.decision.domain.SelectionRule;
import com.memetrade.decision.domain.TokenInputs;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.shared.config.ProcessRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The reads of U4 in a cycle (performance-design.md): per token ONE window of the size the strategy needs, the
 * reference price, the places of the mint and the assessment of C12; the scanner listing once per cycle, only when the
 * selection has rules. Nothing is cached between cycles; missing values stay UNKNOWN (C11).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class CycleMarketReader {

    /** The scanner page read for the filters (BR4.9 keeps at most 10 of them). */
    static final int LISTING_PAGE = 100;

    private final MarketDataQuery market;
    private final MarketListingQuery listing;
    private final RiskAssessmentQuery assessments;

    public CycleMarketReader(MarketDataQuery market, MarketListingQuery listing, RiskAssessmentQuery assessments) {
        this.market = market;
        this.listing = listing;
        this.assessments = assessments;
    }

    /**
     * Everything the decision needs about {@code token}. The window reaches back {@code windowMinutes + 10} minutes from
     * now (slotStart + 60 s), which covers {@code b_(n-k)} to {@code b_n} of BR4.3 and BR4.4.
     */
    public TokenInputs read(TokenId token, BigDecimal windowMinutes) {
        long minutes = windowMinutes.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        return new TokenInputs(
                token,
                market.window(token, Duration.ofMinutes(minutes + 10)),
                market.referencePrice(token),
                market.decimals(token),
                assessments.assess(token));
    }

    /** BR4.9: one scanner page by liquidity descending, only when the selection has rules. */
    public List<ListedToken> listed(List<SelectionRule> rules, Instant now) {
        if (rules.isEmpty()) {
            return List.of();
        }
        return listing.list(new MarketListingQuery.ListingRequest(
                        now,
                        MarketListingQuery.Filters.none(),
                        MarketListingQuery.SortField.LIQUIDITY,
                        MarketListingQuery.SortOrder.DESC,
                        Optional.empty(),
                        LISTING_PAGE))
                .tokens();
    }
}
