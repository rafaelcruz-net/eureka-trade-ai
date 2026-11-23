package com.memetrade.pnl.real;

import com.memetrade.ledger.api.CostHolder;
import java.util.Objects;

/**
 * 261121-paper-real-unico U2 (ENT-208, BR4.5, BR4.7, BR5.2, BR5.3, BR7.1): the part of one participant in a sale, or in
 * the fee of a failed liquidation. Lamports and base units are exact integers; {@code realized = received - cost}.
 *
 * @param participant a bot of the person or the part without owner
 * @param tokenBaseUnits the quantity the participant gave (0 only in a part of a fee alone)
 */
public record ProrationPart(
        CostHolder participant,
        long tokenBaseUnits,
        long receivedLamports,
        long feeLamports,
        long costRemovedLamports,
        long realizedLamports,
        Origin origin) {

    /** Where the part comes from (BR4.7); the part of the seller of an own sale is {@link #OWN} and never a share. */
    public enum Origin {
        OWN,
        LIQUIDATION,
        CONDITIONAL,
        SOLD_BY_OTHER_BOT,
        UNOWNED_EXCESS,
        UNOWNED_NO_COST
    }

    public ProrationPart {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(origin, "origin");
        if (tokenBaseUnits < 0 || receivedLamports < 0 || feeLamports < 0 || costRemovedLamports < 0) {
            throw new IllegalArgumentException("a part never has a negative quantity, received, fee nor cost");
        }
        if (realizedLamports != Math.subtractExact(receivedLamports, costRemovedLamports)) {
            throw new IllegalArgumentException("the realized of a part is its received minus its cost (BR7.1)");
        }
    }

    /** True for the parts of a bot that become a {@code ProratedShare} (BR4.7). */
    public boolean isShare() {
        return !participant.unowned()
                && (origin == Origin.LIQUIDATION || origin == Origin.CONDITIONAL || origin == Origin.SOLD_BY_OTHER_BOT);
    }
}
