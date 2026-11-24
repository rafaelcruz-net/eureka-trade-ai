package com.memetrade.pnl.web;

import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.application.ReadRefusalAudit;
import com.memetrade.pnl.modeview.PnlCompareMetrics;
import com.memetrade.pnl.modeview.PnlModeMetrics;
import com.memetrade.shared.config.ProcessRole;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U3: what the routes of the screens of the P&L (C4 with {@code market}, C5, C6) need besides
 * the view by mode and the two block sources, kept together so the constructor of {@link PnlController} stays readable.
 * No logic of its own, and no value of either mode.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PnlViewsParts {

    final BotDirectory directory;
    final ReadRefusalAudit refusals;
    final PnlModeMetrics modeMetrics;
    final PnlCompareMetrics compareMetrics;

    public PnlViewsParts(
            BotDirectory directory,
            ReadRefusalAudit refusals,
            PnlModeMetrics modeMetrics,
            PnlCompareMetrics compareMetrics) {
        this.directory = directory;
        this.refusals = refusals;
        this.modeMetrics = modeMetrics;
        this.compareMetrics = compareMetrics;
    }
}
