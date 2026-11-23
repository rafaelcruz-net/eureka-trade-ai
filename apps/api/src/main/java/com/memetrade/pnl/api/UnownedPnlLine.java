package com.memetrade.pnl.api;

import java.util.Objects;
import java.util.Set;

/**
 * 261121-paper-real-unico U1, C10: the line "Sem bot" of the REAL (FR3.4, FR5.3). Never produced in the U1.
 *
 * <p>261121-paper-real-unico U2 (BR5.1 to BR5.6, ENT-211): everything of the holder without owner since the beginning,
 * realized and fees in the period and the unrealized of now, with the set of the reasons that produced it:
 * {@code UNOWNED_EXCESS} (the registered quantity without owner sold, BR5.2), {@code UNOWNED_NO_COST} (a quantity sold
 * beyond every position, or a fee without a holder, BR5.3, BR6.2, BR6.6) and {@code ORDER_RECORD_MISSING} (a row whose
 * order is gone, BR5.4). The value {@code SURPLUS_WITHOUT_OWNER} of the contract of the U1 became {@code UNOWNED_EXCESS}
 * (BR5.6). Absent when nothing has ever been without owner; with any value different from zero, never without a reason.
 *
 * @param reasons a set of {@code UNOWNED_EXCESS}, {@code UNOWNED_NO_COST} and {@code ORDER_RECORD_MISSING}
 */
public record UnownedPnlLine(long realizedLamports, LamportsValue unrealized, long feesLamports, Set<String> reasons) {

    public UnownedPnlLine {
        Objects.requireNonNull(unrealized, "unrealized");
        reasons = Set.copyOf(reasons);
    }
}
