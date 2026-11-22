package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.real.RealPnlCalculator;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 261121-paper-real-unico U1, Step 11, WRITTEN BEFORE THE CODE (C10 first implementation; BR5.2, BR5.4, BR5.5, BR5.8,
 * NFR2.3, NFR4.4; team.md "P&L com cenários determinísticos"): known-result scenarios in lamports.
 *
 * <p>SOL at US$ 100 and the token at US$ 0.012 with 6 decimals: {@code 0.012 x 10^9 / (100 x 10^6) = 0.12} lamports per
 * base unit.
 *
 * <ul>
 *   <li>bot A buys 1,000,000 lamports for 10,000,000 units (fee 5,000; cost 1,005,000), then sells 5,000,000 units for
 *       600,000 (fee 5,000): realized {@code 600,000 - 5,000 - ceil(1,005,000 x 5/10) = 92,500}; the 5,000,000 units
 *       left cost 502,500 and are worth 600,000: unrealized 97,500; total 190,000; fees 10,000;
 *   <li>bot B buys 2,000,000 for 20,000,000 units (fee 5,000) and has a failed order (fee 5,000): realized -5,000; the
 *       units cost 2,005,000 and are worth 2,400,000: unrealized 395,000; total 390,000; fees 10,000;
 *   <li>an order without bot (a liquidation) enters only the total of the mode, never a line.
 * </ul>
 */
class RealBotPnlQueryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BOT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String MINT = "TokenMint1111111111111111111111111111111111";
    private static final String UNPRICED = "NoPrice111111111111111111111111111111111111";
    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");

    private final RealExecutionPort execution = mock(RealExecutionPort.class);
    private final MarketDataQuery market = mock(MarketDataQuery.class);
    private final BotDirectory directory = mock(BotDirectory.class);
    private final List<RealOrderView> orders = new ArrayList<>();
    private RealBotPnlQueryV1 query;
    private RealPnlService today;

    @BeforeEach
    void setUp() {
        today = new RealPnlService(execution, market);
        query = new RealBotPnlQueryV1(
                execution, today, directory, PnlByModeProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(execution.settledOrders(eq(TENANT), eq(USER), anyInt())).thenAnswer(call -> {
            int max = call.getArgument(2);
            return orders.size() <= max ? List.copyOf(orders) : List.copyOf(orders.subList(0, max));
        });
        when(execution.settledOrders(TENANT, USER)).thenAnswer(call -> List.copyOf(orders));
        when(directory.botsOf(TENANT, USER, PnlMarket.REAL))
                .thenReturn(List.of(
                        entry(BOT_A, "Robo A", BotPnlStatus.ACTIVE), entry(BOT_B, "Robo B", BotPnlStatus.REMOVED)));
        Map<TokenId, MarketValue> quotes = new HashMap<>();
        quotes.put(new TokenId("solana", WSOL), known("100"));
        quotes.put(new TokenId("solana", MINT), known("0.012"));
        when(market.referencePrices(anySet())).thenReturn(quotes);
        Map<TokenId, MarketValue> decimals = new HashMap<>();
        decimals.put(new TokenId("solana", MINT), known("6"));
        decimals.put(new TokenId("solana", UNPRICED), known("6"));
        when(market.decimalsOf(anySet())).thenReturn(decimals);
    }

    @Test
    void eachBotHasOnlyItsOwnOrdersWithTheFeesAlreadyInTheRealizedAndTheTotalIsRealizedPlusUnrealized() {
        scenario();

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.lines()).extracting(RealBotPnlLine::botId).containsExactly(BOT_A, BOT_B);
        RealBotPnlLine a = pnl.lines().get(0);
        assertThat(a.realizedLamports()).isEqualTo(92_500L);
        assertThat(a.unrealized()).isEqualTo(new LamportsValue.Known(97_500L));
        assertThat(a.feesLamports()).isEqualTo(10_000L);
        assertThat(a.total()).isEqualTo(new LamportsValue.Known(190_000L));
        assertThat(a.status()).isEqualTo(BotPnlStatus.ACTIVE);
        RealBotPnlLine b = pnl.lines().get(1);
        assertThat(b.realizedLamports()).isEqualTo(-5_000L);
        assertThat(b.unrealized()).isEqualTo(new LamportsValue.Known(395_000L));
        assertThat(b.feesLamports()).isEqualTo(10_000L);
        assertThat(b.total()).isEqualTo(new LamportsValue.Known(390_000L));
        assertThat(b.status()).isEqualTo(BotPnlStatus.REMOVED);
    }

    @Test
    void theTotalOfTheModeIsTodaysCalculationOverEveryOrderOfThePersonOrdersWithoutBotIncluded() {
        scenario();
        RealPnlCalculator.Result expected = today.pnl(TENANT, USER);

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.total().realizedLamports()).isEqualTo(expected.realizedLamports());
        assertThat(pnl.total().feesLamports())
                .isEqualTo(expected.feesLamports())
                .isEqualTo(25_000L);
        long unrealized = expected.unrealizedLamports().orElseThrow();
        assertThat(pnl.total().unrealized()).isEqualTo(new LamportsValue.Known(unrealized));
        assertThat(pnl.total().total())
                .isEqualTo(new LamportsValue.Known(Math.addExact(expected.realizedLamports(), unrealized)));
        assertThat(pnl.total().currency()).isEqualTo("SOL");
        assertThat(pnl.total().unrealizedAsOf()).isEqualTo(NOW);
        assertThat(pnl.empty()).isFalse();
    }

    @Test
    void inTheU1TheUnownedLineIsEmptyAndNoBotHasProratedShares() {
        scenario();

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.unowned()).isEmpty();
        assertThat(pnl.lines()).allSatisfy(line -> assertThat(line.shares()).isEmpty());
    }

    @Test
    void anUnknownPriceMakesTheUnrealizedAndTheTotalUnknownNeverZeroAndTheRealizedStaysExact() {
        orders.add(buy(BOT_A, UNPRICED, 1_000_000, 10_000_000, 5_000));

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        RealBotPnlLine a = pnl.lines().get(0);
        assertThat(a.unrealized()).isEqualTo(new LamportsValue.Unknown(LamportsValue.UnknownReason.PRICE_UNKNOWN));
        assertThat(a.total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(a.realizedLamports()).isZero();
        assertThat(a.feesLamports()).isEqualTo(5_000L);
        assertThat(pnl.total().total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(pnl.lines().get(1).total()).isEqualTo(new LamportsValue.Known(0L));
    }

    @Test
    void moreSettledOrdersThanTheLimitMakeTheBlockUnavailableWithoutAPartialNumber() {
        query = new RealBotPnlQueryV1(
                execution, today, directory, new PnlByModeProperties(null, null, null, 15_000), Clock.systemUTC());
        for (int i = 0; i < 15_001; i++) {
            orders.add(failed(null, MINT, 1));
        }

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class)
                .satisfies(failure ->
                        assertThat(((RealPnlUnavailable) failure).causeLabel()).isEqualTo("TOO_MANY_ORDERS"));
    }

    @Test
    void aFailedReadIsRealPnlUnavailable() {
        when(execution.settledOrders(eq(TENANT), eq(USER), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all())).isInstanceOf(RealPnlUnavailable.class);
    }

    @Test
    void withoutRealBotsNorOrdersTheBlockIsEmpty() {
        when(directory.botsOf(TENANT, USER, PnlMarket.REAL)).thenReturn(List.of());

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.empty()).isTrue();
        assertThat(pnl.lines()).isEmpty();
        assertThat(pnl.total().total()).isEqualTo(new LamportsValue.Known(0L));
    }

    @Test
    void theLineOfOneBotIsFoundOnlyForARealBotOfThePerson() {
        scenario();
        UUID paper = UUID.randomUUID();
        when(directory.botOf(TENANT, USER, BOT_A)).thenReturn(Optional.of(entry(BOT_A, "Robo A", BotPnlStatus.ACTIVE)));
        when(directory.botOf(TENANT, USER, paper))
                .thenReturn(Optional.of(
                        new BotEntry(paper, "Simulado", PnlMarket.PAPER, "MOMENTUM_VOLUME", BotPnlStatus.ACTIVE, NOW)));
        when(directory.botOf(TENANT, USER, BOT_B)).thenReturn(Optional.empty());

        assertThat(query.botPnl(TENANT, USER, BOT_A, PnlWindow.all()))
                .get()
                .extracting(RealBotPnlLine::total)
                .isEqualTo(new LamportsValue.Known(190_000L));
        assertThat(query.botPnl(TENANT, USER, paper, PnlWindow.all())).isEmpty();
        assertThat(query.botPnl(TENANT, USER, BOT_B, PnlWindow.all())).isEmpty();
    }

    private void scenario() {
        orders.add(buy(BOT_A, MINT, 1_000_000, 10_000_000, 5_000));
        orders.add(buy(BOT_B, MINT, 2_000_000, 20_000_000, 5_000));
        orders.add(sell(BOT_A, MINT, 5_000_000, 600_000, 5_000));
        orders.add(failed(BOT_B, MINT, 5_000));
        orders.add(buy(null, MINT, 1_000_000, 10_000_000, 5_000));
    }

    private static BotEntry entry(UUID botId, String name, BotPnlStatus status) {
        return new BotEntry(botId, name, PnlMarket.REAL, "MOMENTUM_VOLUME", status, NOW.minusSeconds(3600));
    }

    private static MarketValue known(String value) {
        return new MarketValue.Known(new BigDecimal(value), new SourceRef("test", "Teste", true), NOW, false);
    }

    private static RealOrderView buy(UUID bot, String mint, long lamports, long out, long fee) {
        return order(bot, RealSide.BUY, mint, Optional.of(lamports), Optional.empty(), Optional.of(out), fee, true);
    }

    private static RealOrderView sell(UUID bot, String mint, long units, long received, long fee) {
        return order(bot, RealSide.SELL, mint, Optional.empty(), Optional.of(units), Optional.of(received), fee, true);
    }

    private static RealOrderView failed(UUID bot, String mint, long fee) {
        return order(bot, RealSide.SELL, mint, Optional.empty(), Optional.of(1L), Optional.empty(), fee, false);
    }

    private static RealOrderView order(
            UUID bot,
            RealSide side,
            String mint,
            Optional<Long> inputLamports,
            Optional<Long> inputUnits,
            Optional<Long> out,
            long fee,
            boolean confirmed) {
        return new RealOrderView(
                UUID.randomUUID(),
                Optional.ofNullable(bot),
                bot == null ? Optional.of(UUID.randomUUID()) : Optional.empty(),
                bot == null ? "LIQUIDATION" : "BOT",
                side,
                mint,
                inputLamports,
                inputUnits,
                Optional.empty(),
                Optional.empty(),
                out,
                "AUTO_FULL",
                confirmed ? RealOrderState.CONFIRMED : RealOrderState.FAILED,
                Optional.empty(),
                Optional.of("sig"),
                Optional.of(fee),
                Optional.of("BOT_WALLET"),
                Optional.empty(),
                NOW,
                NOW,
                Optional.empty());
    }
}
