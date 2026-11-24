package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.real.RealCostBasis;
import com.memetrade.realexecution.api.OpenSell;
import com.memetrade.realexecution.api.OpenSellSource;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.shared.jdbc.ReadDeadlineExceeded;
import com.memetrade.testsupport.MutableClock;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261121-paper-real-unico U4, Etapa 3 (written before the code; C15, BR1.1, BR1.2, BR2.2, BR2.3, BR2.4, NFR1.7, NFR2.3,
 * NFR2.6): the read of the own holdings of a bot, with doubles of its ports and a fake clock. Every failure is an
 * {@link HoldingsResult.Unavailable} with its reason and nothing is thrown, except a read started inside a transaction,
 * which is a programming defect; the available quantity is {@code max(0, own - open)}; a bot that is not a REAL bot of
 * the person reads nothing of its money.
 */
class RealBotHoldingsReaderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BOT = UUID.randomUUID();
    private static final UUID OTHER_BOT = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final String XYZ = "XyZ1111111111111111111111111111111111111111";
    private static final Duration BUDGET = Duration.ofMillis(250);
    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final RealLedger ledger = mock(RealLedger.class);
    private final RealExecutionPort orders = mock(RealExecutionPort.class);
    private final java.util.Map<UUID, RealOrderView> settled = new java.util.HashMap<>();
    private final BotDirectory bots = mock(BotDirectory.class);
    private final RealStateQuery wallets = mock(RealStateQuery.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(START);
    private final RealBotHoldingsReader reader =
            new RealBotHoldingsReader(ledger, orders, bots, wallets, new RealCostBasis(), jdbc, transactions, clock);

    private final List<NetworkOrderedEntry> lots = new ArrayList<>();
    private long slot = 1;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(bots.botsOf(TENANT, USER, PnlMarket.REAL)).thenReturn(List.of(entry(BOT), entry(OTHER_BOT)));
        when(wallets.botWalletIds(TENANT, USER)).thenReturn(List.of(WALLET));
        when(ledger.view(eq(TENANT), eq(WALLET), eq(Optional.empty()))).thenAnswer(call -> view());
        when(orders.openSells(TENANT, BOT)).thenReturn(List.of());
        // Review R-01: S4 reads the missing orders in one call; the double answers from the orders of the fixtures.
        when(orders.settledOrdersById(eq(TENANT), eq(USER), any())).thenAnswer(call -> {
            java.util.Collection<UUID> ids = call.getArgument(2);
            return ids.stream().filter(settled::containsKey).map(settled::get).toList();
        });
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void theOwnQuantityAndCostOfTheBotAreItsHolderInTheLedger() {
        buy(BOT, 100_000_000L, 100_000_000L);
        buy(OTHER_BOT, 300_000_000L, 450_000_000L);

        HoldingsResult result = reader.holdings(TENANT, USER, BOT, BUDGET);

        assertThat(result).isInstanceOf(HoldingsResult.Available.class);
        HoldingsResult.Available available = (HoldingsResult.Available) result;
        assertThat(available.holdings())
                .containsExactly(new BotHolding(XYZ, 100_000_000L, 100_000_000L, 0, 100_000_000L));
        assertThat(available.readAt()).isEqualTo(START);
    }

    @Test
    void theAvailableQuantityIsTheOwnMinusTheOpenSalesNeverNegative() {
        buy(BOT, 100_000_000L, 100_000_000L);
        when(orders.openSells(TENANT, BOT))
                .thenReturn(List.of(
                        new OpenSell(OpenSellSource.DECISION_REQUEST, UUID.randomUUID(), XYZ, 60_000_000L),
                        new OpenSell(OpenSellSource.ORDER, UUID.randomUUID(), XYZ, 70_000_000L)));

        HoldingsResult.Available result = (HoldingsResult.Available) reader.holdings(TENANT, USER, BOT, BUDGET);

        assertThat(result.holdings()).containsExactly(new BotHolding(XYZ, 100_000_000L, 100_000_000L, 130_000_000L, 0));
    }

    @Test
    void anOpenSaleOfAMintWithoutOwnQuantityHasZeroCostAndZeroAvailable() {
        buy(OTHER_BOT, 300_000_000L, 450_000_000L);
        when(orders.openSells(TENANT, BOT))
                .thenReturn(List.of(new OpenSell(OpenSellSource.ORDER, UUID.randomUUID(), XYZ, 5L)));

        HoldingsResult.Available result = (HoldingsResult.Available) reader.holdings(TENANT, USER, BOT, BUDGET);

        assertThat(result.holdings()).containsExactly(new BotHolding(XYZ, 0, 0, 5, 0));
    }

    @Test
    void aBotOutsideTheRealBotsOfThePersonIsBotNotOwnedAndNothingOfItsMoneyIsRead() {
        UUID stranger = UUID.randomUUID();

        HoldingsResult result = reader.holdings(TENANT, USER, stranger, BUDGET);

        assertThat(result).isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED));
        verifyNoInteractions(ledger);
        verify(orders, never()).openSells(any(), any());
        verify(wallets, never()).botWalletIds(any(), any());
    }

    @Test
    void theClockPastTheBudgetIsBudgetExceededAndNothingIsThrown() {
        buy(BOT, 100_000_000L, 100_000_000L);
        when(ledger.view(eq(TENANT), eq(WALLET), eq(Optional.empty()))).thenAnswer(call -> {
            clock.advance(Duration.ofMillis(251));
            return view();
        });

        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));
    }

    @Test
    void aDeadlineOrAStatementCancelledByTheServerIsBudgetExceeded() {
        when(orders.openSells(TENANT, BOT)).thenThrow(new ReadDeadlineExceeded(BUDGET));
        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));

        org.mockito.Mockito.doThrow(new UncategorizedSQLException(
                        "open sells", "select", new SQLException("canceling statement", "57014")))
                .when(orders)
                .openSells(TENANT, BOT);
        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));
    }

    @Test
    void anErrorOfTheDatabaseOrOfTheExactArithmeticIsReadFailed() {
        when(orders.openSells(TENANT, BOT)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED));

        when(transactions.getTransaction(any())).thenThrow(new CannotGetJdbcConnectionException("pool"));
        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED));
    }

    @Test
    void anOverflowOfTheOpenSalesIsReadFailed() {
        buy(BOT, 100_000_000L, 100_000_000L);
        when(orders.openSells(TENANT, BOT))
                .thenReturn(List.of(
                        new OpenSell(OpenSellSource.ORDER, UUID.randomUUID(), XYZ, Long.MAX_VALUE),
                        new OpenSell(OpenSellSource.DECISION_REQUEST, UUID.randomUUID(), XYZ, 1L)));

        assertThat(reader.holdings(TENANT, USER, BOT, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED));
    }

    @Test
    void aReadStartedInsideATransactionIsADefectBeforeAnyRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> reader.holdings(TENANT, USER, BOT, BUDGET)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bots, ledger, orders, wallets);
    }

    @Test
    void theStatementTimeoutComesFirstThenTheBotsOfThePersonFixTheSnapshot() {
        buy(BOT, 1, 1);

        reader.holdings(TENANT, USER, BOT, BUDGET);

        InOrder order = inOrder(jdbc, bots, wallets, ledger, orders);
        order.verify(jdbc).execute(anyString());
        order.verify(bots).botsOf(TENANT, USER, PnlMarket.REAL);
        order.verify(wallets).botWalletIds(TENANT, USER);
        order.verify(ledger).view(TENANT, WALLET, Optional.empty());
        order.verify(orders).openSells(TENANT, BOT);
    }

    @Test
    void theOrdersOfTheLotsAreReadByIdUpToTheThresholdAndStreamedAbove() {
        RealBotHoldingsReader byStream = new RealBotHoldingsReader(
                ledger, orders, bots, wallets, new RealCostBasis(), jdbc, transactions, clock, 0);
        UUID order = buy(BOT, 10, 10);

        reader.holdings(TENANT, USER, BOT, BUDGET);
        verify(orders).settledOrdersById(TENANT, USER, List.of(order));
        verify(orders, never()).settledOrder(any(), any(), any());
        verify(orders, never()).streamSettledOrders(any(), any(), any(), any());

        byStream.holdings(TENANT, USER, BOT, BUDGET);
        verify(orders).streamSettledOrders(eq(TENANT), eq(USER), any(), any());
    }

    /**
     * Review R-01: an order named by the lots that is not found (gone, or not the person's) is looked up ONCE per read,
     * in the single statement of S4, never again once per row that names it; its rows stay UNOWNED as before.
     */
    @Test
    void anOrderNotFoundIsLookedUpOncePerReadAndItsRowsStayUnowned() {
        UUID gone = UUID.randomUUID();
        lot(gone, 3, 3);
        lot(gone, 4, 4);
        buy(BOT, 10, 10);

        HoldingsResult result = reader.holdings(TENANT, USER, BOT, BUDGET);

        assertThat(result).isInstanceOf(HoldingsResult.Available.class);
        assertThat(((HoldingsResult.Available) result).holdings())
                .singleElement()
                .satisfies(holding -> assertThat(holding.ownTokenBaseUnits()).isEqualTo(10));
        verify(orders).settledOrdersById(eq(TENANT), eq(USER), any());
        verify(orders, never()).settledOrder(any(), any(), any());
    }

    // ----- fixtures -----
    /** A buy of {@code units} named {@code order:<order>} in the open lots, with no order behind it yet. */
    private void lot(UUID order, long units, long paid) {
        String reference = "order:" + order;
        String signature = "sig" + slot;
        lots.add(new NetworkOrderedEntry(
                UUID.randomUUID(),
                WALLET,
                RealEntryKind.SWAP_FILL_IN,
                -paid,
                Optional.of(XYZ),
                Optional.of(units),
                Optional.of(signature),
                reference,
                START,
                new NetworkOrderKey(slot, false, Optional.of(signature), START, reference, 0, order + ":SWAP_FILL_IN"),
                SlotStatus.RECORDED));
        slot++;
    }

    private UUID buy(UUID bot, long units, long paid) {
        UUID order = UUID.randomUUID();
        lot(order, units, paid);
        RealOrderView view = settled(order, bot);
        when(orders.settledOrder(TENANT, USER, order)).thenReturn(Optional.of(view));
        settled.put(order, view);
        org.mockito.Mockito.doAnswer(call -> {
                    java.util.function.Consumer<RealOrderView> consumer = call.getArgument(3);
                    consumer.accept(view);
                    return null;
                })
                .when(orders)
                .streamSettledOrders(eq(TENANT), eq(USER), any(), any());
        return order;
    }

    private RealLedgerView view() {
        long units = lots.stream()
                .mapToLong(entry -> entry.tokenBaseUnits().orElseThrow())
                .sum();
        List<RealPosition> positions = units == 0
                ? List.of()
                : List.of(new RealPosition(XYZ, units, new RealCost.Unknown(RealCostUnknownReason.NOT_READ)));
        return new RealLedgerView(
                new RealBalance(1, 0, 1, Set.of(), true), positions, RealFlows.NONE, lots.size(), lots);
    }

    private static RealOrderView settled(UUID order, UUID bot) {
        return new RealOrderView(
                order,
                Optional.of(bot),
                Optional.empty(),
                "BOT",
                RealSide.BUY,
                XYZ,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "AUTO_FULL",
                RealOrderState.CONFIRMED,
                Optional.empty(),
                Optional.of("sig-" + order),
                Optional.of(0L),
                Optional.of("BOT_WALLET"),
                Optional.empty(),
                START,
                START,
                Optional.empty(),
                Optional.of(WALLET));
    }

    private static BotEntry entry(UUID bot) {
        return new BotEntry(
                bot, "robo", PnlMarket.REAL, "MOMENTUM_VOLUME", BotPnlStatus.ACTIVE, START.minusSeconds(60));
    }
}
