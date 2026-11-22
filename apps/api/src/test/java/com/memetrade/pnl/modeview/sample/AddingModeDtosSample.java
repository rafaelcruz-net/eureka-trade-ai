package com.memetrade.pnl.modeview.sample;

import com.memetrade.pnl.web.ModePnlDtos.PaperModeBlockDto;
import com.memetrade.pnl.web.ModePnlDtos.RealModeBlockDto;
import java.math.BigDecimal;

/**
 * 261121-paper-real-unico U1: a TEST sample in the package of the mode view made to break parts 2 and 3 of
 * NEVER_SUM_MODES on the DTO types of the route C4 (it reads the total of each block and adds them). Never part of the
 * application.
 */
public final class AddingModeDtosSample {

    private AddingModeDtosSample() {}

    public static BigDecimal grandTotal(PaperModeBlockDto paper, RealModeBlockDto real) {
        return new BigDecimal(paper.realized()).add(new BigDecimal(real.realizedLamports()));
    }
}
