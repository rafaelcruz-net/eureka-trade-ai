package com.memetrade.pnl.api;

import java.util.Objects;
import java.util.Set;

/**
 * 261121-paper-real-unico U1, C10: the line "Sem bot" of the REAL (FR3.4, FR5.3). Never produced in the U1 (the
 * {@code Optional} of {@link RealModePnl#unowned()} is empty, {@code null} in C4); the U2 fills it.
 *
 * @param reasons {@code SURPLUS_WITHOUT_OWNER} or {@code ORDER_RECORD_MISSING}
 */
public record UnownedPnlLine(long realizedLamports, LamportsValue unrealized, long feesLamports, Set<String> reasons) {

    public UnownedPnlLine {
        Objects.requireNonNull(unrealized, "unrealized");
        reasons = Set.copyOf(reasons);
    }
}
