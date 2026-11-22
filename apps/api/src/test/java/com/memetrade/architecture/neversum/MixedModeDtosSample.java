package com.memetrade.architecture.neversum;

import com.memetrade.pnl.web.ModePnlDtos.PaperModeBlockDto;
import com.memetrade.pnl.web.ModePnlDtos.RealModeBlockDto;

/**
 * 261121-paper-real-unico U1: a TEST sample made to break part 1 of NEVER_SUM_MODES on the DTO types of the route C4 (a
 * class outside the mode view, and not an assembler, that sees the PAPER block and the REAL block together). Never part
 * of the application.
 */
public final class MixedModeDtosSample {

    private MixedModeDtosSample() {}

    public static String both(PaperModeBlockDto paper, RealModeBlockDto real) {
        return paper.realized() + real.realizedLamports();
    }
}
