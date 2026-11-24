package com.memetrade.pnl.application;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.real.Holder;
import com.memetrade.realexecution.api.OpenSell;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.jdbc.ReadDeadline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U4 (C15; F1 step 3; BR1.1 to BR1.3, BR2.1 to BR2.4, BR7.1; NFR1.1, NFR1.4, NFR1.7, NFR1.11,
 * NFR2.3, NFR2.6, NFR2.9, NFR3.1, NFR3.2; performance-design.md "Leitura no instantâneo"): the own holdings of a REAL
 * bot, read by the REAL cycle of the worker before its transaction, in ONE read-only REPEATABLE READ transaction of its
 * own ({@code REQUIRES_NEW}; a transaction already active in the thread is a programming defect):
 *
 * <ol>
 *   <li>S0: the statement timeout of what remains of the budget (it does not fix the snapshot);
 *   <li>S1, the FIRST query, which fixes the snapshot: the REAL bots of the person (C8); a bot outside them is
 *       BOT_NOT_OWNED and nothing of its money is read;
 *   <li>S2: the bot wallets of the person (the read the P&L REAL by bot uses);
 *   <li>S3: the view of each wallet ({@code RealLedger.view}, one statement), with the lots since the epoch of each mint;
 *   <li>S4: the settled orders those lots name: up to {@link #ORDER_PREFETCH_THRESHOLD} distinct ones by id, above it
 *       the stream of C13 under the deadline and by id only what it did not bring;
 *   <li>S5: the open sales of the bot (C16 extended);
 *   <li>S6: in memory, the accumulator of the U2 ({@link EpochAttribution}) and the open positions of the holder bot;
 *       {@code available = max(0, own - open)}, exact.
 * </ol>
 *
 * The {@link ReadDeadline} starts BEFORE the connection is asked; what remains is applied as {@code set local
 * statement_timeout} before every statement, the clock is checked at every lot, and once more after the commit, so the
 * budget covers the whole read, the wait for the pool included. Every failure is {@link HoldingsResult.Unavailable}:
 * BUDGET_EXCEEDED for a deadline (or SQLSTATE 57014), BOT_NOT_OWNED, READ_FAILED for anything else; never the position
 * of the whole wallet, never a lock, never a write.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class RealBotHoldingsReader implements RealBotHoldings {

    /**
     * Up to this many distinct orders named by the open lots, S4 reads them by id ({@code real_order_pkey}); above it,
     * by the stream of C13 (one statement, 500 rows a fetch). Confirmed by {@code RealBotHoldingsMeasurementIT} with the
     * seeds A and B; it only ever changes without loosening the budget.
     */
    public static final int ORDER_PREFETCH_THRESHOLD = 32;

    private static final Logger log = LoggerFactory.getLogger(RealBotHoldingsReader.class);

    private final RealLedger ledger;
    private final RealExecutionPort orders;
    private final BotDirectory bots;
    private final RealStateQuery wallets;
    private final RealCostMethod method;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final Clock clock;
    private final int threshold;

    @Autowired
    public RealBotHoldingsReader(
            RealLedger ledger,
            RealExecutionPort orders,
            BotDirectory bots,
            RealStateQuery wallets,
            RealCostMethod method,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            Clock clock) {
        this(ledger, orders, bots, wallets, method, jdbc, transactions, clock, ORDER_PREFETCH_THRESHOLD);
    }

    /** With another threshold of S4 (the parity of the two paths, {@code RealBotHoldingsOrderPathParityIT}). */
    RealBotHoldingsReader(
            RealLedger ledger,
            RealExecutionPort orders,
            BotDirectory bots,
            RealStateQuery wallets,
            RealCostMethod method,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            Clock clock,
            int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("the threshold of the read by id is at least 0");
        }
        this.ledger = ledger;
        this.orders = orders;
        this.bots = bots;
        this.wallets = wallets;
        this.method = method;
        this.jdbc = jdbc;
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.read.setReadOnly(true);
        this.clock = clock;
        this.threshold = threshold;
    }

    @Override
    public HoldingsResult holdings(UUID tenantId, UUID userId, UUID botId, Duration budget) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The own holdings are read before the transaction of the cycle, never inside a transaction");
        }
        ReadDeadline deadline = ReadDeadline.after(clock, budget);
        try {
            HoldingsResult result = read.execute(status -> snapshot(tenantId, userId, botId, deadline));
            deadline.check();
            return result;
        } catch (BotNotOwned notOwned) {
            return new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED);
        } catch (RuntimeException failure) {
            UnavailableReason reason =
                    ReadDeadline.isTimeout(failure) ? UnavailableReason.BUDGET_EXCEEDED : UnavailableReason.READ_FAILED;
            log.debug(
                    "event=REAL_OWN_HOLDINGS_READ_ENDED botId={} reason={} exceptionClass={}",
                    botId,
                    reason,
                    failure.getClass().getSimpleName());
            return new HoldingsResult.Unavailable(reason);
        }
    }

    private HoldingsResult snapshot(UUID tenantId, UUID userId, UUID botId, ReadDeadline deadline) {
        Instant readAt = clock.instant();
        // S0, then S1: the first query of the transaction fixes the snapshot (review R-05).
        deadline.applyStatementTimeout(jdbc::execute);
        Map<UUID, Holder> holders = new HashMap<>();
        Holder self = null;
        for (BotEntry bot : bots.botsOf(tenantId, userId, PnlMarket.REAL)) {
            Holder holder = Holder.bot(bot.botId(), bot.createdAt());
            holders.put(bot.botId(), holder);
            if (bot.botId().equals(botId)) {
                self = holder;
            }
        }
        if (self == null) {
            throw new BotNotOwned();
        }
        deadline.applyStatementTimeout(jdbc::execute);
        List<UUID> walletIds = wallets.botWalletIds(tenantId, userId);
        Map<String, long[]> own = new LinkedHashMap<>();
        Map<UUID, EpochAttribution.Attribution> index = new HashMap<>();
        // Review R-01: the orders looked up and not found in this snapshot are read once per read, not once per row.
        Set<UUID> absent = new java.util.HashSet<>();
        boolean streamed = false;
        for (UUID walletId : walletIds) {
            deadline.applyStatementTimeout(jdbc::execute);
            RealLedgerView view = ledger.view(tenantId, walletId, Optional.empty());
            deadline.check();
            streamed = settledOrders(tenantId, userId, view, index, absent, deadline, streamed);
            EpochAttribution accumulation =
                    new EpochAttribution(tenantId, userId, holders, index, absent, method.start(), orders, bots);
            for (NetworkOrderedEntry entry : view.openLots()) {
                deadline.check();
                accumulation.accept(entry);
            }
            accumulation.flush();
            deadline.check();
            for (RealPosition position : accumulation.wallet.openPositions(self)) {
                long cost = ((com.memetrade.ledger.api.RealCost.Known) position.cost()).lamports();
                long[] sum = own.computeIfAbsent(position.mint(), mint -> new long[2]);
                sum[0] = Math.addExact(sum[0], position.tokenBaseUnits());
                sum[1] = Math.addExact(sum[1], cost);
            }
        }
        deadline.applyStatementTimeout(jdbc::execute);
        Map<String, Long> open = new LinkedHashMap<>();
        for (OpenSell sale : orders.openSells(tenantId, botId)) {
            open.merge(sale.mint(), sale.baseUnits(), Math::addExact);
        }
        deadline.check();
        return new HoldingsResult.Available(holdings(own, open), readAt);
    }

    /**
     * S4: the settled orders the lots of {@code view} name and the index does not have yet: by id up to the threshold;
     * above it the stream of C13 once per read, then by id only what it did not bring. Returns whether the stream ran.
     */
    private boolean settledOrders(
            UUID tenantId,
            UUID userId,
            RealLedgerView view,
            Map<UUID, EpochAttribution.Attribution> index,
            Set<UUID> absent,
            ReadDeadline deadline,
            boolean streamed) {
        Set<UUID> named = new LinkedHashSet<>();
        for (NetworkOrderedEntry entry : view.openLots()) {
            EpochAttribution.orderId(entry.reference())
                    .filter(id -> !index.containsKey(id))
                    .ifPresent(named::add);
        }
        boolean stream = streamed;
        if (named.size() > threshold && !streamed) {
            deadline.applyStatementTimeout(jdbc::execute);
            orders.streamSettledOrders(
                    tenantId,
                    userId,
                    deadline,
                    order -> index.put(order.realOrderId(), EpochAttribution.Attribution.of(order)));
            stream = true;
        }
        // Review R-01: what is still missing in ONE statement (= ANY), not two statements per order.
        List<UUID> missing = named.stream()
                .filter(id -> !index.containsKey(id) && !absent.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            deadline.applyStatementTimeout(jdbc::execute);
            for (var order : orders.settledOrdersById(tenantId, userId, missing)) {
                index.put(order.realOrderId(), EpochAttribution.Attribution.of(order));
            }
            // Not the person's, not settled or gone in this snapshot: the rows that name them are UNOWNED, as before.
            missing.stream().filter(id -> !index.containsKey(id)).forEach(absent::add);
        }
        return stream;
    }

    /** S6: one holding per mint with an own quantity or an open sale; exact arithmetic. */
    private static List<BotHolding> holdings(Map<String, long[]> own, Map<String, Long> open) {
        Set<String> mints = new LinkedHashSet<>(own.keySet());
        mints.addAll(open.keySet());
        List<BotHolding> holdings = new ArrayList<>();
        for (String mint : mints) {
            long[] mine = own.getOrDefault(mint, new long[2]);
            long selling = open.getOrDefault(mint, 0L);
            long available = Math.max(0, Math.subtractExact(mine[0], selling));
            holdings.add(new BotHolding(mint, mine[0], mine[1], selling, available));
        }
        return holdings;
    }

    /** BR2.4: the bot is not a REAL bot of the person in the tenant. */
    private static final class BotNotOwned extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BotNotOwned() {
            super("The bot is not a REAL bot of the person");
        }
    }
}
