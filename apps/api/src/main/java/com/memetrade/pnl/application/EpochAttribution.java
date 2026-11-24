package com.memetrade.pnl.application;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.real.Holder;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (BR2.1, BR3.1, BR3.2, BR3.4, BR5.4), extracted in the U4 (Etapa 9, NFR2.9, NFR8.5) so the
 * read of the cost of a wallet ({@link AttributedCostReader}) and the read of the own holdings of a bot
 * ({@link RealBotHoldingsReader}) share ONE accumulation, without a copy: the rows of a bot wallet in network order
 * applied to the accumulator of {@link RealCostMethod}, each swap fill attributed to its holder (the bot of the order;
 * a sale without bot for a liquidation or a conditional; UNOWNED for an order gone), a buy waiting for the RENT of its
 * reference (BR2.1). The orders of the rows come from an index the caller filled, or are read on the spot by id; a bot
 * outside the REAL bots of the person fails the read ({@link UnknownBot}, BR3.4).
 */
final class EpochAttribution {

    private final UUID tenantId;
    private final UUID userId;
    private final Map<UUID, Holder> holders;
    private final Map<UUID, Attribution> index;
    private final java.util.Set<UUID> absent;
    private final RealExecutionPort orders;
    private final BotDirectory bots;
    final RealCostMethod.Accumulator wallet;
    long rows;
    private NetworkOrderedEntry pendingBuy;
    private Holder pendingHolder;
    private long pendingPaid;

    EpochAttribution(
            UUID tenantId,
            UUID userId,
            Map<UUID, Holder> holders,
            Map<UUID, Attribution> index,
            RealCostMethod.Accumulator wallet,
            RealExecutionPort orders,
            BotDirectory bots) {
        this(tenantId, userId, holders, index, null, wallet, orders, bots);
    }

    /**
     * 261121-paper-real-unico U4 (review R-01): with {@code absent}, the ids of orders already looked up and not found
     * in this read, so an order the index does not have is read once, not once per row that names it. Only for a read
     * in ONE snapshot (the reader of the own holdings, REPEATABLE READ), where a second lookup gives the same answer.
     * {@code null} keeps the behaviour of the U2.
     */
    EpochAttribution(
            UUID tenantId,
            UUID userId,
            Map<UUID, Holder> holders,
            Map<UUID, Attribution> index,
            java.util.Set<UUID> absent,
            RealCostMethod.Accumulator wallet,
            RealExecutionPort orders,
            BotDirectory bots) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.holders = holders;
        this.index = index;
        this.absent = absent;
        this.wallet = wallet;
        this.orders = orders;
        this.bots = bots;
    }

    /** What the cost needs of an order: its bot, its origin and its side. */
    record Attribution(Optional<UUID> botId, boolean liquidation, RealSide side) {

        static Attribution of(RealOrderView order) {
            return new Attribution(order.botId(), "USER_LIQUIDATION".equals(order.origin()), order.side());
        }
    }

    /** BR3.4: an order names a bot that is not a REAL bot of the person. */
    static final class UnknownBot extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownBot() {
            super("An order of the wallet names a bot that is not a REAL bot of the person");
        }
    }

    void accept(NetworkOrderedEntry entry) {
        rows++;
        if (pendingBuy != null) {
            if (entry.kind() == RealEntryKind.RENT && entry.reference().equals(pendingBuy.reference())) {
                pendingPaid = Math.addExact(pendingPaid, Math.negateExact(entry.lamports()));
                return;
            }
            if (entry.kind() == RealEntryKind.NETWORK_FEE && entry.reference().equals(pendingBuy.reference())) {
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
    private Optional<CostHolder> sellerOf(String reference) {
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
        Optional<UUID> id = orderId(reference);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Attribution known = index.get(id.get());
        if (known != null) {
            return Optional.of(known);
        }
        if (absent != null && absent.contains(id.get())) {
            return Optional.empty();
        }
        Optional<Attribution> read =
                orders.settledOrder(tenantId, userId, id.get()).map(Attribution::of);
        read.ifPresentOrElse(found -> index.put(id.get(), found), () -> {
            if (absent != null) {
                absent.add(id.get());
            }
        });
        return read;
    }

    /**
     * The id of {@code order:<id>}; empty for any other reference, and for a reference of an order that is not an id of
     * real_order (it has no order, like a purged one).
     */
    static Optional<UUID> orderId(String reference) {
        if (!reference.startsWith("order:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(reference.substring("order:".length())));
        } catch (IllegalArgumentException notAnOrder) {
            return Optional.empty();
        }
    }

    /** BR3.4: a bot that is not a REAL bot of the person fails the read (after reading it on the spot). */
    private Holder bot(UUID botId) {
        Holder holder = holders.get(botId);
        if (holder != null) {
            return holder;
        }
        Optional<BotEntry> read = bots.botOf(tenantId, userId, botId).filter(entry -> entry.market() == PnlMarket.REAL);
        if (read.isEmpty()) {
            throw new UnknownBot();
        }
        Holder found = Holder.bot(read.get().botId(), read.get().createdAt());
        holders.put(botId, found);
        return found;
    }
}
