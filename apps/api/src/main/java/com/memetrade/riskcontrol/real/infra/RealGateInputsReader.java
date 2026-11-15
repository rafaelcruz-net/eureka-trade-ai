package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealGateUnavailable.Dependency;
import com.memetrade.riskcontrol.real.domain.BuyInputs;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.LamportPrice;
import com.memetrade.riskcontrol.real.domain.PricedPosition;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.shared.logging.LogMasking;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (logical-components.md RealGateInputsReader, U3-NFR4.1, U3-NFR6.1, BR6.3, BR7.3): reads what
 * one evaluation needs, only through the public interfaces M1 ({@code botwallet.api}), M2 ({@code ledger.api}), M4
 * ({@code marketdata.api}), the indicator ({@code riskassessment.api}) and the tables of the barrier. Tenant and
 * person come from the order; nothing of another tenant and nothing of the PAPER ledger is ever read.
 *
 * <p>Fail closed: a technical failure of M1, M2 or M4 becomes {@link RealGateUnavailable} with its dependency; the
 * indicator, which never throws by contract, turns any failure into an unavailable assessment (NO_ASSESSMENT). A token
 * the market does not answer is unknown, never zero. The prices of the positions and of the token come in one read.
 */
@Component
public class RealGateInputsReader {

    private static final Logger log = LoggerFactory.getLogger(RealGateInputsReader.class);

    /** The wrapped SOL mint: its quote turns the quotes in dollars into lamports (LamportPrice). */
    static final TokenId WRAPPED_SOL = new TokenId(OrderIntent.SOLANA, "So11111111111111111111111111111111111111112");

    private final RealStateQuery state;
    private final RealLedger ledger;
    private final MarketDataQuery market;
    private final RiskAssessmentQuery assessment;
    private final JdbcRealRiskStore store;

    public RealGateInputsReader(
            RealStateQuery state,
            RealLedger ledger,
            MarketDataQuery market,
            RiskAssessmentQuery assessment,
            JdbcRealRiskStore store) {
        this.state = state;
        this.ledger = ledger;
        this.market = market;
        this.assessment = assessment;
        this.store = store;
    }

    /**
     * The value of a wallet now (BR4.3, BR5.2): its REAL balance and its positions at their quote or cost, and the flows
     * after the photo asked for (none without one), all from the same read.
     */
    public record WalletValue(
            RealBalance balance, List<PricedPosition> positions, long positionsValue, long value, RealFlows flows) {}

    /** M1: the state of the wallet of the person in this tenant. */
    public Optional<RealStateView> wallet(UUID tenantId, UUID userId) {
        return read(Dependency.WALLET, () -> state.readRealState(tenantId, userId));
    }

    /** M4: the kind of the active market source. */
    public SourceKind source() {
        return read(Dependency.MARKET, market::activeSourceKind);
    }

    /** Everything a buy needs after the state and the source, under the lock of the wallet. */
    public BuyInputs buy(OrderIntent intent, RealStateView wallet, Instant now) {
        UUID tenantId = intent.tenantId();
        UUID botWalletId = wallet.botWalletId();
        TokenId token = new TokenId(intent.chain(), intent.mint());
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Optional<DaySnapshot> snapshot = store.snapshot(tenantId, botWalletId, day);
        RealLedgerView ledgerView =
                read(Dependency.LEDGER, () -> ledger.view(tenantId, botWalletId, snapshot.map(DaySnapshot::takenAt)));
        List<RealPosition> open = ledgerView.positions();
        RealBalance balance = ledgerView.balance();
        RealFlows flows = ledgerView.flows();
        Prices prices = prices(open, Set.of(token));
        MarketValue liquidity = read(Dependency.MARKET, () -> market.liquidity(token));
        MarketValue volume = read(Dependency.MARKET, () -> market.volume24h(token));
        return new BuyInputs(
                assess(token),
                store.isBlocked(token.chain(), token.mint()),
                prices.usd().getOrDefault(token, notAnswered()),
                liquidity,
                volume,
                priced(open, prices),
                balance,
                store.openReservedLamports(tenantId, botWalletId),
                store.approvedBuys(tenantId, intent.botId(), day),
                snapshot,
                flows);
    }

    /**
     * F3 and F4: the value of the wallet now and, with a photo, the flows after it, with the same rules as the buy. The
     * balance, the positions and the flows come from ONE read of the REAL ledger (review R-01, {@link RealLedgerView}):
     * the reads in the transaction of the cycle are READ COMMITTED, and the fills of the U4 do not take the lock of the
     * barrier, so separate reads could count a fill or a deposit committed between them twice (or once in the value and
     * once in the flows), whatever their order. One statement shares one snapshot of PostgreSQL.
     */
    public WalletValue value(UUID tenantId, UUID botWalletId, Optional<Instant> flowsSince) {
        RealLedgerView ledgerView = read(Dependency.LEDGER, () -> ledger.view(tenantId, botWalletId, flowsSince));
        List<RealPosition> open = ledgerView.positions();
        List<PricedPosition> positions = priced(open, prices(open, Set.of()));
        long positionsValue = RealRiskPolicy.positionsValue(positions);
        return new WalletValue(
                ledgerView.balance(),
                positions,
                positionsValue,
                RealRiskPolicy.currentValue(ledgerView.balance(), positionsValue),
                ledgerView.flows());
    }

    /** The quotes in dollars of the positions, of the extra tokens and of SOL, and the decimals of the positions. */
    private record Prices(Map<TokenId, MarketValue> usd, Map<TokenId, MarketValue> decimals) {}

    private Prices prices(List<RealPosition> open, Set<TokenId> extra) {
        Set<TokenId> held = new LinkedHashSet<>();
        open.forEach(position -> held.add(new TokenId(OrderIntent.SOLANA, position.mint())));
        Set<TokenId> quoted = new LinkedHashSet<>(held);
        quoted.addAll(extra);
        if (!held.isEmpty()) {
            quoted.add(WRAPPED_SOL);
        }
        Map<TokenId, MarketValue> usd = read(Dependency.MARKET, () -> market.referencePrices(quoted));
        Map<TokenId, MarketValue> decimals =
                held.isEmpty() ? Map.of() : read(Dependency.MARKET, () -> market.decimalsOf(held));
        return new Prices(usd, decimals);
    }

    private static List<PricedPosition> priced(List<RealPosition> open, Prices prices) {
        MarketValue sol = prices.usd().getOrDefault(WRAPPED_SOL, notAnswered());
        return open.stream()
                .map(position -> {
                    TokenId token = new TokenId(OrderIntent.SOLANA, position.mint());
                    return new PricedPosition(
                            position.mint(),
                            position.tokenBaseUnits(),
                            position.costLamports(),
                            LamportPrice.from(
                                    prices.usd().getOrDefault(token, notAnswered()),
                                    sol,
                                    prices.decimals().getOrDefault(token, notAnswered())));
                })
                .toList();
    }

    /** BR2.1: the indicator never throws by contract; if it does, the buy is refused, never approved. */
    private AssessmentResult assess(TokenId token) {
        try {
            AssessmentResult result = assessment.assess(token);
            return result == null ? new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE) : result;
        } catch (RuntimeException failure) {
            log.warn(
                    "REAL barrier: the assessment failed, the buy is refused: mint={}, exceptionClass={}",
                    LogMasking.maskBase58(token.mint()),
                    failure.getClass().getSimpleName());
            return new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE);
        }
    }

    private static MarketValue notAnswered() {
        return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty());
    }

    private static <T> T read(Dependency dependency, Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException failure) {
            throw new RealGateUnavailable(dependency, failure);
        }
    }
}
