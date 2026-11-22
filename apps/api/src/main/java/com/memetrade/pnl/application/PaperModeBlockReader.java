package com.memetrade.pnl.application;

import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.application.PnlQueryService.PnlView;
import com.memetrade.pnl.domain.PnlCalculator;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (BR5.2, BR5.4, BR5.9, BR5.10, NFR2.2; performance-design.md "Bloco PAPER"): the PAPER
 * block of the P&L by mode. Called inside the REPEATABLE READ transaction of its block (the lines and the total come
 * from the same snapshot): the PAPER bots of the directory (C8) in order of creation, the line of each one by the SAME
 * calculation as {@code GET /api/v1/pnl?botId=} (reused, never copied), and the total of the mode by the same
 * calculation without a bot filter, from the PAPER ledger, never as the sum of the lines. The window ALL starts at
 * {@code 1970-01-01T00:00Z} internally and ends at the instant of the injectable clock; the limit of 366 days of the
 * public route does not apply to it. Nothing REAL is read.
 */
@Component
public class PaperModeBlockReader {

    /** The internal start of the window ALL; no PAPER entry is older (the answer shows only {@code ALL}). */
    static final Instant BEGINNING = Instant.EPOCH;

    private final PnlQueryService pnl;
    private final BotDirectory directory;
    private final Clock clock;

    public PaperModeBlockReader(PnlQueryService pnl, BotDirectory directory, Clock clock) {
        this.pnl = pnl;
        this.directory = directory;
        this.clock = clock;
    }

    public PaperModeBlock block(Principal person, PnlWindow window) {
        if (!(window instanceof PnlWindow.All)) {
            throw new IllegalArgumentException("The U1 answers only the period ALL");
        }
        Instant now = clock.instant();
        List<PaperBotPnlLine> lines = new ArrayList<>();
        for (BotEntry bot : directory.botsOf(person.tenantId(), person.userId(), PnlMarket.PAPER)) {
            PnlCalculator.Report report = pnl.reportSince(person, BEGINNING, now, Optional.of(bot.botId()))
                    .report();
            lines.add(new PaperBotPnlLine(
                    bot.botId(),
                    bot.name(),
                    bot.status(),
                    report.realized(),
                    report.unrealized(),
                    report.fees(),
                    total(report)));
        }
        PnlView mode = pnl.reportSince(person, BEGINNING, now, Optional.empty());
        PnlCalculator.Report report = mode.report();
        return new PaperModeBlock(
                PaperModeBlock.DISCLAIMER,
                lines.isEmpty() && report.empty(),
                total(report),
                report.realized(),
                report.unrealized(),
                mode.unrealizedAsOf(),
                report.fees(),
                lines);
    }

    /** BR5.4: {@code realized + unrealized - fees}, exact; unknown when the unrealized is unknown. */
    static Amount total(PnlCalculator.Report report) {
        return switch (report.unrealized()) {
            case Amount.Known known ->
                new Amount.Known(report.realized().add(known.value()).subtract(report.fees()));
            case Amount.Unknown unknown -> unknown;
        };
    }
}
