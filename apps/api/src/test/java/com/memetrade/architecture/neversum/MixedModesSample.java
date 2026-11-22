package com.memetrade.architecture.neversum;

import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.application.PaperModeBlock;

/**
 * 261121-paper-real-unico U1: a TEST sample made to break part 1 of NEVER_SUM_MODES (a class outside the mode view that
 * sees a PAPER result and a REAL result together). Never part of the application.
 */
public final class MixedModesSample {

    private MixedModesSample() {}

    public static String both(PaperModeBlock paper, RealModePnl real) {
        return paper.disclaimer() + real.empty();
    }
}
