package com.memetrade.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.domain.LedgerEntries.Draft;
import com.memetrade.ledger.domain.PositionAccounting.Holding;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.pnl.domain.Mark;
import com.memetrade.pnl.domain.PnlCalculator;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import com.memetrade.pnl.domain.PnlCalculator.TokenInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 8 (NFR4.4): 500 random sequences with a FIXED seed of fund, buys, sells, closes and FEE compensations over
 * three synthetic tokens, kept in memory with the domain rules only. After every operation: balance = sum of the
 * entries and never negative (BR2.5); each position = the replay of its fills (BR2.3); each order's entries = the
 * amounts of its fill (BR2.2, BR8.2) plus its compensations; and the P&L lines sum exactly to the total (BR6.5).
 */
@Tag("u7")
class LedgerPropertyTest {

    private static final long SEED = 20261028L;
    private static final int SEQUENCES = 500;
    private static final int STEPS = 25;
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final SourceRef SOURCE = new SourceRef("sim-prop", "Propriedade", true);
    private static final List<String> TOKENS = List.of("TokA", "TokB", "TokC");

    private final FillCalculator calculator = new FillCalculator(SimulationPolicy.V1);
    private final com.memetrade.pnl.domain.ExitValuation valuation =
            new com.memetrade.pnl.domain.ExitValuation(SimulationPolicy.V1.spreadPct(), SimulationPolicy.V1.feePct());

    @Test
    void fiveHundredSequencesKeepBalancePositionsEntriesAndTotalsCoherent() {
        Random random = new Random(SEED);
        int fills = 0;
        int compensations = 0;
        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            Book book = new Book(new BigDecimal(random.nextInt(1, 5000)).setScale(6));
            for (int step = 0; step < STEPS; step++) {
                String token = TOKENS.get(random.nextInt(TOKENS.size()));
                int action = random.nextInt(10);
                if (action < 5) {
                    fills += book.trade(OrderSide.BUY, token, quantity(random), price(random), liquidity(random));
                } else if (action < 8) {
                    fills += book.trade(OrderSide.SELL, token, quantity(random), price(random), liquidity(random));
                } else if (action == 8) {
                    fills += book.close(token, price(random));
                } else {
                    compensations += book.compensateSomeFee(random);
                }
                book.check(sequence, step);
            }
        }
        assertThat(fills).as("the sequences really fill orders").isGreaterThan(SEQUENCES * 5);
        assertThat(compensations).isGreaterThan(SEQUENCES / 5);
    }

    private static BigDecimal quantity(Random random) {
        return new BigDecimal(random.nextInt(1, 2_000_000)).movePointLeft(random.nextInt(0, 9));
    }

    private static BigDecimal price(Random random) {
        return new BigDecimal(random.nextInt(1, 5_000_000)).movePointLeft(random.nextInt(4, 12));
    }

    private static BigDecimal liquidity(Random random) {
        return new BigDecimal(random.nextInt(1, 10_000_000)).movePointLeft(random.nextInt(0, 6));
    }

    private static MarketInputs market(BigDecimal price, BigDecimal liquidity) {
        return new MarketInputs(
                new MarketValue.Known(price, SOURCE, NOW, false),
                new MarketValue.Known(liquidity, SOURCE, NOW, false),
                new MarketValue.Known(BigDecimal.valueOf(6), SOURCE, NOW, false),
                NOW);
    }

    /** An in-memory ledger of one bot built only from the domain rules. */
    private final class Book {
        private final List<Entry> entries = new ArrayList<>();
        private final List<Fill> fillRecords = new ArrayList<>();
        private final Map<String, Holding> holdings = new HashMap<>();
        private final Map<String, BigDecimal> lastPrice = new HashMap<>();
        private final InMemoryCompensations port = new InMemoryCompensations();

        Book(BigDecimal funding) {
            Draft fund = LedgerEntries.forFunding(funding);
            entries.add(new Entry(UUID.randomUUID(), null, fund.type(), fund.amount(), null));
        }

        BigDecimal balance() {
            return entries.stream().map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        int trade(OrderSide side, String token, BigDecimal quantity, BigDecimal price, BigDecimal liquidity) {
            Holding holding = holdings.getOrDefault(token, Holding.empty());
            FillQuote quote = calculator.quote(side, quantity, holding.quantity(), balance(), market(price, liquidity));
            if (!(quote instanceof FillQuote.Computed computed)) {
                return 0;
            }
            PositionAccounting.Outcome outcome = side == OrderSide.BUY
                    ? PositionAccounting.buy(holding, computed.filledQuantity(), computed.grossAmount())
                    : PositionAccounting.sell(holding, computed.filledQuantity(), computed.grossAmount());
            record(token, side, false, computed, outcome);
            lastPrice.put(token, price);
            return 1;
        }

        int close(String token, BigDecimal price) {
            Holding holding = holdings.getOrDefault(token, Holding.empty());
            if (holding.isEmpty()) {
                return 0;
            }
            FillQuote.Computed computed = (FillQuote.Computed)
                    calculator.closeQuote(holding.quantity(), market(price, new BigDecimal("1000000")));
            record(token, OrderSide.SELL, true, computed, PositionAccounting.close(holding, computed.grossAmount()));
            lastPrice.put(token, price);
            return 1;
        }

        int compensateSomeFee(Random random) {
            List<Entry> fees = entries.stream()
                    .filter(e -> e.type() == EntryType.FEE && !port.compensated.contains(e.entryId()))
                    .toList();
            if (fees.isEmpty()) {
                return 0;
            }
            Entry fee = fees.get(random.nextInt(fees.size()));
            port.current = this;
            new LedgerCompensation(port).compensate(fee.entryId(), "property");
            return 1;
        }

        private void record(
                String token,
                OrderSide side,
                boolean close,
                FillQuote.Computed computed,
                PositionAccounting.Outcome outcome) {
            UUID orderId = UUID.randomUUID();
            List<Draft> drafts = close
                    ? LedgerEntries.forClose(computed)
                    : side == OrderSide.BUY ? LedgerEntries.forBuy(computed) : LedgerEntries.forSell(computed);
            for (Draft draft : drafts) {
                entries.add(new Entry(UUID.randomUUID(), orderId, draft.type(), draft.amount(), null));
            }
            fillRecords.add(new Fill(
                    orderId, token, side, close, computed, outcome.realized().orElse(BigDecimal.ZERO)));
            holdings.put(token, outcome.after());
        }

        void check(int sequence, int step) {
            String where = "sequence " + sequence + ", step " + step;
            assertThat(balance().signum())
                    .as("balance never negative, " + where)
                    .isGreaterThanOrEqualTo(0);
            for (String token : TOKENS) {
                Holding replayed = Holding.empty();
                for (Fill fill : fillRecords) {
                    if (!fill.token().equals(token)) {
                        continue;
                    }
                    BigDecimal filled = fill.quote().filledQuantity();
                    BigDecimal gross = fill.quote().grossAmount();
                    replayed = fill.close()
                            ? PositionAccounting.close(replayed, gross).after()
                            : fill.side() == OrderSide.BUY
                                    ? PositionAccounting.buy(replayed, filled, gross)
                                            .after()
                                    : PositionAccounting.sell(replayed, filled, gross)
                                            .after();
                }
                Holding kept = holdings.getOrDefault(token, Holding.empty());
                assertThat(kept.quantity()).as("position = replay, " + where).isEqualByComparingTo(replayed.quantity());
                assertThat(kept.costBasis()).as("cost = replay, " + where).isEqualByComparingTo(replayed.costBasis());
                assertThat(kept.quantity().signum() == 0)
                        .isEqualTo(kept.costBasis().signum() == 0);
            }
            for (Fill fill : fillRecords) {
                BigDecimal expected = fill.side() == OrderSide.BUY && !fill.close()
                        ? fill.quote().grossAmount().add(fill.quote().fee()).negate()
                        : fill.quote().grossAmount().subtract(fill.quote().fee());
                BigDecimal compensations = BigDecimal.ZERO;
                BigDecimal found = BigDecimal.ZERO;
                for (Entry entry : entries) {
                    if (fill.orderId().equals(entry.orderId())) {
                        found = found.add(entry.amount());
                    }
                    if (entry.type() == EntryType.COMPENSATION && fill.orderId().equals(entry.compensatedOrder())) {
                        compensations = compensations.add(entry.amount());
                    }
                }
                assertThat(found.add(compensations))
                        .as("order entries = fill amounts (BR8.2), " + where)
                        .isEqualByComparingTo(expected.add(compensations));
            }
            checkTotals(where);
        }

        private void checkTotals(String where) {
            List<TokenInput> inputs = new ArrayList<>();
            for (String token : TOKENS) {
                BigDecimal realized = fillRecords.stream()
                        .filter(f -> f.token().equals(token))
                        .map(Fill::realized)
                        .reduce(BigDecimal.ZERO.setScale(6), BigDecimal::add);
                BigDecimal fees = BigDecimal.ZERO.setScale(6);
                for (Entry entry : entries) {
                    boolean ofToken = fillRecords.stream()
                            .anyMatch(f -> f.token().equals(token)
                                    && (f.orderId().equals(entry.orderId())
                                            || f.orderId().equals(entry.compensatedOrder())));
                    if (ofToken && (entry.type() == EntryType.FEE || entry.type() == EntryType.COMPENSATION)) {
                        fees = fees.subtract(entry.amount());
                    }
                }
                Holding holding = holdings.getOrDefault(token, Holding.empty());
                Optional<Mark> mark = holding.isEmpty()
                        ? Optional.empty()
                        : Optional.of(valuation.value(
                                holding.quantity(),
                                holding.costBasis(),
                                new MarketValue.Known(lastPrice.get(token), SOURCE, NOW, false),
                                new MarketValue.Known(new BigDecimal("1000000"), SOURCE, NOW, false),
                                NOW));
                inputs.add(new TokenInput("solana", token, realized, fees, mark));
            }
            PnlCalculator.Report report = PnlCalculator.report(inputs);
            assertThat(report.realized())
                    .as("sum of the lines = total, " + where)
                    .isEqualTo(report.lines().stream()
                            .map(PnlCalculator.Line::realized)
                            .reduce(BigDecimal.ZERO.setScale(6), BigDecimal::add));
            assertThat(report.fees().signum())
                    .as("fees never negative, " + where)
                    .isGreaterThanOrEqualTo(0);
            if (report.unrealized() instanceof Amount.Known total) {
                assertThat(total.value())
                        .isEqualTo(report.lines().stream()
                                .map(line -> ((Amount.Known) line.unrealized()).value())
                                .reduce(BigDecimal.ZERO.setScale(6), BigDecimal::add));
            }
        }
    }

    private record Entry(UUID entryId, UUID orderId, EntryType type, BigDecimal amount, UUID compensatedOrder) {}

    private record Fill(
            UUID orderId, String token, OrderSide side, boolean close, FillQuote.Computed quote, BigDecimal realized) {}

    /** The port over the book under test: the compensation entry keeps the order of the FEE it corrects. */
    private static final class InMemoryCompensations implements CompensationPort {
        private final List<UUID> compensated = new ArrayList<>();
        private Book current;

        @Override
        public Optional<OriginalEntry> find(UUID entryId) {
            return current.entries.stream()
                    .filter(e -> e.entryId().equals(entryId))
                    .findFirst()
                    .map(e -> new OriginalEntry(e.entryId(), UUID.randomUUID(), e.type(), e.amount(), "order"));
        }

        @Override
        public boolean alreadyCompensated(UUID entryId) {
            return compensated.contains(entryId);
        }

        @Override
        public UUID record(OriginalEntry original, BigDecimal amount, String reason) {
            UUID order = current.entries.stream()
                    .filter(e -> e.entryId().equals(original.entryId()))
                    .findFirst()
                    .orElseThrow()
                    .orderId();
            compensated.add(original.entryId());
            UUID id = UUID.randomUUID();
            current.entries.add(new Entry(id, null, EntryType.COMPENSATION, amount, order));
            return id;
        }
    }
}
