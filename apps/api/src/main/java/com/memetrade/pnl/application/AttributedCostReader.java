package com.memetrade.pnl.application;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.pnl.infra.PnlRealProperties;
import com.memetrade.pnl.real.Holder;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.shared.jdbc.ReadDeadline;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U2 (BR2.4, BR2.7, BR3.1, BR3.2, BR3.4, BR5.4; performance-design.md "Leitura de custo com
 * atribuição"; reliability-design.md): the cost of one bot wallet with attribution, for the rounds of the barrier
 * ({@code GATE}) and the REAL cycle ({@code CYCLE}). Inside a SAVEPOINT of the transaction of the caller
 * ({@code PROPAGATION_NESTED}):
 *
 * <ol>
 *   <li>the REAL bots of the person (C8), paused and removed included;
 *   <li>the settled orders of the person (C13), streamed into a compact index by id;
 *   <li>every row of the wallet in network order (C12), all the kinds, each counted; the swap fills become
 *       {@link CostFill}s attributed to their holder (the bot of the order, a sale without bot for a liquidation or a
 *       conditional, UNOWNED for an order gone); the RENT of a buy enters its cost (BR2.1);
 *   <li>the accumulator of {@link RealCostMethod}: the cost of each mint is the sum of its holders.
 * </ol>
 *
 * A hard deadline (250 ms) covers the four steps and the work in Java, in two layers (the clock at every row and the
 * {@code statement_timeout} applied again before every fetch); {@code work_mem} is set locally. On success the values
 * of {@code statement_timeout} and {@code work_mem} read before are applied again (RELEASE SAVEPOINT keeps a SET
 * LOCAL); on any failure ({@code 57014}, the deadline, another error of the read, UNKNOWN_BOT, a count of rows other
 * than the one of the view) the savepoint is rolled back, which undoes the error and the SET LOCAL, so the transaction
 * of the caller stays usable, and the answer is {@link WalletCost.Unknown}. Never a lock.
 */
@Component
public class AttributedCostReader implements RealWalletCost {

    private static final Logger log = LoggerFactory.getLogger(AttributedCostReader.class);

    private final RealLedger ledger;
    private final RealExecutionPort orders;
    private final BotDirectory bots;
    private final RealCostMethod method;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate savepoint;
    private final Clock clock;
    private final PnlRealMetrics metrics;
    private final PnlRealProperties properties;

    public AttributedCostReader(
            RealLedger ledger,
            RealExecutionPort orders,
            BotDirectory bots,
            RealCostMethod method,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            PnlRealMetrics metrics,
            PnlRealProperties properties) {
        this.ledger = ledger;
        this.orders = orders;
        this.bots = bots;
        this.method = method;
        this.jdbc = jdbc;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.clock = clock;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public WalletCost costOf(UUID tenantId, UUID userId, UUID botWalletId, CostPath path, long expectedWalletRows) {
        if (path == CostPath.PNL) {
            throw new IllegalArgumentException("the read of the P&L block has its own path (RealBotPnl)");
        }
        return guarded(path, () -> read(tenantId, userId, botWalletId, expectedWalletRows));
    }

    /**
     * 261121-paper-real-unico U2a (Etapa 14a, decision of the founder): the cost from the EPOCH of each mint. The view
     * the caller read just before (one statement, one snapshot) brings the swap fills and the RENT of each open mint
     * after the last time its quantity reached zero; before that point every holder of the mint was at zero, so nothing
     * is lost. Only the bots (C8) and the orders those rows name (read on the spot, by id) are read here: no stream of
     * the whole history. Same savepoint, same hard deadline, same failures; nothing is stored.
     */
    @Override
    public WalletCost costOf(
            UUID tenantId, UUID userId, UUID botWalletId, CostPath path, com.memetrade.ledger.api.RealLedgerView view) {
        if (path == CostPath.PNL) {
            throw new IllegalArgumentException("the read of the P&L block has its own path (RealBotPnl)");
        }
        return guarded(path, () -> readFromEpoch(tenantId, userId, view));
    }

    private WalletCost readFromEpoch(UUID tenantId, UUID userId, com.memetrade.ledger.api.RealLedgerView view) {
        String timeoutBefore = jdbc.queryForObject("show statement_timeout", String.class);
        String workMemBefore = jdbc.queryForObject("show work_mem", String.class);
        ReadDeadline deadline =
                ReadDeadline.after(clock, properties.attribution().gateAndCycleTimeout());
        deadline.applyStatementTimeout(jdbc::execute);
        Map<UUID, Holder> holders = new HashMap<>();
        for (BotEntry bot : bots.botsOf(tenantId, userId, PnlMarket.REAL)) {
            holders.put(bot.botId(), Holder.bot(bot.botId(), bot.createdAt()));
        }
        Accumulation accumulation = new Accumulation(tenantId, userId, holders, new HashMap<>(), method.start());
        for (NetworkOrderedEntry entry : view.openLots()) {
            deadline.check();
            accumulation.accept(entry);
        }
        accumulation.flush();
        deadline.check();
        Map<String, Long> costByMint = new LinkedHashMap<>();
        for (RealPosition position : accumulation.wallet.openPositions()) {
            costByMint.put(position.mint(), ((com.memetrade.ledger.api.RealCost.Known) position.cost()).lamports());
        }
        jdbc.execute("set local statement_timeout = '" + timeoutBefore + "'");
        jdbc.execute("set local work_mem = '" + workMemBefore + "'");
        return new WalletCost.Known(costByMint);
    }

    private WalletCost guarded(CostPath path, java.util.function.Supplier<WalletCost> read) {
        long started = System.nanoTime();
        try {
            WalletCost cost = savepoint.execute(status -> read.get());
            metrics.attributionRead(path, PnlRealMetrics.Outcome.OK, Duration.ofNanos(System.nanoTime() - started));
            return cost;
        } catch (RuntimeException failure) {
            RealCostUnknownReason reason = reasonOf(failure);
            PnlRealMetrics.Outcome outcome =
                    switch (reason) {
                        case TIMEOUT -> PnlRealMetrics.Outcome.TIMEOUT;
                        case UNKNOWN_BOT -> PnlRealMetrics.Outcome.UNKNOWN_BOT;
                        default -> PnlRealMetrics.Outcome.ERROR;
                    };
            metrics.attributionRead(path, outcome, Duration.ofNanos(System.nanoTime() - started));
            log.warn(
                    "event=REAL_ATTRIBUTION_READ_FAILED path={} outcome={} reason={} exceptionClass={}",
                    path,
                    outcome,
                    reason,
                    failure.getClass().getSimpleName());
            return new WalletCost.Unknown(reason);
        }
    }

    private WalletCost read(UUID tenantId, UUID userId, UUID botWalletId, long expectedWalletRows) {
        String timeoutBefore = jdbc.queryForObject("show statement_timeout", String.class);
        String workMemBefore = jdbc.queryForObject("show work_mem", String.class);
        ReadDeadline deadline =
                ReadDeadline.after(clock, properties.attribution().gateAndCycleTimeout());
        jdbc.execute("set local work_mem = '" + properties.read().workMemSetting() + "'");
        deadline.applyStatementTimeout(jdbc::execute);

        Map<UUID, Holder> holders = new HashMap<>();
        for (BotEntry bot : bots.botsOf(tenantId, userId, PnlMarket.REAL)) {
            holders.put(bot.botId(), Holder.bot(bot.botId(), bot.createdAt()));
        }
        Map<UUID, Attribution> index = new HashMap<>();
        orders.streamSettledOrders(
                tenantId, userId, deadline, order -> index.put(order.realOrderId(), Attribution.of(order)));

        Accumulation accumulation = new Accumulation(tenantId, userId, holders, index, method.start());
        ledger.streamInNetworkOrder(
                tenantId, List.of(botWalletId), EnumSet.allOf(RealEntryKind.class), deadline, accumulation::accept);
        accumulation.flush();
        if (accumulation.rows != expectedWalletRows) {
            throw new InconsistentRead();
        }
        deadline.check();
        Map<String, Long> costByMint = new LinkedHashMap<>();
        for (RealPosition position : accumulation.wallet.openPositions()) {
            costByMint.put(position.mint(), ((com.memetrade.ledger.api.RealCost.Known) position.cost()).lamports());
        }
        jdbc.execute("set local statement_timeout = '" + timeoutBefore + "'");
        jdbc.execute("set local work_mem = '" + workMemBefore + "'");
        return new WalletCost.Known(costByMint);
    }

    /** What the cost needs of an order: its bot, its origin and its side. */
    private record Attribution(Optional<UUID> botId, boolean liquidation, RealSide side) {

        static Attribution of(RealOrderView order) {
            return new Attribution(order.botId(), "USER_LIQUIDATION".equals(order.origin()), order.side());
        }
    }

    /** The rows of the wallet applied to the accumulator; a buy waits for the RENT of its reference (BR2.1). */
    private final class Accumulation {

        private final UUID tenantId;
        private final UUID userId;
        private final Map<UUID, Holder> holders;
        private final Map<UUID, Attribution> index;
        private final RealCostMethod.Accumulator wallet;
        private long rows;
        private NetworkOrderedEntry pendingBuy;
        private Holder pendingHolder;
        private long pendingPaid;

        Accumulation(
                UUID tenantId,
                UUID userId,
                Map<UUID, Holder> holders,
                Map<UUID, Attribution> index,
                RealCostMethod.Accumulator wallet) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.holders = holders;
            this.index = index;
            this.wallet = wallet;
        }

        void accept(NetworkOrderedEntry entry) {
            rows++;
            if (pendingBuy != null) {
                if (entry.kind() == RealEntryKind.RENT && entry.reference().equals(pendingBuy.reference())) {
                    pendingPaid = Math.addExact(pendingPaid, Math.negateExact(entry.lamports()));
                    return;
                }
                if (entry.kind() == RealEntryKind.NETWORK_FEE
                        && entry.reference().equals(pendingBuy.reference())) {
                    return;
                }
                flush();
            }
            if (entry.kind() == RealEntryKind.SWAP_FILL_IN) {
                pendingBuy = entry;
                pendingHolder = holderOfBuy(entry.reference());
                pendingPaid = Math.negateExact(entry.lamports());
            } else if (entry.kind() == RealEntryKind.SWAP_FILL_OUT) {
                wallet.apply(new CostFill(
                        entry.key(),
                        RealEntryKind.SWAP_FILL_OUT,
                        entry.mint().orElseThrow(),
                        entry.tokenBaseUnits().orElseThrow(),
                        entry.lamports(),
                        sellerOf(entry.reference())));
            }
        }

        void flush() {
            if (pendingBuy == null) {
                return;
            }
            wallet.apply(new CostFill(
                    pendingBuy.key(),
                    RealEntryKind.SWAP_FILL_IN,
                    pendingBuy.mint().orElseThrow(),
                    pendingBuy.tokenBaseUnits().orElseThrow(),
                    Math.negateExact(pendingPaid),
                    Optional.of(pendingHolder)));
            pendingBuy = null;
        }

        /** BR3.1, BR5.4: the bot of the order; an order gone is UNOWNED; a bot outside the directory is a defect. */
        private Holder holderOfBuy(String reference) {
            Optional<Attribution> order = order(reference);
            if (order.isEmpty() || order.get().botId().isEmpty()) {
                return Holder.UNOWNED;
            }
            return bot(order.get().botId().get());
        }

        /** BR3.2: an own sale of the bot of the order; a liquidation and a conditional are sales without bot. */
        private Optional<com.memetrade.ledger.api.CostHolder> sellerOf(String reference) {
            if (reference.startsWith("conditional:")) {
                return Optional.empty();
            }
            Optional<Attribution> order = order(reference);
            if (order.isEmpty()) {
                return Optional.of(Holder.UNOWNED);
            }
            if (order.get().liquidation() || order.get().botId().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(bot(order.get().botId().get()));
        }

        /** The order of {@code order:<id>}: from the index, else read on the spot (confirmed meanwhile). */
        private Optional<Attribution> order(String reference) {
            if (!reference.startsWith("order:")) {
                return Optional.empty();
            }
            UUID id;
            try {
                id = UUID.fromString(reference.substring("order:".length()));
            } catch (IllegalArgumentException notAnOrder) {
                // A reference of an order that is not an id of real_order: it has no order, like a purged one.
                return Optional.empty();
            }
            Attribution known = index.get(id);
            if (known != null) {
                return Optional.of(known);
            }
            Optional<Attribution> read =
                    orders.settledOrder(tenantId, userId, id).map(Attribution::of);
            read.ifPresent(found -> index.put(id, found));
            return read;
        }

        /** BR3.4: a bot that is not a REAL bot of the person fails the read (after reading it on the spot). */
        private Holder bot(UUID botId) {
            Holder holder = holders.get(botId);
            if (holder != null) {
                return holder;
            }
            Optional<BotEntry> read =
                    bots.botOf(tenantId, userId, botId).filter(entry -> entry.market() == PnlMarket.REAL);
            if (read.isEmpty()) {
                throw new UnknownBot();
            }
            Holder found = Holder.bot(read.get().botId(), read.get().createdAt());
            holders.put(botId, found);
            return found;
        }
    }

    private static RealCostUnknownReason reasonOf(RuntimeException failure) {
        if (failure instanceof UnknownBot) {
            return RealCostUnknownReason.UNKNOWN_BOT;
        }
        if (failure instanceof InconsistentRead) {
            return RealCostUnknownReason.INCONSISTENT_READ;
        }
        if (ReadDeadline.isTimeout(failure)) {
            return RealCostUnknownReason.TIMEOUT;
        }
        return RealCostUnknownReason.ERROR;
    }

    /** BR3.4: an order names a bot that is not a REAL bot of the person. */
    private static final class UnknownBot extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownBot() {
            super("An order of the wallet names a bot that is not a REAL bot of the person");
        }
    }

    /** The read saw another set of rows than the view read before it. */
    private static final class InconsistentRead extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InconsistentRead() {
            super("The read of cost saw another set of rows than the view");
        }
    }
}
