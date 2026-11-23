package com.memetrade.pnl.real;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealCostMethod.CostPart;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealModeTotal;
import com.memetrade.pnl.api.UnownedPnlLine;
import com.memetrade.pnl.real.ProrationPart.Origin;
import com.memetrade.pnl.real.RealProration.Holding;
import com.memetrade.pnl.real.RealProration.SaleKind;
import com.memetrade.pnl.real.TradeEvent.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (F1 to F3, F5; BR1.1, BR1.6, BR2.1 to BR2.5, BR3.1 to BR3.4, BR4.x, BR5.x, BR6.x, BR7.x,
 * BR8.1 to BR8.5, BR10.1): the REAL P&L by bot of ONE person in ONE pass over its ledger rows in network order. Pure: no
 * I/O (the attribution is given), no clock (the instant of "now" is given), no floating point; {@code long} with exact
 * operations, {@link java.math.BigInteger} and {@link BigDecimal} only for the products of the proration and of a price.
 *
 * <ol>
 *   <li>{@link #accept} takes the rows one by one (never a list), keeps only the trading rows (BR6.5) and groups the
 *       rows of one (wallet, reference, signature) into a {@link TradeEvent}; the open events of a slot are applied when
 *       a row of a greater slot or of another wallet arrives, in the order of their main row (F1 step 5). The failed
 *       attempts of the mode Assinada, without a row, are applied by their instant among the rows (BR6.2).
 *   <li>Each event is applied (F2): the cost by {@link RealCostMethod} (the one average cost, BR2.4), the proration by
 *       {@link RealProration} (BR4), the fees (BR6), the realized gross (BR7.1); at the same time the side of the ledger
 *       is accumulated from the rows and the orders only (F3 step 1), by the one instant of each event (BR7.6).
 *   <li>{@link #finish} values the open positions with the prices of now (BR7.2, BR7.3), builds the lines, the line "Sem
 *       bot" and the total of the mode from the ledger side (BR7.5), and reconciles them exactly (BR8).
 * </ol>
 *
 * An order that names a bot outside the REAL bots of the person stops the pass with {@link UnknownBot} (BR3.4); it never
 * goes to the line "Sem bot".
 */
public final class RealPnlEngine {

    static final String ORDER = "order:";
    static final String CONDITIONAL = "conditional:";
    static final String ORDER_RECORD_MISSING = "ORDER_RECORD_MISSING";
    static final String UNOWNED_EXCESS = "UNOWNED_EXCESS";
    static final String UNOWNED_NO_COST = "UNOWNED_NO_COST";
    private static final Set<RealEntryKind> TRADING = EnumSet.of(
            RealEntryKind.SWAP_FILL_IN, RealEntryKind.SWAP_FILL_OUT, RealEntryKind.NETWORK_FEE, RealEntryKind.RENT);

    /** The attribution the pass reads: the orders of the person (C13) and its REAL bots (C8), both already filtered. */
    public interface Attribution {

        /** The settled order of the person with this id; empty when it is not hers or is gone. */
        Optional<OrderAttribution> order(UUID realOrderId);

        /** A REAL bot of the person; empty for anything else (another person, a PAPER bot, a draft). */
        Optional<BotEntry> bot(UUID botId);
    }

    /** BR3.4: an order names a bot that is not a REAL bot of the person; the pass stops, nothing is delivered. */
    public static final class UnknownBot extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownBot() {
            super("An order names a bot that is not a REAL bot of the person");
        }
    }

    private final RealCostMethod method;
    private final Attribution attribution;
    private final PnlWindow window;
    private final Map<UUID, Holder> holders = new LinkedHashMap<>();
    private final Map<Holder, Line> lines = new LinkedHashMap<>();
    private final Map<Holder, BotEntry> entries = new HashMap<>();
    private final Line unowned = new Line();
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    /** Review R-04: the virtual attempts of each bot wallet, in order of settlement; applied in their own wallet. */
    private final Map<UUID, Deque<OrderAttribution>> walletAttempts = new TreeMap<>();

    /** The virtual attempts of an order read without its wallet: applied in the wallet being read. */
    private final Deque<OrderAttribution> walletless = new ArrayDeque<>();

    private final Ledger ledgerAll = new Ledger();
    private final Ledger ledgerWindow = new Ledger();
    private final Map<EventKey, Assembly> open = new LinkedHashMap<>();
    private long eventSumsDiff;
    private UUID currentWallet;
    private long currentSlot = -1;
    private boolean closed;

    /**
     * @param bots the REAL bots of the person, paused and removed included, in order of creation (C8)
     * @param virtualAttempts the failed orders of the mode Assinada of the person (no row in the ledger)
     */
    public RealPnlEngine(
            RealCostMethod method,
            List<BotEntry> bots,
            Attribution attribution,
            PnlWindow window,
            List<OrderAttribution> virtualAttempts) {
        this.method = Objects.requireNonNull(method, "method");
        this.attribution = Objects.requireNonNull(attribution, "attribution");
        this.window = Objects.requireNonNull(window, "window");
        for (BotEntry bot : bots) {
            register(bot);
        }
        List<OrderAttribution> due = new ArrayList<>(virtualAttempts.stream()
                .filter(OrderAttribution::virtualAttempt)
                .toList());
        due.sort(Comparator.comparing(OrderAttribution::settledAt)
                .thenComparing(order -> order.realOrderId().toString()));
        for (OrderAttribution order : due) {
            order.botWalletId()
                    .ifPresentOrElse(
                            wallet -> walletAttempts
                                    .computeIfAbsent(wallet, id -> new ArrayDeque<>())
                                    .add(order),
                            () -> walletless.add(order));
        }
    }

    // ----------------------------------------------------------------- the stream

    /** Takes the next row of the stream, in network order (C12), grouped by wallet. */
    public void accept(NetworkOrderedEntry entry) {
        if (closed) {
            throw new IllegalStateException("the pass is over");
        }
        if (currentWallet != null && !entry.botWalletId().equals(currentWallet)) {
            // The stream leaves a wallet for good (C12 reads the wallets one after the other): what is left of its
            // attempts settled after all its rows.
            flush();
            drain(currentWallet);
        }
        Deque<OrderAttribution> own = walletAttempts.get(entry.botWalletId());
        while (own != null && !own.isEmpty() && own.peek().settledAt().isBefore(entry.createdAt())) {
            flush();
            apply(attempt(own.poll(), entry.botWalletId()));
        }
        while (!walletless.isEmpty() && walletless.peek().settledAt().isBefore(entry.createdAt())) {
            flush();
            apply(attempt(walletless.poll(), entry.botWalletId()));
        }
        if (!entry.botWalletId().equals(currentWallet) || entry.key().effectiveSlot() != currentSlot) {
            flush();
            currentWallet = entry.botWalletId();
            currentSlot = entry.key().effectiveSlot();
        }
        if (!TRADING.contains(entry.kind())
                || !(entry.reference().startsWith(ORDER) || entry.reference().startsWith(CONDITIONAL))) {
            return;
        }
        open.computeIfAbsent(
                        new EventKey(entry.botWalletId(), entry.reference(), entry.txSignature()),
                        key -> new Assembly(entry.botWalletId(), entry.reference()))
                .add(entry);
    }

    /** The mints with an open position after the whole stream (the only prices the pass needs, NFR1.13). */
    public Set<String> openMints() {
        close();
        Set<String> mints = new TreeSet<>();
        for (Wallet wallet : wallets.values()) {
            wallet.accumulator.openPositions().forEach(position -> mints.add(position.mint()));
        }
        return mints;
    }

    /**
     * Values the open positions with the prices of now and reconciles.
     *
     * @param prices lamports per base unit of each open mint; empty or absent is unknown, never zero
     * @param asOf the instant of now (the {@code unrealizedAsOf} of the block)
     */
    public RealPnlResult finish(Map<String, Optional<BigDecimal>> prices, Instant asOf) {
        close();
        Map<Holder, long[]> unrealized = new HashMap<>();
        Set<Holder> unknown = new LinkedHashSet<>();
        long modeUnrealized = 0;
        boolean modeUnknown = false;
        long costDiff = 0;
        long quantityDiff = 0;
        Map<String, long[]> byMint = new TreeMap<>();
        Map<String, Boolean> pricedByMint = new HashMap<>();
        for (Wallet wallet : wallets.values()) {
            Map<String, RealPosition> open = new HashMap<>();
            for (RealPosition position : wallet.accumulator.openPositions()) {
                open.put(position.mint(), position);
            }
            Set<String> mints = new TreeSet<>(wallet.books.keySet());
            mints.addAll(open.keySet());
            mints.addAll(wallet.quantity.keySet());
            for (String mint : mints) {
                RealPosition position = open.get(mint);
                long units = position == null ? 0 : position.tokenBaseUnits();
                long cost = position == null ? 0 : known(position.cost());
                Book book = wallet.books.getOrDefault(mint, new Book());
                costDiff = Math.addExact(costDiff, Math.abs(book.buyCost - Math.addExact(book.costRemoved, cost)));
                quantityDiff =
                        Math.addExact(quantityDiff, Math.abs(book.buyUnits - Math.addExact(book.soldUnits, units)));
                quantityDiff = Math.addExact(quantityDiff, Math.abs(wallet.quantity.getOrDefault(mint, 0L) - units));
                if (units == 0) {
                    continue;
                }
                List<Holding> holdings = holdings(wallet, mint);
                Optional<BigDecimal> price = prices.getOrDefault(mint, Optional.empty());
                long[] total = byMint.computeIfAbsent(mint, m -> new long[3]);
                total[0] = Math.addExact(total[0], units);
                total[1] = Math.addExact(total[1], cost);
                if (price.isEmpty() || price.get().signum() < 0) {
                    holdings.forEach(h -> unknown.add((Holder) h.holder()));
                    modeUnknown = true;
                    pricedByMint.put(mint, false);
                    continue;
                }
                long value = price.get()
                        .multiply(BigDecimal.valueOf(units))
                        .setScale(0, RoundingMode.DOWN)
                        .longValueExact();
                long[] shares = LargestRemainder.share(
                        value, holdings.stream().mapToLong(Holding::units).toArray());
                for (int i = 0; i < holdings.size(); i++) {
                    Holding holding = holdings.get(i);
                    long[] gain = unrealized.computeIfAbsent((Holder) holding.holder(), h -> new long[1]);
                    gain[0] = Math.addExact(gain[0], Math.subtractExact(shares[i], holding.cost()));
                }
                modeUnrealized = Math.addExact(modeUnrealized, Math.subtractExact(value, cost));
                total[2] = Math.addExact(total[2], value);
                pricedByMint.putIfAbsent(mint, true);
            }
        }
        List<PositionValue> positions = new ArrayList<>();
        byMint.forEach((mint, total) -> {
            boolean priced = pricedByMint.getOrDefault(mint, false);
            positions.add(new PositionValue(
                    mint,
                    total[0],
                    total[1],
                    priced ? Optional.of(total[2]) : Optional.empty(),
                    priced ? Optional.of(Math.subtractExact(total[2], total[1])) : Optional.empty()));
        });

        List<RealBotPnlLine> botLines = new ArrayList<>();
        long linesRealizedWindow = 0;
        long linesFeesWindow = 0;
        long linesRealizedAll = 0;
        long linesFeesAll = 0;
        long linesUnrealized = 0;
        boolean linesUnknown = false;
        for (Map.Entry<Holder, Line> entry : lines.entrySet()) {
            Holder holder = entry.getKey();
            Line line = entry.getValue();
            LamportsValue gain = unrealized(holder, unrealized, unknown);
            botLines.add(new RealBotPnlLine(
                    holder.bot(),
                    entries.get(holder).status(),
                    line.realizedWindow,
                    gain,
                    line.feesWindow,
                    total(line.realizedWindow, gain, line.feesWindow),
                    line.shares));
            linesRealizedWindow = Math.addExact(linesRealizedWindow, line.realizedWindow);
            linesFeesWindow = Math.addExact(linesFeesWindow, line.feesWindow);
            linesRealizedAll = Math.addExact(linesRealizedAll, line.realizedAll);
            linesFeesAll = Math.addExact(linesFeesAll, line.feesAll);
            if (gain instanceof LamportsValue.Known known) {
                linesUnrealized = Math.addExact(linesUnrealized, known.lamports());
            } else {
                linesUnknown = true;
            }
        }
        LamportsValue unownedGain = unrealized(Holder.UNOWNED, unrealized, unknown);
        boolean unownedOpen = unknown.contains(Holder.UNOWNED) || unrealized.containsKey(Holder.UNOWNED);
        Optional<UnownedPnlLine> unownedLine = Optional.empty();
        if (unowned.touched || unownedOpen) {
            unownedLine = Optional.of(
                    new UnownedPnlLine(unowned.realizedWindow, unownedGain, unowned.feesWindow, unowned.reasons));
            linesRealizedWindow = Math.addExact(linesRealizedWindow, unowned.realizedWindow);
            linesFeesWindow = Math.addExact(linesFeesWindow, unowned.feesWindow);
            linesRealizedAll = Math.addExact(linesRealizedAll, unowned.realizedAll);
            linesFeesAll = Math.addExact(linesFeesAll, unowned.feesAll);
            if (unownedGain instanceof LamportsValue.Known known) {
                linesUnrealized = Math.addExact(linesUnrealized, known.lamports());
            } else {
                linesUnknown = true;
            }
        }
        boolean unownedHasValue = unowned.realizedAll != 0
                || unowned.feesAll != 0
                || unownedOpen
                || (unownedGain instanceof LamportsValue.Known known && known.lamports() != 0);

        LamportsValue modeGain = modeUnknown ? priceUnknown() : new LamportsValue.Known(modeUnrealized);
        LamportsValue linesGain = linesUnknown ? priceUnknown() : new LamportsValue.Known(linesUnrealized);
        RealModeTotal total = new RealModeTotal(
                ledgerWindow.realized(),
                modeGain,
                ledgerWindow.fees,
                total(ledgerWindow.realized(), modeGain, ledgerWindow.fees),
                asOf);
        ReconciliationResult reconciliation = RealPnlReconciliation.reconcile(new RealPnlReconciliation.Inputs(
                window,
                new RealPnlReconciliation.Side(linesRealizedWindow, linesFeesWindow, linesGain),
                new RealPnlReconciliation.Side(ledgerWindow.realized(), ledgerWindow.fees, modeGain),
                new RealPnlReconciliation.Side(linesRealizedAll, linesFeesAll, linesGain),
                new RealPnlReconciliation.Side(ledgerAll.realized(), ledgerAll.fees, modeGain),
                costDiff,
                quantityDiff,
                eventSumsDiff,
                unownedHasValue && unowned.reasons.isEmpty()));
        boolean empty = lines.isEmpty() && wallets.isEmpty() && !unowned.touched;
        return new RealPnlResult(new RealModePnl(botLines, unownedLine, total, empty), reconciliation, positions);
    }

    // ----------------------------------------------------------------- assembly (ENT-207)

    private void close() {
        if (closed) {
            return;
        }
        flush();
        if (currentWallet != null) {
            drain(currentWallet);
        }
        // The wallets whose rows the stream never read (their attempts are all they have), in the order of the stream.
        for (UUID wallet : List.copyOf(walletAttempts.keySet())) {
            drain(wallet);
        }
        while (!walletless.isEmpty()) {
            apply(attempt(walletless.poll(), Optional.ofNullable(currentWallet)));
        }
        closed = true;
    }

    /** Review R-04: applies what is left of the virtual attempts of a wallet, in that wallet. */
    private void drain(UUID wallet) {
        Deque<OrderAttribution> left = walletAttempts.remove(wallet);
        while (left != null && !left.isEmpty()) {
            apply(attempt(left.poll(), wallet));
        }
    }

    private TradeEvent attempt(OrderAttribution order, UUID wallet) {
        return attempt(order, Optional.of(wallet));
    }

    /** Applies the open events of the slot, in the order of their main row. */
    private void flush() {
        if (open.isEmpty()) {
            return;
        }
        List<Assembly> due = new ArrayList<>(open.values());
        open.clear();
        due.sort(Comparator.comparing(Assembly::mainKey));
        for (Assembly assembly : due) {
            apply(assembly.event());
        }
    }

    private TradeEvent attempt(OrderAttribution order, Optional<UUID> wallet) {
        return new TradeEvent(
                Type.FEE_ONLY,
                ORDER + order.realOrderId(),
                wallet,
                Optional.of(order.mint()),
                0,
                0,
                0,
                order.feeLamports(),
                Optional.of(order),
                false,
                order.settledAt(),
                Optional.empty());
    }

    private record EventKey(UUID wallet, String reference, Optional<String> signature) {}

    /** The rows of one (wallet, reference, signature). */
    private final class Assembly {
        private final UUID wallet;
        private final String reference;
        private NetworkOrderedEntry fillIn;
        private NetworkOrderedEntry fillOut;
        private long boughtUnits;
        private long paid;
        private long soldUnits;
        private long received;
        private long fee;
        private long rent;
        private Instant feeAt;
        private Instant rentAt;
        private NetworkOrderKey first;

        Assembly(UUID wallet, String reference) {
            this.wallet = wallet;
            this.reference = reference;
        }

        void add(NetworkOrderedEntry entry) {
            if (first == null || entry.key().compareTo(first) < 0) {
                first = entry.key();
            }
            switch (entry.kind()) {
                case SWAP_FILL_IN -> {
                    fillIn = fillIn == null ? entry : fillIn;
                    boughtUnits =
                            Math.addExact(boughtUnits, entry.tokenBaseUnits().orElseThrow());
                    paid = Math.addExact(paid, Math.negateExact(entry.lamports()));
                }
                case SWAP_FILL_OUT -> {
                    fillOut = fillOut == null ? entry : fillOut;
                    soldUnits = Math.addExact(soldUnits, entry.tokenBaseUnits().orElseThrow());
                    received = Math.addExact(received, entry.lamports());
                }
                case NETWORK_FEE -> {
                    fee = Math.addExact(fee, Math.negateExact(entry.lamports()));
                    feeAt = feeAt == null ? entry.createdAt() : feeAt;
                }
                case RENT -> {
                    rent = Math.addExact(rent, Math.negateExact(entry.lamports()));
                    rentAt = rentAt == null ? entry.createdAt() : rentAt;
                }
                default -> throw new IllegalArgumentException("only a trading row is assembled");
            }
        }

        /** The key of the main row: the fill, else the fee, else the RENT (F1 step 5). */
        NetworkOrderKey mainKey() {
            if (fillIn != null) {
                return fillIn.key();
            }
            if (fillOut != null) {
                return fillOut.key();
            }
            return first;
        }

        TradeEvent event() {
            Optional<OrderAttribution> order = Optional.empty();
            boolean missing = false;
            if (reference.startsWith(ORDER)) {
                order = orderId(reference).flatMap(attribution::order);
                missing = order.isEmpty();
            }
            // BR6.3: the fee of the mode Assinada is read from the order, never also from the ledger.
            long charged = order.filter(OrderAttribution::feePaidByMainWallet)
                    .map(OrderAttribution::feeLamports)
                    .orElse(fee);
            if (fillIn != null) {
                return new TradeEvent(
                        Type.BUY,
                        reference,
                        Optional.of(wallet),
                        fillIn.mint(),
                        boughtUnits,
                        paid,
                        rent,
                        charged,
                        order,
                        missing,
                        fillIn.createdAt(),
                        Optional.of(fillIn.key()));
            }
            if (fillOut != null) {
                boolean ownSale = missing
                        || order.map(o -> !o.liquidation() && o.botId().isPresent())
                                .orElse(false);
                return new TradeEvent(
                        ownSale ? Type.OWN_SALE : Type.SALE_WITHOUT_BOT,
                        reference,
                        Optional.of(wallet),
                        fillOut.mint(),
                        soldUnits,
                        received,
                        rent,
                        charged,
                        order,
                        missing,
                        fillOut.createdAt(),
                        Optional.of(fillOut.key()));
            }
            return new TradeEvent(
                    Type.FEE_ONLY,
                    reference,
                    Optional.of(wallet),
                    order.map(OrderAttribution::mint),
                    0,
                    0,
                    rent,
                    charged,
                    order,
                    missing,
                    feeAt != null ? feeAt : rentAt,
                    Optional.of(first));
        }
    }

    private static Optional<UUID> orderId(String reference) {
        try {
            return Optional.of(UUID.fromString(reference.substring(ORDER.length())));
        } catch (IllegalArgumentException notAnId) {
            // A reference that is not the id of an order: like an order gone (BR5.4).
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------- F2

    private void apply(TradeEvent event) {
        switch (event.type()) {
            case BUY -> buy(event);
            case OWN_SALE, SALE_WITHOUT_BOT -> sale(event);
            case FEE_ONLY -> feeOnly(event);
        }
    }

    /** F2.1 (BR2.1, BR3.1, BR6.1): one holder; the cost is the SOL leg plus the RENT, never the fee. */
    private void buy(TradeEvent event) {
        Wallet wallet = wallet(event.botWalletId().orElseThrow());
        String mint = event.mint().orElseThrow();
        Holder holder =
                event.order().flatMap(OrderAttribution::botId).map(this::holder).orElse(Holder.UNOWNED);
        long cost = Math.addExact(event.swapLamports(), event.rentLamports());
        wallet.accumulator.apply(new CostFill(
                event.key().orElseThrow(),
                RealEntryKind.SWAP_FILL_IN,
                mint,
                event.tokenBaseUnits(),
                Math.negateExact(cost),
                Optional.of(holder)));
        wallet.quantity.merge(mint, event.tokenBaseUnits(), Math::addExact);
        Book book = wallet.books.computeIfAbsent(mint, m -> new Book());
        book.buyCost = Math.addExact(book.buyCost, cost);
        book.buyUnits = Math.addExact(book.buyUnits, event.tokenBaseUnits());
        Line line = line(holder);
        line.fee(event.feeLamports(), event.at());
        if (holder.unowned()) {
            unowned.touched = true;
            unowned.reasons.add(ORDER_RECORD_MISSING);
        }
        ledger(event.at(), 0, 0, event.feeLamports());
    }

    /** F2.2, F2.3 (BR4.3 to BR4.7, BR5.2 to BR5.4, BR6.1, BR6.4, BR6.6, BR7.1). */
    private void sale(TradeEvent event) {
        Wallet wallet = wallet(event.botWalletId().orElseThrow());
        String mint = event.mint().orElseThrow();
        SaleKind kind;
        Optional<CostHolder> seller;
        if (event.type() == Type.SALE_WITHOUT_BOT) {
            kind = event.conditional() ? SaleKind.CONDITIONAL : SaleKind.LIQUIDATION;
            seller = Optional.empty();
        } else {
            kind = SaleKind.OWN_SALE;
            seller = Optional.of(
                    event.orderMissing()
                            ? Holder.UNOWNED
                            : holder(event.order()
                                    .flatMap(OrderAttribution::botId)
                                    .orElseThrow()));
        }
        long costBefore = wallet.cost(mint);
        List<CostPart> costParts = wallet.accumulator.apply(new CostFill(
                event.key().orElseThrow(),
                RealEntryKind.SWAP_FILL_OUT,
                mint,
                event.tokenBaseUnits(),
                event.swapLamports(),
                seller));
        long costAfter = wallet.cost(mint);
        long current = wallet.quantity.getOrDefault(mint, 0L);
        wallet.quantity.put(mint, Math.subtractExact(current, Math.min(current, event.tokenBaseUnits())));
        List<ProrationPart> parts =
                RealProration.parts(kind, seller, costParts, event.swapLamports(), event.feeLamports());
        long units = 0;
        long received = 0;
        long fees = 0;
        Book book = wallet.books.computeIfAbsent(mint, m -> new Book());
        for (ProrationPart part : parts) {
            units = Math.addExact(units, part.tokenBaseUnits());
            received = Math.addExact(received, part.receivedLamports());
            fees = Math.addExact(fees, part.feeLamports());
            Line line = line((Holder) part.participant());
            line.realized(part.realizedLamports(), event.at());
            line.fee(part.feeLamports(), event.at());
            if (part.isShare() && inWindow(event.at())) {
                line.shares.add(new ProratedShare(
                        event.reference(),
                        part.origin().name(),
                        mint,
                        event.at(),
                        part.tokenBaseUnits(),
                        part.receivedLamports(),
                        part.feeLamports(),
                        part.realizedLamports()));
            }
            if (part.participant().unowned()) {
                unowned.touched = true;
                unowned.reasons.add(
                        switch (part.origin()) {
                            case UNOWNED_NO_COST -> UNOWNED_NO_COST;
                            case OWN -> ORDER_RECORD_MISSING;
                            default -> UNOWNED_EXCESS;
                        });
            }
            if (part.origin() != Origin.UNOWNED_NO_COST) {
                book.soldUnits = Math.addExact(book.soldUnits, part.tokenBaseUnits());
            }
            book.costRemoved = Math.addExact(book.costRemoved, part.costRemovedLamports());
        }
        eventSumsDiff = Math.addExact(
                eventSumsDiff,
                Math.abs(units - event.tokenBaseUnits())
                        + Math.abs(received - event.swapLamports())
                        + Math.abs(fees - event.feeLamports()));
        rentOutsideABuy(event, seller);
        ledger(
                event.at(),
                event.swapLamports(),
                Math.subtractExact(costBefore, costAfter),
                Math.addExact(event.feeLamports(), event.rentLamports()));
    }

    /** F2.4 (BR6.2, BR6.3, BR6.6): a failed order, or a RENT of a reference without a fill. */
    private void feeOnly(TradeEvent event) {
        Optional<OrderAttribution> order = event.order();
        if (event.feeLamports() > 0) {
            if (event.orderMissing()) {
                unownedFee(event.feeLamports(), event.at(), ORDER_RECORD_MISSING);
            } else if (order.flatMap(OrderAttribution::botId).isPresent()) {
                line(holder(order.get().botId().get())).fee(event.feeLamports(), event.at());
            } else {
                failedSaleWithoutBot(event);
            }
        }
        rentOutsideABuy(
                event,
                event.orderMissing()
                        ? Optional.of(Holder.UNOWNED)
                        : order.flatMap(OrderAttribution::botId)
                                .map(this::holder)
                                .map(h -> (CostHolder) h));
        ledger(event.at(), 0, 0, Math.addExact(event.feeLamports(), event.rentLamports()));
    }

    /** BR6.2: the fee of a failed liquidation, shared by the quantities of the holders just before the attempt. */
    private void failedSaleWithoutBot(TradeEvent event) {
        Optional<Wallet> wallet = event.botWalletId().map(this::wallet);
        List<Holding> before = wallet.isPresent() && event.mint().isPresent()
                ? holdings(wallet.get(), event.mint().get())
                : List.of();
        SaleKind kind = event.conditional() ? SaleKind.CONDITIONAL : SaleKind.LIQUIDATION;
        List<ProrationPart> parts = RealProration.fee(kind, before, event.feeLamports());
        if (parts.isEmpty()) {
            unownedFee(event.feeLamports(), event.at(), UNOWNED_NO_COST);
            return;
        }
        long fees = 0;
        for (ProrationPart part : parts) {
            fees = Math.addExact(fees, part.feeLamports());
            Line line = line((Holder) part.participant());
            line.fee(part.feeLamports(), event.at());
            if (part.isShare() && inWindow(event.at())) {
                line.shares.add(new ProratedShare(
                        event.reference(),
                        part.origin().name(),
                        event.mint().orElseThrow(),
                        event.at(),
                        0,
                        0,
                        part.feeLamports(),
                        0));
            }
            if (part.participant().unowned()) {
                unowned.touched = true;
                unowned.reasons.add(UNOWNED_EXCESS);
            }
        }
        eventSumsDiff = Math.addExact(eventSumsDiff, Math.abs(fees - event.feeLamports()));
    }

    /** BR6.6: a RENT outside a buy goes to the fees of the holder of its order; without a bot, to "Sem bot". */
    private void rentOutsideABuy(TradeEvent event, Optional<CostHolder> owner) {
        if (event.rentLamports() == 0) {
            return;
        }
        if (event.orderMissing()) {
            unownedFee(event.rentLamports(), event.at(), ORDER_RECORD_MISSING);
        } else if (owner.isPresent() && !owner.get().unowned()) {
            line((Holder) owner.get()).fee(event.rentLamports(), event.at());
        } else {
            unownedFee(event.rentLamports(), event.at(), UNOWNED_NO_COST);
        }
    }

    private void unownedFee(long fee, Instant at, String reason) {
        unowned.fee(fee, at);
        unowned.touched = true;
        unowned.reasons.add(reason);
    }

    /** F3 step 1: the side of the ledger, from the rows and the orders only, by the one instant of the event. */
    private void ledger(Instant at, long received, long costRemoved, long fees) {
        ledgerAll.add(received, costRemoved, fees);
        if (inWindow(at)) {
            ledgerWindow.add(received, costRemoved, fees);
        }
    }

    // ----------------------------------------------------------------- holders

    private void register(BotEntry bot) {
        if (bot.market() != PnlMarket.REAL) {
            throw new IllegalArgumentException("only a REAL bot holds a REAL position");
        }
        Holder holder = Holder.bot(bot.botId(), bot.createdAt());
        holders.put(bot.botId(), holder);
        entries.put(holder, bot);
        lines.put(holder, new Line());
    }

    /** BR3.4: a bot of the person, from the directory or read on the spot; anything else stops the pass. */
    private Holder holder(UUID botId) {
        Holder known = holders.get(botId);
        if (known != null) {
            return known;
        }
        BotEntry read = attribution
                .bot(botId)
                .filter(bot -> bot.market() == PnlMarket.REAL)
                .orElseThrow(UnknownBot::new);
        register(read);
        return holders.get(botId);
    }

    private Line line(Holder holder) {
        return holder.unowned() ? unowned : lines.get(holder);
    }

    /** The holders of a mint in a wallet with a quantity, in tie-break order (BR4.2). */
    private List<Holding> holdings(Wallet wallet, String mint) {
        List<Holding> held = new ArrayList<>();
        List<Holder> all = new ArrayList<>(holders.values());
        all.add(Holder.UNOWNED);
        all.sort(Comparator.naturalOrder());
        for (Holder holder : all) {
            for (RealPosition position : wallet.accumulator.openPositions(holder)) {
                if (position.mint().equals(mint)) {
                    held.add(new Holding(holder, position.tokenBaseUnits(), known(position.cost())));
                }
            }
        }
        return held;
    }

    private Wallet wallet(UUID botWalletId) {
        return wallets.computeIfAbsent(botWalletId, id -> new Wallet(method.start()));
    }

    // ----------------------------------------------------------------- values

    private boolean inWindow(Instant at) {
        return switch (window) {
            case PnlWindow.All all -> true;
            case PnlWindow.Range range -> !at.isBefore(range.from()) && at.isBefore(range.to());
        };
    }

    private static long known(RealCost cost) {
        return switch (cost) {
            case RealCost.Known known -> known.lamports();
            case RealCost.Unknown unknown ->
                throw new IllegalStateException("the accumulator of the P&L always knows its cost");
        };
    }

    private static LamportsValue unrealized(Holder holder, Map<Holder, long[]> gains, Set<Holder> unknown) {
        if (unknown.contains(holder)) {
            return priceUnknown();
        }
        long[] gain = gains.get(holder);
        return new LamportsValue.Known(gain == null ? 0 : gain[0]);
    }

    /** BR7.4: {@code realized + unrealized - fees}; unknown with the unrealized. */
    private static LamportsValue total(long realized, LamportsValue unrealized, long fees) {
        return switch (unrealized) {
            case LamportsValue.Known known ->
                new LamportsValue.Known(Math.subtractExact(Math.addExact(realized, known.lamports()), fees));
            case LamportsValue.Unknown unknown -> unknown;
        };
    }

    private static LamportsValue priceUnknown() {
        return new LamportsValue.Unknown(LamportsValue.UnknownReason.PRICE_UNKNOWN);
    }

    /** The result of one holder: realized and fees in the period and since the beginning, and its shares. */
    private final class Line {
        long realizedWindow;
        long feesWindow;
        long realizedAll;
        long feesAll;
        boolean touched;
        final List<ProratedShare> shares = new ArrayList<>();
        final Set<String> reasons = new TreeSet<>();

        void realized(long lamports, Instant at) {
            touched = true;
            realizedAll = Math.addExact(realizedAll, lamports);
            if (inWindow(at)) {
                realizedWindow = Math.addExact(realizedWindow, lamports);
            }
        }

        void fee(long lamports, Instant at) {
            if (lamports == 0) {
                return;
            }
            touched = true;
            feesAll = Math.addExact(feesAll, lamports);
            if (inWindow(at)) {
                feesWindow = Math.addExact(feesWindow, lamports);
            }
        }
    }

    /** The side of the ledger of the mode (BR7.5). */
    private static final class Ledger {
        long received;
        long costRemoved;
        long fees;

        void add(long received, long costRemoved, long fees) {
            this.received = Math.addExact(this.received, received);
            this.costRemoved = Math.addExact(this.costRemoved, costRemoved);
            this.fees = Math.addExact(this.fees, fees);
        }

        long realized() {
            return Math.subtractExact(received, costRemoved);
        }
    }

    /** What the conservation of a mint of a wallet needs (BR8.3). */
    private static final class Book {
        long buyCost;
        long buyUnits;
        long soldUnits;
        long costRemoved;
    }

    /** One bot wallet: its cost accumulator, its quantity-only path (BR2.5) and its books. */
    private static final class Wallet {
        final RealCostMethod.Accumulator accumulator;
        final Map<String, Long> quantity = new HashMap<>();
        final Map<String, Book> books = new HashMap<>();

        Wallet(RealCostMethod.Accumulator accumulator) {
            this.accumulator = accumulator;
        }

        long cost(String mint) {
            long cost = 0;
            for (RealPosition position : accumulator.openPositions()) {
                if (position.mint().equals(mint)) {
                    cost = Math.addExact(cost, known(position.cost()));
                }
            }
            return cost;
        }
    }
}
