package com.memetrade.pnl.application;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.PositionView;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.domain.ExitValuation;
import com.memetrade.pnl.domain.Mark;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The exit valuation of the open positions now (BR6.2): C11 for each token and the spread and fee of the policy in
 * force, read through {@link LedgerReadModel} (the {@code pnl} module never reads the ledger tables).
 */
@Component
public class PortfolioMarks {

    private final LedgerReadModel ledger;
    private final MarketDataQuery market;
    private final Clock clock;

    public PortfolioMarks(LedgerReadModel ledger, MarketDataQuery market, Clock clock) {
        this.ledger = ledger;
        this.market = market;
        this.clock = clock;
    }

    /** A valuation with the policy in force. */
    public ExitValuation valuation() {
        LedgerReadModel.PolicyView policy = ledger.policy();
        return new ExitValuation(policy.spreadPct(), policy.feePct());
    }

    /** The mark of one open position, read now. */
    public Mark mark(ExitValuation valuation, PositionView position) {
        TokenId token = new TokenId(position.chain(), position.tokenMint());
        return valuation.value(
                position.quantity(),
                position.costBasis(),
                market.referencePrice(token),
                market.liquidity(token),
                clock.instant());
    }

    /** The marks of every position with quantity above zero (OPEN or CLOSE_PENDING). */
    public List<Mark> openMarks(List<PositionView> positions) {
        ExitValuation valuation = valuation();
        return positions.stream()
                .filter(p -> p.quantity().signum() > 0)
                .map(p -> mark(valuation, p))
                .toList();
    }
}
