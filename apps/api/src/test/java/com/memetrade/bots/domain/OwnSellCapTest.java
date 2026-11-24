package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.ExitReason;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 2 (written before the code; BR1.2, BR1.4, BR1.5, BR3.1, NFR2.4, NFR2.6; E3, E4, E6,
 * E10): the cut of a SELL of the REAL cycle to the quantity available of the bot itself, a pure function. The mark of the
 * bot is checked BEFORE the quantity; an unavailable read never sells; the request is the floor of the quantity of the
 * decision in base units, in exact arithmetic (an overflow is a technical failure, never a truncated value).
 */
class OwnSellCapTest {

    /** A synthetic mint of 6 decimals (1 XYZ = 1,000,000 base units); never a real one. */
    private static final String XYZ = "XyZ1111111111111111111111111111111111111111";

    private static final int DECIMALS = 6;
    private static final long MARK = 17;

    @Test
    void e3ARequestAboveTheAvailableIsCutToTheAvailable() {
        OwnSellCap.Verdict verdict = OwnSellCap.verdict(sell("150"), known(100_000_000L, 0), MARK, MARK, DECIMALS);

        assertThat(verdict).isEqualTo(new OwnSellCap.Verdict.Sell(100_000_000L, 150_000_000L, true));
    }

    @Test
    void e4ARequestBelowTheAvailableIsSoldAsAskedAndNotCapped() {
        OwnSellCap.Verdict verdict = OwnSellCap.verdict(sell("40"), known(100_000_000L, 0), MARK, MARK, DECIMALS);

        assertThat(verdict).isEqualTo(new OwnSellCap.Verdict.Sell(40_000_000L, 40_000_000L, false));
    }

    @Test
    void e6NothingAvailableBecauseEverythingIsOpenIsNoOwnQuantity() {
        // E6: own 100 XYZ, an AMBIGUOUS sale of 100 XYZ: available 0.
        OwnSellCap.Verdict verdict =
                OwnSellCap.verdict(sell("100"), known(100_000_000L, 100_000_000L), MARK, MARK, DECIMALS);

        assertThat(verdict).isEqualTo(new OwnSellCap.Verdict.NoOwnQuantity(100_000_000L));
    }

    @Test
    void e10OpenSalesAboveTheOwnQuantityGiveZeroNeverANegativeSale() {
        OwnSellCap.Verdict verdict =
                OwnSellCap.verdict(sell("30"), known(30_000_000L, 50_000_000L), MARK, MARK, DECIMALS);

        assertThat(verdict).isInstanceOf(OwnSellCap.Verdict.NoOwnQuantity.class);
    }

    @Test
    void aMintOutsideTheOwnPositionsHasZeroAvailable() {
        OwnHoldings other =
                new OwnHoldings.Known(List.of(new OwnPosition("Other1111111111111111111111111111111111111", 5, 5, 5)));

        assertThat(OwnSellCap.verdict(sell("1"), other, MARK, MARK, DECIMALS))
                .isEqualTo(new OwnSellCap.Verdict.NoOwnQuantity(1_000_000L));
    }

    @Test
    void aChangedMarkIsStaleSnapshotBeforeTheQuantityIsLookedAt() {
        // C1: the mark moved between the read and the lock; with the whole quantity available it still does not sell.
        OwnSellCap.Verdict verdict = OwnSellCap.verdict(sell("100"), known(100_000_000L, 0), MARK, MARK + 1, DECIMALS);

        assertThat(verdict).isEqualTo(new OwnSellCap.Verdict.Unavailable(OwnQuantityReason.STALE_SNAPSHOT));
    }

    @Test
    void anUnavailableReadNeverSellsAndKeepsItsReason() {
        for (OwnQuantityReason reason : List.of(
                OwnQuantityReason.BUDGET_EXCEEDED, OwnQuantityReason.READ_FAILED, OwnQuantityReason.BOT_NOT_OWNED)) {
            assertThat(OwnSellCap.verdict(sell("1"), new OwnHoldings.Unavailable(reason), MARK, MARK, DECIMALS))
                    .isEqualTo(new OwnSellCap.Verdict.Unavailable(reason));
        }
    }

    @Test
    void aBotThatRunsWithoutTheReadIsReadFailed() {
        assertThat(OwnSellCap.verdict(sell("1"), new OwnHoldings.NotRead(), MARK, MARK, DECIMALS))
                .isEqualTo(new OwnSellCap.Verdict.Unavailable(OwnQuantityReason.READ_FAILED));
    }

    @Test
    void aDecisionThatIsNotASellIsNotApplicable() {
        DecisionOutcome buy = new DecisionOutcome(
                DecisionAction.BUY,
                Optional.of(token()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new BigDecimal("5")),
                List.of(),
                "compra",
                Instant.EPOCH);
        DecisionOutcome noTrade = new DecisionOutcome(
                DecisionAction.NO_TRADE,
                Optional.empty(),
                Optional.of(NoTradeReason.NO_TOKENS),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                "nada",
                Instant.EPOCH);

        assertThat(OwnSellCap.verdict(buy, new OwnHoldings.NotRead(), MARK, MARK + 9, DECIMALS))
                .isInstanceOf(OwnSellCap.Verdict.NotApplicable.class);
        assertThat(OwnSellCap.verdict(noTrade, known(1, 0), MARK, MARK, DECIMALS))
                .isInstanceOf(OwnSellCap.Verdict.NotApplicable.class);
    }

    @Test
    void theRequestIsTheFloorOfTheQuantityInBaseUnits() {
        assertThat(OwnSellCap.requestedBaseUnits(new BigDecimal("0.0000019"), 6))
                .isEqualTo(1L);
        assertThat(OwnSellCap.requestedBaseUnits(new BigDecimal("0.0000009"), 6))
                .isZero();
        assertThat(OwnSellCap.requestedBaseUnits(new BigDecimal("100"), 6)).isEqualTo(100_000_000L);
    }

    @Test
    void anOverflowOfTheRequestIsAnArithmeticExceptionNeverATruncatedValue() {
        assertThatThrownBy(() -> OwnSellCap.requestedBaseUnits(new BigDecimal("1e20"), 9))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> OwnSellCap.verdict(sell("100000000000000"), known(1, 0), MARK, MARK, 9))
                .isInstanceOf(ArithmeticException.class);
    }

    private static OwnHoldings known(long own, long open) {
        long available = Math.max(0, own - open);
        return new OwnHoldings.Known(List.of(new OwnPosition(XYZ, own, own, available)));
    }

    private static DecisionOutcome sell(String quantity) {
        return new DecisionOutcome(
                DecisionAction.SELL,
                Optional.of(token()),
                Optional.empty(),
                Optional.of(ExitReason.MAX_HOLD),
                Optional.of(new BigDecimal(quantity)),
                List.of(),
                "saida por tempo",
                Instant.EPOCH);
    }

    private static TokenId token() {
        return new TokenId("solana", XYZ);
    }
}
