package com.memetrade.pnl.modeview.sample;

import com.memetrade.pnl.application.PaperModeBlock;
import java.math.BigDecimal;

/**
 * 261121-paper-real-unico U1: a TEST sample in the package of the mode view made to break parts 2 and 3 of
 * NEVER_SUM_MODES (it calls an accessor of a PAPER result and adds money). Never part of the application.
 */
public final class AddingModesSample {

    private AddingModesSample() {}

    public static BigDecimal grandTotal(PaperModeBlock paper, BigDecimal other) {
        return paper.realized().add(other);
    }
}
