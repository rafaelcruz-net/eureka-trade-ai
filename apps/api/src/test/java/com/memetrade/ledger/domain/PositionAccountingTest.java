package com.memetrade.ledger.domain;

import static com.memetrade.ledger.domain.LedgerScenarioCatalogTest.d;
import static com.memetrade.ledger.domain.LedgerScenarioCatalogTest.market;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.domain.LedgerEntries.Draft;
import com.memetrade.ledger.domain.PositionAccounting.Holding;
import com.memetrade.ledger.domain.PositionAccounting.Outcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 6 (written before the code): the decomposition of BR2.2, the average cost kept as total cost (BR2.3), the
 * restart of the cost (BR2.4), the close at the reference price (BR5.1) and the compensation of a FEE only (BR4.2),
 * with the numbers of C1, C3, C4, C7, C11, C12 and C13 compared by exact equality.
 */
@Tag("u7")
class PositionAccountingTest {

    private final FillCalculator calculator = new FillCalculator(SimulationPolicy.V1);

    private FillQuote.Computed quote(OrderSide side, String quantity, String position, String price) {
        return (FillQuote.Computed)
                calculator.quote(side, d(quantity), d(position), d("1000000"), market(price, "100000.000000", 6));
    }

    private static BigDecimal sum(List<Draft> drafts) {
        return drafts.stream().map(Draft::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void c1TheBuyEntriesDecomposeTheCostAndSumToMinusGrossPlusFee() {
        FillQuote.Computed buy = quote(OrderSide.BUY, "100", "0", "1.000000");
        List<Draft> entries = LedgerEntries.forBuy(buy);

        assertThat(entries)
                .containsExactly(
                        new Draft(EntryType.TRADE_COST, d("-100.000000")),
                        new Draft(EntryType.SLIPPAGE, d("-0.600000")),
                        new Draft(EntryType.FEE, d("-0.301800")));
        assertThat(sum(entries)).isEqualTo(buy.grossAmount().add(buy.fee()).negate());
    }

    @Test
    void c1TheSellEntriesSumToGrossMinusFeeAndRealizeTheGain() {
        FillQuote.Computed sell = quote(OrderSide.SELL, "100", "100", "1.500000");
        List<Draft> entries = LedgerEntries.forSell(sell);

        assertThat(entries)
                .containsExactly(
                        new Draft(EntryType.TRADE_PROCEEDS, d("150.000000")),
                        new Draft(EntryType.SLIPPAGE, d("-0.975000")),
                        new Draft(EntryType.FEE, d("-0.447075")));
        assertThat(sum(entries)).isEqualTo(d("148.577925"));

        Outcome outcome = PositionAccounting.sell(new Holding(d("100"), d("100.600000")), d("100"), d("149.025000"));
        assertThat(outcome.realized()).contains(d("48.425000"));
        assertThat(outcome.costRemoved()).isEqualTo(d("100.600000"));
        assertThat(outcome.after().quantity().signum()).isZero();
        assertThat(outcome.after().costBasis()).isEqualTo(d("0.000000"));
    }

    @Test
    void c1AndC4TheFeeStaysOutOfTheCostAndTheBalanceEndsAt1047676125() {
        Outcome buy = PositionAccounting.buy(Holding.empty(), d("100"), d("100.600000"));
        assertThat(buy.after().costBasis()).as("the fee is not in the cost").isEqualTo(d("100.600000"));
        assertThat(buy.after().quantity()).isEqualByComparingTo("100");
        assertThat(buy.realized()).isEmpty();

        BigDecimal balance = d("1000.000000")
                .add(sum(LedgerEntries.forBuy(quote(OrderSide.BUY, "100", "0", "1.000000"))))
                .add(sum(LedgerEntries.forSell(quote(OrderSide.SELL, "100", "100", "1.500000"))));
        assertThat(balance).isEqualTo(d("1047.676125"));
        BigDecimal fees = d("0.301800").add(d("0.447075"));
        assertThat(fees).isEqualTo(d("0.748875"));
        assertThat(d("48.425000").subtract(fees)).as("net result").isEqualTo(d("47.676125"));
    }

    @Test
    void c3TwoBuysAndAPartialSellRemoveTheProportionalCost() {
        Holding first = PositionAccounting.buy(Holding.empty(), d("100"), d("100.600000"))
                .after();
        Holding second =
                PositionAccounting.buy(first, d("100"), d("201.400000")).after();
        assertThat(second.quantity()).isEqualByComparingTo("200");
        assertThat(second.costBasis()).isEqualTo(d("302.000000"));

        Outcome sell = PositionAccounting.sell(second, d("50"), d("149.025000"));
        assertThat(sell.costRemoved()).isEqualTo(d("75.500000"));
        assertThat(sell.realized()).contains(d("73.525000"));
        assertThat(sell.after().quantity()).isEqualByComparingTo("150");
        assertThat(sell.after().costBasis()).isEqualTo(d("226.500000"));
    }

    @Test
    void c7AZeroedPositionRestartsTheCost() {
        Holding bought = PositionAccounting.buy(Holding.empty(), d("100"), d("100.600000"))
                .after();
        Outcome sold = PositionAccounting.sell(bought, d("100"), d("99.400000"));
        assertThat(sold.realized()).contains(d("-1.200000"));
        assertThat(sold.after().quantity().signum()).isZero();
        assertThat(sold.after().costBasis()).isEqualTo(d("0.000000"));

        Holding again =
                PositionAccounting.buy(sold.after(), d("10"), d("40.216000")).after();
        assertThat(again.quantity()).isEqualByComparingTo("10");
        assertThat(again.costBasis()).isEqualTo(d("40.216000"));
    }

    @Test
    void c11ASellLimitedToThePositionRealizesLikeC1() {
        FillQuote.Computed sell = quote(OrderSide.SELL, "150", "100", "1.500000");
        Outcome outcome = PositionAccounting.sell(
                new Holding(d("100"), d("100.600000")), sell.filledQuantity(), sell.grossAmount());
        assertThat(outcome.realized()).contains(d("48.425000"));
        assertThat(outcome.after().quantity().signum()).isZero();
    }

    @Test
    void c12TheCloseWritesCloseAndFeeAndRealizesAgainstTheWholeCost() {
        FillQuote.Computed close =
                (FillQuote.Computed) calculator.closeQuote(d("100"), market("1.200000", "100000.000000", 6));
        assertThat(LedgerEntries.forClose(close))
                .containsExactly(new Draft(EntryType.CLOSE, d("120.000000")), new Draft(EntryType.FEE, d("-0.360000")));

        Outcome outcome = PositionAccounting.close(new Holding(d("100"), d("100.600000")), close.grossAmount());
        assertThat(outcome.realized()).contains(d("19.400000"));
        assertThat(outcome.after().quantity().signum()).isZero();
        assertThat(outcome.after().costBasis()).isEqualTo(d("0.000000"));
    }

    /**
     * Regression of the defect found by LedgerPropertyTest: a partial sell whose removed cost rounds to the whole cost
     * (0.000001 x 999999 / 1000000 = 0.000000999999 -> 0.000001) left quantity above zero with cost zero, which the
     * database refuses (position_zero_together_ck). The smallest USDC unit stays with the open quantity.
     */
    @Test
    void aPartialSellNeverLeavesAnOpenQuantityWithoutCost() {
        Holding before = new Holding(d("1000000"), d("0.000001"));

        PositionAccounting.Outcome outcome = PositionAccounting.sell(before, d("999999"), d("0.000002"));

        assertThat(outcome.after().quantity()).isEqualByComparingTo("1");
        assertThat(outcome.after().costBasis()).isEqualTo(d("0.000001"));
        assertThat(outcome.costRemoved()).isEqualTo(d("0.000000"));
        assertThat(outcome.realized()).contains(d("0.000002"));
    }

    @Test
    void zeroEntriesAreNeverWritten() {
        // A fill without slippage in money: 0.000001 token at 2 with no impact - TRADE_COST and gross both 0.000002.
        FillQuote.Computed tiny = (FillQuote.Computed) calculator.closeQuote(d("0.000001"), market("2", "100000", 6));
        assertThat(LedgerEntries.forClose(tiny))
                .as("the fee of 0.000002 rounds to zero and is not written")
                .containsExactly(new Draft(EntryType.CLOSE, d("0.000002")));
    }

    @Test
    void theFundingIsOnePositiveEntryWithScaleSix() {
        assertThat(LedgerEntries.forFunding(d("1000"))).isEqualTo(new Draft(EntryType.FUNDING, d("1000.000000")));
        assertThatThrownBy(() -> LedgerEntries.forFunding(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerEntries.forFunding(d("1.0000001")))
                .as("more than 6 decimals is not a USDC amount")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void c13TheCompensationOfAFeeIsItsNegativeAndHappensOnce() {
        InMemoryPort port = new InMemoryPort();
        UUID fee = port.add(EntryType.FEE, "-0.301800");
        LedgerCompensation compensation = new LedgerCompensation(port);

        UUID entry = compensation.compensate(fee, "test");

        assertThat(port.recorded).containsExactly(d("0.301800"));
        assertThat(entry).isNotNull();
        assertThat(d("899.098200").add(port.recorded.getFirst())).isEqualTo(d("899.400000"));
        assertThatThrownBy(() -> compensation.compensate(fee, "again"))
                .isInstanceOf(LedgerCompensation.CompensationRefused.class);
    }

    @Test
    void inTheBetaOnlyAFeeIsCompensatedAndAnUnknownEntryIsRefused() {
        InMemoryPort port = new InMemoryPort();
        LedgerCompensation compensation = new LedgerCompensation(port);

        for (EntryType type :
                List.of(EntryType.TRADE_COST, EntryType.SLIPPAGE, EntryType.FUNDING, EntryType.COMPENSATION)) {
            UUID entry = port.add(type, "-1.000000");
            assertThatThrownBy(() -> compensation.compensate(entry, "x"))
                    .as(type.name())
                    .isInstanceOf(LedgerCompensation.CompensationRefused.class);
        }
        assertThatThrownBy(() -> compensation.compensate(UUID.randomUUID(), "x"))
                .isInstanceOf(LedgerCompensation.CompensationRefused.class);
        assertThat(port.recorded).isEmpty();
    }

    /** In-memory port: only the facts the rule reads. */
    static final class InMemoryPort implements CompensationPort {
        final Map<UUID, OriginalEntry> entries = new HashMap<>();
        final List<UUID> compensated = new ArrayList<>();
        final List<BigDecimal> recorded = new ArrayList<>();

        UUID add(EntryType type, String amount) {
            UUID id = UUID.randomUUID();
            entries.put(
                    id,
                    new OriginalEntry(
                            id,
                            UUID.randomUUID(),
                            type,
                            d(amount),
                            UUID.randomUUID().toString()));
            return id;
        }

        @Override
        public Optional<OriginalEntry> find(UUID entryId) {
            return Optional.ofNullable(entries.get(entryId));
        }

        @Override
        public boolean alreadyCompensated(UUID entryId) {
            return compensated.contains(entryId);
        }

        @Override
        public UUID record(OriginalEntry original, BigDecimal amount, String reason) {
            compensated.add(original.entryId());
            recorded.add(amount);
            return UUID.randomUUID();
        }
    }
}
