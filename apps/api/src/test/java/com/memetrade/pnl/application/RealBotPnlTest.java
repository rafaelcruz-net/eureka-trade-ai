package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.infra.PnlRealProperties;
import com.memetrade.pnl.real.Holder;
import com.memetrade.pnl.real.OrderAttribution;
import com.memetrade.pnl.real.PnlScenario;
import com.memetrade.pnl.real.RealCostBasis;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.shared.jdbc.ReadDeadline;
import com.memetrade.shared.jdbc.ReadDeadlineExceeded;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; F1, F5, BR10.1, BR8.6, BR8.7, BR3.4, NFR1.13, F-2): the
 * new implementation of C10 over doubles of its ports: one pass by person (the REAL bots of the directory, the settled
 * orders in a stream to a compact index, the trading rows of the wallets in network order), the prices read ONCE and
 * only for the mints with an open position, the empty block, the cap of orders counted in the stream and the paths of
 * failure. Tenant and person always from the caller.
 */
class RealBotPnlTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final Holder A =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000a"), Instant.parse("2026-11-01T10:00:00Z"));
    private static final Holder B =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000b"), Instant.parse("2026-11-02T10:00:00Z"));
    private static final Set<RealEntryKind> TRADING = EnumSet.of(
            RealEntryKind.SWAP_FILL_IN, RealEntryKind.SWAP_FILL_OUT, RealEntryKind.NETWORK_FEE, RealEntryKind.RENT);

    private final RealLedger ledger = mock(RealLedger.class);
    private final RealExecutionPort execution = mock(RealExecutionPort.class);
    private final BotDirectory directory = mock(BotDirectory.class);
    private final RealStateQuery wallets = mock(RealStateQuery.class);
    private final RealLamportPrices prices = mock(RealLamportPrices.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReconciliationAuditGate gate = mock(ReconciliationAuditGate.class);
    private final java.util.concurrent.atomic.AtomicInteger audits = new java.util.concurrent.atomic.AtomicInteger();
    private final ReconciliationMismatch mismatch = new ReconciliationMismatch(() -> {
        audits.incrementAndGet();
        return "true";
    });
    private final List<RealOrderView> extraOrders = new ArrayList<>();

    /** The implementation over doubles fed by {@code scenario} (also the fixture of {@code RealBotPnlQueryTest}). */
    RealBotPnl query(PnlScenario scenario, PnlByModeProperties byMode) {
        when(jdbc.queryForObject("show statement_timeout", String.class)).thenReturn("0");
        when(directory.botsOf(TENANT, USER, PnlMarket.REAL)).thenReturn(scenario.bots());
        for (BotEntry bot : scenario.bots()) {
            when(directory.botOf(TENANT, USER, bot.botId())).thenReturn(Optional.of(bot));
        }
        when(wallets.botWalletIds(TENANT, USER)).thenReturn(List.of(scenario.wallet));
        doAnswer(call -> {
                    Consumer<RealOrderView> consumer = call.getArgument(3);
                    scenario.orders().values().stream()
                            .map(RealBotPnlTest::view)
                            .forEach(consumer);
                    extraOrders.forEach(consumer);
                    return null;
                })
                .when(execution)
                .streamSettledOrders(eq(TENANT), eq(USER), any(ReadDeadline.class), any());
        when(execution.settledOrder(eq(TENANT), eq(USER), any())).thenReturn(Optional.empty());
        doAnswer(call -> {
                    Consumer<NetworkOrderedEntry> consumer = call.getArgument(4);
                    Set<RealEntryKind> kinds = call.getArgument(2);
                    scenario.entriesInNetworkOrder().stream()
                            .filter(entry -> kinds.contains(entry.kind()))
                            .forEach(consumer);
                    return null;
                })
                .when(ledger)
                .streamInNetworkOrder(eq(TENANT), anyCollection(), anySet(), any(ReadDeadline.class), any());
        when(gate.onMismatch(any(), any(), any())).thenReturn(mismatch);
        return new RealBotPnl(
                ledger,
                execution,
                directory,
                wallets,
                new RealCostBasis(),
                prices,
                jdbc,
                transactions(),
                Clock.fixed(PnlScenario.NOW, ZoneOffset.UTC),
                new PnlRealMetrics(new SimpleMeterRegistry()),
                PnlRealProperties.defaults(),
                byMode,
                gate);
    }

    /** A transaction manager double whose read transaction is always new (the read owns it). */
    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any()))
                .thenAnswer(call -> new org.springframework.transaction.support.SimpleTransactionStatus(true));
        return manager;
    }

    private static RealOrderView view(OrderAttribution order) {
        boolean liquidation = order.liquidation();
        return new RealOrderView(
                order.realOrderId(),
                order.botId(),
                liquidation ? Optional.of(UUID.randomUUID()) : Optional.empty(),
                liquidation ? "USER_LIQUIDATION" : "BOT",
                liquidation ? RealSide.SELL : RealSide.BUY,
                order.mint(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                order.feePaidByMainWallet() ? "SIGNED" : "AUTO_FULL",
                order.failed() ? RealOrderState.FAILED : RealOrderState.CONFIRMED,
                Optional.empty(),
                Optional.of("sig-" + order.realOrderId()),
                Optional.of(order.feeLamports()),
                Optional.of(order.feePaidByMainWallet() ? "MAIN_WALLET" : "BOT_WALLET"),
                Optional.empty(),
                order.settledAt(),
                order.settledAt(),
                Optional.empty(),
                order.botWalletId());
    }

    private static PnlScenario twoBots() {
        PnlScenario scenario = new PnlScenario().bot(A).bot(B);
        scenario.buy(A, 100, 100_000_000L, 1_000L);
        scenario.buy(B, 100, 300_000_000L, 0);
        scenario.ownSale(A, 50, 100_000_000L, 0);
        return scenario;
    }

    void prices(Map<String, Optional<BigDecimal>> answer) {
        when(prices.lamportPrices(anySet())).thenReturn(answer);
    }

    /** F1: one line per REAL bot in order of creation; the realized is GROSS; the rows of the person's wallets only. */
    @Test
    void f1TheBlockOfAPersonFromOnePassOverItsOrdersAndItsLedgerRows() {
        PnlScenario scenario = twoBots();
        RealBotPnl query = query(scenario, PnlByModeProperties.defaults());
        prices(PnlScenario.price(PnlScenario.XYZ, 2_000_000L));

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.lines()).extracting(RealBotPnlLine::botId).containsExactly(A.bot(), B.bot());
        RealBotPnlLine a = pnl.lines().getFirst();
        assertThat(a.realizedLamports()).isEqualTo(50_000_000L);
        assertThat(a.feesLamports()).isEqualTo(1_000L);
        assertThat(a.unrealized()).isEqualTo(new LamportsValue.Known(100_000_000L - 50_000_000L));
        assertThat(a.total()).isEqualTo(new LamportsValue.Known(50_000_000L + 50_000_000L - 1_000L));
        assertThat(pnl.total().realizedLamports()).isEqualTo(50_000_000L);
        assertThat(pnl.empty()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> walletIds = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(ledger)
                .streamInNetworkOrder(eq(TENANT), walletIds.capture(), eq(TRADING), any(ReadDeadline.class), any());
        assertThat(walletIds.getValue()).containsExactly(scenario.wallet);
    }

    /** F5, AC3.2.6: only the line of the bot; a bot that is not a REAL bot of the person is empty (404), nothing read. */
    @Test
    void f5TheLineOfOneBotOnlyAndNothingForABotOfSomebodyElse() {
        RealBotPnl query = query(twoBots(), PnlByModeProperties.defaults());
        prices(Map.of());
        UUID stranger = UUID.randomUUID();
        when(directory.botOf(TENANT, USER, stranger)).thenReturn(Optional.empty());

        assertThat(query.botPnl(TENANT, USER, stranger, PnlWindow.all())).isEmpty();
        verify(ledger, never()).streamInNetworkOrder(any(), anyCollection(), anySet(), any(ReadDeadline.class), any());
        Optional<RealBotPnlLine> b = query.botPnl(TENANT, USER, B.bot(), PnlWindow.all());
        assertThat(b).get().extracting(RealBotPnlLine::botId).isEqualTo(B.bot());
        assertThat(b.get().realizedLamports()).isZero();
    }

    /** NFR1.13: the prices are read once, at the end, and only for the mints with an open position. */
    @Test
    void thePricesAreReadOnceOnlyForTheMintsWithAnOpenPosition() {
        PnlScenario scenario = twoBots();
        scenario.mint("ABC").buy(A, 10, 10L, 0);
        scenario.mint("ABC").ownSale(A, 10, 20L, 0);
        RealBotPnl query = query(scenario, PnlByModeProperties.defaults());
        prices(Map.of());

        query.modePnl(TENANT, USER, PnlWindow.all());

        verify(prices, times(1)).lamportPrices(Set.of(PnlScenario.XYZ));
    }

    /** F1 step 2: without a REAL bot and without a bot wallet the block is empty, and nothing else is read. */
    @Test
    void withoutBotNorWalletTheBlockIsEmpty() {
        RealBotPnl query = query(new PnlScenario(), PnlByModeProperties.defaults());
        when(wallets.botWalletIds(TENANT, USER)).thenReturn(List.of());

        RealModePnl pnl = query.modePnl(TENANT, USER, PnlWindow.all());

        assertThat(pnl.empty()).isTrue();
        assertThat(pnl.lines()).isEmpty();
        assertThat(pnl.total().total()).isEqualTo(new LamportsValue.Known(0L));
        verify(execution, never()).streamSettledOrders(any(), any(), any(ReadDeadline.class), any());
        verify(ledger, never()).streamInNetworkOrder(any(), anyCollection(), anySet(), any(ReadDeadline.class), any());
    }

    /** F-2: the cap of real-max-orders is counted in the stream; above it TOO_MANY_ORDERS and no partial number. */
    @Test
    void moreOrdersThanTheCapMakeTheBlockUnavailableWithoutAPartialNumber() {
        PnlScenario scenario = twoBots();
        RealBotPnl query = query(scenario, new PnlByModeProperties(null, null, null, 15_000));
        for (int i = 0; i < 15_001; i++) {
            extraOrders.add(view(new OrderAttribution(
                    UUID.randomUUID(), Optional.of(A.bot()), false, true, "XYZ", 1, false, PnlScenario.NOW)));
        }

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class)
                .extracting(failure -> ((RealPnlUnavailable) failure).causeLabel())
                .isEqualTo(RealPnlUnavailable.TOO_MANY_ORDERS);
        verify(ledger, never()).streamInNetworkOrder(any(), anyCollection(), anySet(), any(ReadDeadline.class), any());
    }

    /** BR3.4, BR8.7: an order of a bot outside the directory: UNKNOWN_BOT goes to the audit gate; no number leaves. */
    @Test
    void anOrderOfABotOutsideTheDirectoryIsAMismatchOfTheBlockOnly() {
        PnlScenario scenario = twoBots();
        RealBotPnl query = query(scenario, PnlByModeProperties.defaults());
        UUID stranger = UUID.randomUUID();
        when(directory.botOf(TENANT, USER, stranger)).thenReturn(Optional.empty());
        extraOrders.add(view(new OrderAttribution(
                UUID.randomUUID(), Optional.of(stranger), false, false, "XYZ", 0, false, PnlScenario.NOW)));

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class)
                .extracting(failure -> ((RealPnlUnavailable) failure).causeLabel())
                .isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
        ArgumentCaptor<ReconciliationResult> result = ArgumentCaptor.forClass(ReconciliationResult.class);
        verify(gate).onMismatch(eq(TENANT), eq(USER), result.capture());
        assertThat(result.getValue().failedChecks()).containsExactly(ReconciliationResult.Check.UNKNOWN_BOT);
        verify(ledger, never()).streamInNetworkOrder(any(), anyCollection(), anySet(), any(ReadDeadline.class), any());
        assertThat(audits.get())
                .as("the read owned its transaction, which is closed: the audit ran once before the answer")
                .isEqualTo(1);
    }

    /**
     * Review R-01 (decision of the founder of 2026-11-23): inside the transaction of a block (still open when the read
     * returns), the audit is NOT written by the read; the answer carries it and the pool of the blocks runs it after
     * the transaction ended (MismatchAuditAfterTheBlockIT).
     */
    @Test
    void insideTheTransactionOfABlockTheAuditWaitsForTheEndOfTheTransaction() {
        PnlScenario scenario = twoBots();
        RealBotPnl query = query(scenario, PnlByModeProperties.defaults());
        UUID stranger = UUID.randomUUID();
        when(directory.botOf(TENANT, USER, stranger)).thenReturn(Optional.empty());
        extraOrders.add(view(new OrderAttribution(
                UUID.randomUUID(), Optional.of(stranger), false, false, "XYZ", 0, false, PnlScenario.NOW)));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                    .isSameAs(mismatch);
            assertThat(audits.get()).isZero();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        mismatch.afterTransaction();
        mismatch.afterTransaction();
        assertThat(audits.get()).as("once, whatever the calls").isEqualTo(1);
    }

    /** F1: a failed read is a technical failure of the block; a deadline passed is TIMEOUT, never a number. */
    @Test
    void aFailedOrLateReadMakesTheBlockUnavailable() {
        RealBotPnl query = query(twoBots(), PnlByModeProperties.defaults());
        doAnswer(call -> {
                    throw new DataAccessResourceFailureException("down");
                })
                .when(ledger)
                .streamInNetworkOrder(eq(TENANT), anyCollection(), anySet(), any(ReadDeadline.class), any());

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class)
                .extracting(failure -> ((RealPnlUnavailable) failure).causeLabel())
                .isNotEqualTo(RealPnlUnavailable.TIMEOUT);

        doAnswer(call -> {
                    throw new ReadDeadlineExceeded(Duration.ofMillis(500));
                })
                .when(ledger)
                .streamInNetworkOrder(eq(TENANT), anyCollection(), anySet(), any(ReadDeadline.class), any());

        assertThatThrownBy(() -> query.modePnl(TENANT, USER, PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class)
                .extracting(failure -> ((RealPnlUnavailable) failure).causeLabel())
                .isEqualTo(RealPnlUnavailable.TIMEOUT);
        verify(jdbc, times(2)).execute("set local work_mem = '12288kB'");
    }

    /** C10 from the U2: the period RANGE is accepted and reconciled. */
    @Test
    void aRangeIsAccepted() {
        RealBotPnl query = query(twoBots(), PnlByModeProperties.defaults());
        prices(new HashMap<>());

        RealModePnl week = query.modePnl(
                TENANT,
                USER,
                new PnlWindow.Range(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z")));

        assertThat(week.total().realizedLamports()).isEqualTo(50_000_000L);
    }
}
