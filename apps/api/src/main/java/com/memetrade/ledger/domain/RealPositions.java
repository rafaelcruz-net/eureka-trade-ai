package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2 {@code positions} (261113-trading-real-bot U3, Q11, BR5.2): the open positions of one wallet from its swap fills
 * in recording order, with the average cost method. A buy adds its quantity and its SOL leg; a sell removes its quantity
 * and the same share of the cost, rounded DOWN so the cost left on the open quantity is never understated (the barrier
 * values a position at cost when its price is unknown). Integer arithmetic only.
 */
public final class RealPositions {

    private RealPositions() {}

    /** One swap fill: kind, SOL leg in lamports with its sign, mint and quantity. */
    public record Fill(RealEntryKind kind, long lamports, String mint, long tokenBaseUnits) {}

    public static List<RealPosition> of(List<Fill> fills) {
        Map<String, long[]> open = new LinkedHashMap<>();
        for (Fill fill : fills) {
            long[] position = open.computeIfAbsent(fill.mint(), mint -> new long[2]);
            if (fill.kind() == RealEntryKind.SWAP_FILL_IN) {
                position[0] = Math.addExact(position[0], fill.tokenBaseUnits());
                position[1] = Math.addExact(position[1], Math.absExact(fill.lamports()));
            } else if (fill.kind() == RealEntryKind.SWAP_FILL_OUT) {
                long sold = Math.min(fill.tokenBaseUnits(), position[0]);
                long removed = position[0] == 0
                        ? 0
                        : BigInteger.valueOf(position[1])
                                .multiply(BigInteger.valueOf(sold))
                                .divide(BigInteger.valueOf(position[0]))
                                .longValueExact();
                position[0] = Math.subtractExact(position[0], sold);
                position[1] = position[0] == 0 ? 0 : Math.subtractExact(position[1], removed);
            }
        }
        List<RealPosition> positions = new ArrayList<>();
        open.forEach((mint, position) -> {
            if (position[0] > 0) {
                positions.add(new RealPosition(mint, position[0], position[1]));
            }
        });
        return positions;
    }
}
