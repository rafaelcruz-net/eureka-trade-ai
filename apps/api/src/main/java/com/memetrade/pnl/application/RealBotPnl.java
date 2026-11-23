package com.memetrade.pnl.application;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealModeTotal;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.infra.PnlRealProperties;
import com.memetrade.pnl.real.OrderAttribution;
import com.memetrade.pnl.real.RealPnlEngine;
import com.memetrade.pnl.real.RealPnlReconciliation;
import com.memetrade.pnl.real.RealPnlResult;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.jdbc.ReadDeadline;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U2 (C10, the "V2" of {@link RealBotPnlQuery}; F1 to F5, F8, BR8.6, BR8.7, BR10.1 to BR10.3,
 * F-2, D-NFR1, NFR1.12, NFR1.13, NFR1.15, NFR1.16): the P&L REAL by bot of a person, ONE pass, in a read-only
 * transaction in REPEATABLE READ (the orders and the rows see the same snapshot, so the reconciliation is exact), with
 * {@code set local work_mem}; one connection of the main pool from start to end; no lock, no write.
 *
 * <ol>
 *   <li>the attribution (path {@code PNL}, at most 500 ms inside the deadline of the block, timed by
 *       {@code pnl_real_attribution_read_seconds{path="PNL"}}): the REAL bots of the person (C8) and its settled orders
 *       in a stream (C13) into a compact index by id, the cap {@code real-max-orders} counted in the stream (F-2); an
 *       order of a bot outside the directory is UNKNOWN_BOT (BR3.4);
 *   <li>the trading rows of the person's bot wallets in network order (C12), applied one by one by
 *       {@link RealPnlEngine}, under the deadline of the block;
 *   <li>the prices once, only for the mints with an open position (NFR1.13);
 *   <li>the reconciliation; a mismatch goes to the {@link ReconciliationAuditGate} and leaves the block UNAVAILABLE;
 *       its audit is written only after the read transaction ended (review R-01).
 * </ol>
 *
 * The deadline of the block is the {@code statement_timeout} the pool {@code pnl-mode-block} set on the transaction
 * (what is left of its 800 ms), or {@code block-timeout} when called on its own. Any failure of a read is
 * {@link RealPnlUnavailable}: {@code TIMEOUT} for a deadline ({@code 57014} or the deadline at a row), the class of the
 * failure otherwise; never a partial number.
 */
@Service
@ProcessRole(ProcessRole.Kind.API)
public class RealBotPnl implements RealBotPnlQuery {

    /** BR6.5: only these rows enter the P&L; the inheritance of the slot is computed over every row before the filter. */
    static final Set<RealEntryKind> TRADING = EnumSet.of(
            RealEntryKind.SWAP_FILL_IN, RealEntryKind.SWAP_FILL_OUT, RealEntryKind.NETWORK_FEE, RealEntryKind.RENT);

    private final RealLedger ledger;
    private final RealExecutionPort orders;
    private final BotDirectory directory;
    private final RealStateQuery wallets;
    private final RealCostMethod method;
    private final RealLamportPrices prices;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final Clock clock;
    private final PnlRealMetrics metrics;
    private final PnlRealProperties properties;
    private final Duration blockTimeout;
    private final int maxOrders;
    private final ReconciliationAuditGate gate;

    public RealBotPnl(
            RealLedger ledger,
            RealExecutionPort orders,
            BotDirectory directory,
            RealStateQuery wallets,
            RealCostMethod method,
            RealLamportPrices prices,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            Clock clock,
            PnlRealMetrics metrics,
            PnlRealProperties properties,
            PnlByModeProperties byMode,
            ReconciliationAuditGate gate) {
        this.ledger = ledger;
        this.orders = orders;
        this.directory = directory;
        this.wallets = wallets;
        this.method = method;
        this.prices = prices;
        this.jdbc = jdbc;
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.clock = clock;
        this.metrics = metrics;
        this.properties = properties;
        this.blockTimeout = byMode.blockTimeout();
        this.maxOrders = byMode.realMaxOrders();
        this.gate = gate;
        if (properties.attribution().pnlTimeout().compareTo(blockTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "memetrade.pnl.real.attribution.pnl-timeout must be below memetrade.pnl.by-mode.block-timeout");
        }
    }

    @Override
    public RealModePnl modePnl(UUID tenantId, UUID userId, PnlWindow window) {
        return pass(tenantId, userId, window).pnl();
    }

    @Override
    public Optional<RealBotPnlLine> botPnl(UUID tenantId, UUID userId, UUID botId, PnlWindow window) {
        Optional<BotEntry> bot =
                directory.botOf(tenantId, userId, botId).filter(entry -> entry.market() == PnlMarket.REAL);
        if (bot.isEmpty()) {
            return Optional.empty();
        }
        // F5: the whole pass of the person, because the proration depends on every holder; only the line leaves.
        return pass(tenantId, userId, window).pnl().lines().stream()
                .filter(line -> line.botId().equals(botId))
                .findFirst();
    }

    /** BR10.3: the total and the positions of {@code GET /api/v1/real/pnl}, since the beginning. */
    public RealPnlResult realPnl(UUID tenantId, UUID userId) {
        return pass(tenantId, userId, PnlWindow.all());
    }

    /** The outcome of the read transaction: the result, and its deadline for the audit of a mismatch. */
    private record Pass(RealPnlResult result, ReadDeadline block) {}

    private RealPnlResult pass(UUID tenantId, UUID userId, PnlWindow window) {
        Pass pass;
        try {
            pass = read.execute(status -> {
                Pass computed = compute(tenantId, userId, window);
                // An UNKNOWN_BOT found inside a @Transactional read of another module (the stream of the orders or of
                // the ledger) marks the shared transaction rollback-only. When this read owns the transaction it ends
                // it with an explicit rollback (it only read), so the mismatch reaches the audit gate instead of an
                // UnexpectedRollbackException; inside the transaction of a block, the gate throws and the block rolls
                // back.
                if (computed.result().reconciliation().status() == ReconciliationResult.Status.MISMATCH
                        && status.isNewTransaction()) {
                    status.setRollbackOnly();
                }
                return computed;
            });
        } catch (RealPnlUnavailable unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            throw new RealPnlUnavailable(
                    ReadDeadline.isTimeout(failure)
                            ? RealPnlUnavailable.TIMEOUT
                            : failure.getClass().getSimpleName(),
                    failure);
        }
        if (pass.result().reconciliation().status() == ReconciliationResult.Status.MISMATCH) {
            ReconciliationMismatch mismatch =
                    gate.onMismatch(tenantId, userId, pass.result().reconciliation());
            // Review R-01 (decision of the founder of 2026-11-23): the audit is written only once the read transaction
            // has ended. When this read owned it, it ended above: write now. Inside the transaction of a block (still
            // open), the pool of the blocks writes it after that transaction (AfterBlockRelease).
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                mismatch.afterTransaction();
            }
            throw mismatch;
        }
        return pass.result();
    }

    private Pass compute(UUID tenantId, UUID userId, PnlWindow window) {
        ReadDeadline block = ReadDeadline.after(clock, blockBudget());
        ReadDeadline attribution = block.within(properties.attribution().pnlTimeout());
        jdbc.execute("set local work_mem = '" + properties.read().workMemSetting() + "'");

        // Steps 1 to 3 of F1: the attribution, under its own budget.
        long started = System.nanoTime();
        Map<UUID, BotEntry> bots = new LinkedHashMap<>();
        Map<UUID, OrderAttribution> index = new HashMap<>();
        List<OrderAttribution> attempts = new ArrayList<>();
        List<UUID> walletIds;
        try {
            attribution.applyStatementTimeout(jdbc::execute);
            directory.botsOf(tenantId, userId, PnlMarket.REAL).forEach(bot -> bots.put(bot.botId(), bot));
            walletIds = wallets.botWalletIds(tenantId, userId);
            if (bots.isEmpty() && walletIds.isEmpty()) {
                metrics.attributionRead(CostPath.PNL, PnlRealMetrics.Outcome.OK, elapsed(started));
                return new Pass(empty(window), block);
            }
            int[] count = {0};
            orders.streamSettledOrders(tenantId, userId, attribution, view -> {
                if (++count[0] > maxOrders) {
                    throw new RealPnlUnavailable(RealPnlUnavailable.TOO_MANY_ORDERS);
                }
                OrderAttribution order = attributionOf(view);
                order.botId().ifPresent(botId -> bots.computeIfAbsent(botId, id -> spotBot(tenantId, userId, id)));
                index.put(order.realOrderId(), order);
                if (order.virtualAttempt()) {
                    attempts.add(order);
                }
            });
        } catch (UnknownBotOfAnOrder unknown) {
            metrics.attributionRead(CostPath.PNL, PnlRealMetrics.Outcome.UNKNOWN_BOT, elapsed(started));
            return unknownBot(window, block);
        } catch (RuntimeException failure) {
            metrics.attributionRead(
                    CostPath.PNL,
                    ReadDeadline.isTimeout(failure) ? PnlRealMetrics.Outcome.TIMEOUT : PnlRealMetrics.Outcome.ERROR,
                    elapsed(started));
            throw failure;
        }
        metrics.attributionRead(CostPath.PNL, PnlRealMetrics.Outcome.OK, elapsed(started));

        // Steps 4 to 9: the rows in network order, the prices once, the reconciliation; under the deadline of the
        // block.
        List<BotEntry> holders = new ArrayList<>(bots.values());
        holders.sort(Comparator.comparing(BotEntry::createdAt)
                .thenComparing(bot -> bot.botId().toString().toLowerCase(Locale.ROOT)));
        RealPnlEngine engine =
                new RealPnlEngine(method, holders, attribution(tenantId, userId, index, bots), window, attempts);
        try {
            if (!walletIds.isEmpty()) {
                block.applyStatementTimeout(jdbc::execute);
                ledger.streamInNetworkOrder(tenantId, walletIds, TRADING, block, engine::accept);
            }
            block.check();
            Set<String> open = engine.openMints();
            Map<String, Optional<BigDecimal>> quotes = open.isEmpty() ? Map.of() : prices.lamportPrices(open);
            block.check();
            return new Pass(engine.finish(quotes, clock.instant()), block);
        } catch (RealPnlEngine.UnknownBot | UnknownBotOfAnOrder unknown) {
            return unknownBot(window, block);
        }
    }

    /** The orders of the index, or read on the spot (confirmed meanwhile); the bots of the person, or read on the spot. */
    private RealPnlEngine.Attribution attribution(
            UUID tenantId, UUID userId, Map<UUID, OrderAttribution> index, Map<UUID, BotEntry> bots) {
        return new RealPnlEngine.Attribution() {
            @Override
            public Optional<OrderAttribution> order(UUID realOrderId) {
                OrderAttribution known = index.get(realOrderId);
                if (known != null) {
                    return Optional.of(known);
                }
                Optional<OrderAttribution> read =
                        orders.settledOrder(tenantId, userId, realOrderId).map(RealBotPnl::attributionOf);
                read.ifPresent(found -> index.put(realOrderId, found));
                return read;
            }

            @Override
            public Optional<BotEntry> bot(UUID botId) {
                BotEntry known = bots.get(botId);
                if (known != null) {
                    return Optional.of(known);
                }
                Optional<BotEntry> read =
                        directory.botOf(tenantId, userId, botId).filter(bot -> bot.market() == PnlMarket.REAL);
                read.ifPresent(found -> bots.put(botId, found));
                return read;
            }
        };
    }

    /** BR3.4: a bot of an order outside the bots of step 1, read on the spot; not a REAL bot of the person stops. */
    private BotEntry spotBot(UUID tenantId, UUID userId, UUID botId) {
        return directory
                .botOf(tenantId, userId, botId)
                .filter(bot -> bot.market() == PnlMarket.REAL)
                .orElseThrow(UnknownBotOfAnOrder::new);
    }

    static OrderAttribution attributionOf(RealOrderView order) {
        return new OrderAttribution(
                order.realOrderId(),
                order.botId(),
                "USER_LIQUIDATION".equals(order.origin()),
                order.state() == RealOrderState.FAILED,
                order.mint(),
                order.feeLamports().orElse(0L),
                order.feePayer().map("MAIN_WALLET"::equals).orElse(false),
                order.updatedAt(),
                order.botWalletId());
    }

    /**
     * The deadline of the block: the {@code statement_timeout} the pool of the blocks set (what is left of it), or the
     * block-timeout when the read runs on its own (no statement timeout).
     */
    private Duration blockBudget() {
        String raw = jdbc.queryForObject("show statement_timeout", String.class);
        Duration set = parse(raw);
        return set.isZero() || set.compareTo(blockTimeout) > 0 ? blockTimeout : set;
    }

    /** {@code show statement_timeout}: {@code 0}, {@code 750ms}, {@code 1s}, {@code 2min}... ; anything else is unset. */
    static Duration parse(String raw) {
        if (raw == null) {
            return Duration.ZERO;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("min")) {
                return Duration.ofMinutes(
                        Long.parseLong(value.substring(0, value.length() - 3).trim()));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(
                        Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException unexpected) {
            // An unexpected format of the server: no limit of its own, the block-timeout applies.
            return Duration.ZERO;
        }
    }

    private Pass unknownBot(PnlWindow window, ReadDeadline block) {
        RealModePnl nothing = new RealModePnl(List.of(), Optional.empty(), zero(), false);
        return new Pass(new RealPnlResult(nothing, RealPnlReconciliation.unknownBot(window), List.of()), block);
    }

    /** F1 step 2: no REAL bot and no bot wallet: the empty block, reconciled by construction. */
    private RealPnlResult empty(PnlWindow window) {
        return new RealPnlResult(
                new RealModePnl(List.of(), Optional.empty(), zero(), true),
                new ReconciliationResult(
                        window, ReconciliationResult.Status.MATCH, 0, 0, Optional.of(0L), List.of(), Map.of()),
                List.of());
    }

    private RealModeTotal zero() {
        return new RealModeTotal(0, new LamportsValue.Known(0), 0, new LamportsValue.Known(0), clock.instant());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    /** BR3.4: an order of the stream names a bot that is not a REAL bot of the person. */
    private static final class UnknownBotOfAnOrder extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownBotOfAnOrder() {
            super("An order of the person names a bot that is not one of her REAL bots");
        }
    }
}
