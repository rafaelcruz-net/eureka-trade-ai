package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M2 {@code positions} (261113-trading-real-bot U3, Q11). 261121-paper-real-unico U2 (decision D1, BR2.5, BR2.8): the
 * quantity-only path of the wallet, pure, fed with the swap fills in NETWORK order (C12) and no attribution. Each
 * SWAP_FILL_IN adds its quantity; each SWAP_FILL_OUT removes min(sold, current), so the quantity is never negative (what
 * a sale takes beyond it is the share of UNOWNED without cost in the P&L, BR5.3). Its own cost algorithm is gone: the
 * cost is not read here ({@code Unknown(NOT_READ)}); the one REAL average cost is the sum of the holders (BR2.4). By
 * construction this quantity equals the sum of the quantities of the holders (BR9.1). Integer arithmetic only.
 */
public final class RealPositions {

    private static final RealCost NOT_READ = new RealCost.Unknown(RealCostUnknownReason.NOT_READ);

    private RealPositions() {}

    /** One swap fill: kind, SOL leg in lamports with its sign, mint and quantity. */
    public record Fill(RealEntryKind kind, long lamports, String mint, long tokenBaseUnits) {}

    /** The open positions after {@code fills}, in network order. */
    public static List<RealPosition> of(List<Fill> fills) {
        Quantities quantities = new Quantities();
        fills.forEach(quantities::apply);
        return quantities.positions();
    }

    /** The quantity of each mint, applied one fill at a time (never a list of the history). */
    public static final class Quantities {

        private final Map<String, Long> open = new LinkedHashMap<>();

        public void apply(Fill fill) {
            Objects.requireNonNull(fill.mint(), "mint");
            if (fill.tokenBaseUnits() < 1) {
                throw new IllegalArgumentException("a swap fill moves at least 1 base unit");
            }
            long current = open.getOrDefault(fill.mint(), 0L);
            switch (fill.kind()) {
                case SWAP_FILL_IN -> open.put(fill.mint(), Math.addExact(current, fill.tokenBaseUnits()));
                case SWAP_FILL_OUT ->
                    open.put(fill.mint(), Math.subtractExact(current, Math.min(fill.tokenBaseUnits(), current)));
                default -> throw new IllegalArgumentException("only a swap fill moves the quantity of a token");
            }
        }

        /** The open quantity of {@code mint}, 0 when none. */
        public long quantity(String mint) {
            return open.getOrDefault(mint, 0L);
        }

        /** The positions with at least 1 base unit, in the order their mint first appeared; cost not read. */
        public List<RealPosition> positions() {
            List<RealPosition> positions = new ArrayList<>();
            open.forEach((mint, units) -> {
                if (units > 0) {
                    positions.add(new RealPosition(mint, units, NOT_READ));
                }
            });
            return positions;
        }
    }
}
