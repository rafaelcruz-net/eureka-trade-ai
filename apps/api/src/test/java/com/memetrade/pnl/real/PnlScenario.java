package com.memetrade.pnl.real;

import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (test support of Etapa 18): a REAL ledger of one bot wallet written as the confirmations of
 * the application write it ({@code RealOrderLedger}, {@code FillPosting}): the rows of each event with the reference
 * {@code order:<id>} or {@code conditional:<id>}, its signature and the idempotency keys of the writers, and the orders
 * the P&L reads (C13). Each event gets the next slot unless {@link #sameSlot()}; after {@link #slotMissing()} the rows
 * inherit the slot of the last recorded row (BR1.3). Each event is one minute after the previous one unless
 * {@link #at(String)} says otherwise. Values in lamports and base units; synthetic mints and signatures only.
 */
public final class PnlScenario {

    public static final String XYZ = "XYZ";
    public static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    public final UUID wallet = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    /** A second bot wallet of the same person (review R-04); it sorts after {@link #wallet}. */
    public static final UUID SECOND_WALLET = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private UUID current = wallet;
    private final Map<UUID, BotEntry> bots = new LinkedHashMap<>();
    private final Map<UUID, OrderAttribution> orders = new HashMap<>();
    private final List<OrderAttribution> virtualAttempts = new ArrayList<>();
    private final List<NetworkOrderedEntry> entries = new ArrayList<>();
    private Instant at = Instant.parse("2026-11-10T09:00:00Z");
    private long slot = 1_000;
    private long lastRecordedSlot;
    private int counter;
    private boolean nextSameSlot;
    private boolean missing;
    private String nextSignature;
    private Duration feeDelay = Duration.ZERO;
    private String mint = XYZ;

    // ----- the bots -----

    public PnlScenario bot(Holder holder) {
        return bot(holder, BotPnlStatus.ACTIVE);
    }

    public PnlScenario bot(Holder holder, BotPnlStatus status) {
        UUID id = holder.botId().orElseThrow();
        bots.put(
                id,
                new BotEntry(
                        id,
                        "Robo " + id.toString().substring(34),
                        PnlMarket.REAL,
                        "MOMENTUM_VOLUME",
                        status,
                        holder.botCreatedAt()));
        return this;
    }

    public List<BotEntry> bots() {
        return List.copyOf(bots.values());
    }

    // ----- how the next event is keyed -----

    /** The instant of the next event (and of the following ones, one minute apart). */
    public PnlScenario at(String instant) {
        at = Instant.parse(instant);
        return this;
    }

    /** The next event has the slot of the previous one. */
    PnlScenario sameSlot() {
        nextSameSlot = true;
        return this;
    }

    /** The next event uses this signature (to order two events of the same slot, BR1.2). */
    PnlScenario signature(String signature) {
        nextSignature = signature;
        return this;
    }

    /** From now on the rows have no recorded slot (SLOT_MISSING) and inherit it (BR1.3). */
    PnlScenario slotMissing() {
        missing = true;
        return this;
    }

    /** The NETWORK_FEE and RENT rows of the next event are recorded {@code delay} after its main row (BR9.5). */
    PnlScenario feeDelay(Duration delay) {
        feeDelay = delay;
        return this;
    }

    public PnlScenario mint(String mint) {
        this.mint = mint;
        return this;
    }

    /** The next events and orders are of this bot wallet of the person (review R-04). */
    public PnlScenario inWallet(UUID botWalletId) {
        this.current = botWalletId;
        return this;
    }

    // ----- the events -----

    /** A confirmed buy of a bot; the bot wallet pays the fee (a NETWORK_FEE row). */
    public UUID buy(Holder bot, long units, long paid, long fee) {
        return buyWithRent(bot, units, paid, 0, fee);
    }

    /** A confirmed buy that created the token account: its RENT enters its cost (BR2.1). */
    UUID buyWithRent(Holder bot, long units, long paid, long rent, long fee) {
        UUID id = order(Optional.of(bot), false, false, fee, false);
        Event event = event("order:" + id, id.toString());
        event.row(RealEntryKind.SWAP_FILL_IN, -paid, units, ":SWAP_FILL_IN");
        if (rent > 0) {
            event.feeRow(RealEntryKind.RENT, -rent, ":RENT");
        }
        if (fee > 0) {
            event.feeRow(RealEntryKind.NETWORK_FEE, -fee, ":NETWORK_FEE");
        }
        return done(id);
    }

    /** A confirmed buy in the mode Assinada: the main wallet paid the fee; it is only on the order (BR6.3). */
    public UUID signedBuy(Holder bot, long units, long paid, long fee) {
        UUID id = order(Optional.of(bot), false, false, fee, true);
        event("order:" + id, id.toString()).row(RealEntryKind.SWAP_FILL_IN, -paid, units, ":SWAP_FILL_IN");
        return done(id);
    }

    /** A buy whose order is gone (purged, BR5.4): the ledger has it, the orders do not. */
    UUID purgedBuy(long units, long paid) {
        UUID id = UUID.randomUUID();
        event("order:" + id, id.toString()).row(RealEntryKind.SWAP_FILL_IN, -paid, units, ":SWAP_FILL_IN");
        return done(id);
    }

    /** The sale of a bot (BR3.2 OWN_SALE). */
    public UUID ownSale(Holder bot, long units, long received, long fee) {
        UUID id = order(Optional.of(bot), false, false, fee, false);
        sale("order:" + id, id.toString(), units, received, fee, 0);
        return done(id);
    }

    /** The sale of a bot with a RENT outside any buy (BR6.6). */
    UUID ownSaleWithRent(Holder bot, long units, long received, long fee, long rent) {
        UUID id = order(Optional.of(bot), false, false, fee, false);
        sale("order:" + id, id.toString(), units, received, fee, rent);
        return done(id);
    }

    /** The sale of a bot in the mode Assinada: the fee is only on the order. */
    UUID signedOwnSale(Holder bot, long units, long received, long fee) {
        UUID id = order(Optional.of(bot), false, false, fee, true);
        sale("order:" + id, id.toString(), units, received, 0, 0);
        return done(id);
    }

    /** A sale whose order is gone: UNOWNED is the seller (BR3.2, BR5.4). */
    UUID purgedSale(long units, long received, long fee) {
        UUID id = UUID.randomUUID();
        sale("order:" + id, id.toString(), units, received, fee, 0);
        return done(id);
    }

    /** A sale of the "vender tudo e sacar" (origin USER_LIQUIDATION). */
    public UUID liquidation(long units, long received, long fee) {
        return liquidationWithRent(units, received, fee, 0);
    }

    UUID liquidationWithRent(long units, long received, long fee, long rent) {
        UUID id = order(Optional.empty(), true, false, fee, false);
        sale("order:" + id, id.toString(), units, received, fee, rent);
        return done(id);
    }

    /** The executed part of a conditional ({@code conditional:<id>}, FillPosting); no order. */
    public UUID conditional(long units, long received, long fee) {
        UUID id = UUID.randomUUID();
        Event event = event("conditional:" + id, null);
        event.row(RealEntryKind.SWAP_FILL_OUT, received, units, ":fill");
        if (fee > 0) {
            event.feeRow(RealEntryKind.NETWORK_FEE, -fee, ":fee");
        }
        return done(id);
    }

    /** An order of a bot that failed on chain; the bot wallet paid the fee (BR6.2). */
    public UUID failed(Holder bot, long fee) {
        UUID id = order(Optional.of(bot), false, true, fee, false);
        event("order:" + id, id.toString()).feeRow(RealEntryKind.NETWORK_FEE, -fee, ":NETWORK_FEE");
        return done(id);
    }

    /** A liquidation that failed on chain; the bot wallet paid the fee (BR6.2). */
    UUID failedLiquidation(long fee) {
        UUID id = order(Optional.empty(), true, true, fee, false);
        event("order:" + id, id.toString()).feeRow(RealEntryKind.NETWORK_FEE, -fee, ":NETWORK_FEE");
        return done(id);
    }

    /** An order of a purged record that failed on chain (BR5.4). */
    UUID purgedFailed(long fee) {
        UUID id = UUID.randomUUID();
        event("order:" + id, id.toString()).feeRow(RealEntryKind.NETWORK_FEE, -fee, ":NETWORK_FEE");
        return done(id);
    }

    /** An order of a bot that failed in the mode Assinada: no row, a virtual attempt (BR6.2, BR6.3). */
    public UUID signedFailed(Holder bot, long fee) {
        UUID id = order(Optional.of(bot), false, true, fee, true);
        virtualAttempts.add(orders.get(id));
        return done(id);
    }

    /** A liquidation that failed in the mode Assinada: no row, a virtual attempt shared like the failed one. */
    UUID signedFailedLiquidation(long fee) {
        UUID id = order(Optional.empty(), true, true, fee, true);
        virtualAttempts.add(orders.get(id));
        return done(id);
    }

    /** Rows that are not trading: a deposit, a withdrawal with its fee, a reserve and a compensation (BR6.5). */
    void notTrading() {
        String sig = nextSignature();
        Instant when = at;
        add(RealEntryKind.DEPOSIT, 500_000_000L, null, null, "dep:" + sig, "dep:" + sig, sig, when);
        add(RealEntryKind.WITHDRAWAL, -100_000_000L, null, null, "wd:w" + counter, "wd:w" + counter, null, when);
        add(RealEntryKind.NETWORK_FEE, -5_000L, null, null, "wd:w" + counter, "wd:w" + counter + ":fee", null, when);
        add(RealEntryKind.RESERVE, 7_000L, null, null, "order:" + UUID.randomUUID(), "r" + counter, null, when);
        add(RealEntryKind.COMPENSATION, 3L, mint, 2L, "comp:" + counter, "comp:" + counter, null, when);
        advance();
    }

    // ----- running it -----

    public List<NetworkOrderedEntry> entriesInNetworkOrder() {
        // As NETWORK_ORDER_SQL: the wallets one after the other, each in network order.
        List<NetworkOrderedEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(NetworkOrderedEntry::botWalletId).thenComparing(NetworkOrderedEntry::key));
        return ordered;
    }

    public Map<UUID, OrderAttribution> orders() {
        return Map.copyOf(orders);
    }

    List<OrderAttribution> virtualAttempts() {
        return List.copyOf(virtualAttempts);
    }

    RealPnlEngine.Attribution attribution() {
        Map<UUID, BotEntry> known = Map.copyOf(bots);
        Map<UUID, OrderAttribution> byId = orders();
        return new RealPnlEngine.Attribution() {
            @Override
            public Optional<OrderAttribution> order(UUID realOrderId) {
                return Optional.ofNullable(byId.get(realOrderId));
            }

            @Override
            public Optional<BotEntry> bot(UUID botId) {
                return Optional.ofNullable(known.get(botId));
            }
        };
    }

    RealPnlResult run(PnlWindow window, Map<String, Optional<BigDecimal>> prices) {
        return run(window, prices, entriesInNetworkOrder());
    }

    RealPnlResult run(PnlWindow window, Map<String, Optional<BigDecimal>> prices, List<NetworkOrderedEntry> stream) {
        RealPnlEngine engine = new RealPnlEngine(new RealCostBasis(), bots(), attribution(), window, virtualAttempts());
        stream.forEach(engine::accept);
        return engine.finish(prices, NOW);
    }

    public RealPnlResult all() {
        return run(PnlWindow.all(), Map.of());
    }

    /** A price of {@code lamports} per base unit. */
    public static Map<String, Optional<BigDecimal>> price(String mint, long lamports) {
        return Map.of(mint, Optional.of(BigDecimal.valueOf(lamports)));
    }

    public static RealBotPnlLine line(RealPnlResult result, Holder bot) {
        return result.pnl().lines().stream()
                .filter(l -> l.botId().equals(bot.botId().orElseThrow()))
                .findFirst()
                .orElseThrow();
    }

    // ----- internals -----

    private UUID order(Optional<Holder> bot, boolean liquidation, boolean failed, long fee, boolean mainWallet) {
        UUID id = UUID.randomUUID();
        orders.put(
                id,
                new OrderAttribution(
                        id,
                        bot.flatMap(Holder::botId),
                        liquidation,
                        failed,
                        mint,
                        fee,
                        mainWallet,
                        at,
                        Optional.of(current)));
        return id;
    }

    private void sale(String reference, String keyBase, long units, long received, long fee, long rent) {
        Event event = event(reference, keyBase);
        event.row(RealEntryKind.SWAP_FILL_OUT, received, units, ":SWAP_FILL_OUT");
        if (rent > 0) {
            event.feeRow(RealEntryKind.RENT, -rent, ":RENT");
        }
        if (fee > 0) {
            event.feeRow(RealEntryKind.NETWORK_FEE, -fee, ":NETWORK_FEE");
        }
    }

    private Event event(String reference, String keyBase) {
        if (!nextSameSlot) {
            slot++;
        }
        nextSameSlot = false;
        String sig = nextSignature();
        Event event = new Event(reference, keyBase == null ? "cond:" + sig : keyBase, sig, at, at.plus(feeDelay));
        feeDelay = Duration.ZERO;
        return event;
    }

    private UUID done(UUID id) {
        advance();
        return id;
    }

    private void advance() {
        at = at.plus(Duration.ofMinutes(1));
    }

    private String nextSignature() {
        counter++;
        if (nextSignature != null) {
            String chosen = nextSignature;
            nextSignature = null;
            return chosen;
        }
        return String.format("Sig%06d", counter);
    }

    private final class Event {
        private final String reference;
        private final String keyBase;
        private final String signature;
        private final Instant main;
        private final Instant fees;

        Event(String reference, String keyBase, String signature, Instant main, Instant fees) {
            this.reference = reference;
            this.keyBase = keyBase;
            this.signature = signature;
            this.main = main;
            this.fees = fees;
        }

        void row(RealEntryKind kind, long lamports, long units, String suffix) {
            add(kind, lamports, mint, units, reference, keyBase + suffix, signature, main);
        }

        void feeRow(RealEntryKind kind, long lamports, String suffix) {
            add(kind, lamports, null, null, reference, keyBase + suffix, signature, fees);
        }
    }

    private void add(
            RealEntryKind kind,
            long lamports,
            String rowMint,
            Long units,
            String reference,
            String idempotencyKey,
            String signature,
            Instant createdAt) {
        boolean recorded = !missing && signature != null;
        long effective;
        if (recorded) {
            effective = slot;
            lastRecordedSlot = slot;
        } else {
            effective = lastRecordedSlot;
        }
        SlotStatus status =
                recorded ? SlotStatus.RECORDED : signature == null ? SlotStatus.NO_NETWORK_TX : SlotStatus.SLOT_MISSING;
        NetworkOrderKey key = new NetworkOrderKey(
                effective,
                !recorded,
                Optional.ofNullable(signature),
                createdAt,
                reference,
                NetworkOrderKey.kindRank(kind),
                idempotencyKey);
        entries.add(new NetworkOrderedEntry(
                UUID.randomUUID(),
                current,
                kind,
                lamports,
                Optional.ofNullable(rowMint),
                Optional.ofNullable(units),
                Optional.ofNullable(signature),
                reference,
                createdAt,
                key,
                status));
    }
}
