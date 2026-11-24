package com.memetrade.pnl.web;

import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.application.PaperBotPnlLine;
import com.memetrade.pnl.application.PaperModeBlock;
import com.memetrade.pnl.application.PaperModeBlockReader;
import com.memetrade.pnl.web.PnlDtos.PnlAmountDto;
import com.memetrade.shared.config.ProcessRole;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (C4, BR5.2): the PAPER block of the answer, built on the thread of its block. It reads and
 * maps ONLY PAPER values (exact US$ as text, the unknown as {@code UNKNOWN}); nothing REAL is ever seen here (rule
 * NEVER_SUM_MODES). The principal is a parameter: no authentication context is read on the thread of the pool.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PaperBlockSource {

    private final PaperModeBlockReader reader;

    public PaperBlockSource(PaperModeBlockReader reader) {
        this.reader = reader;
    }

    public ModePnlDtos.PaperModeBlockDto block(Principal person) {
        return block(person, PnlWindow.all());
    }

    /** 261121-paper-real-unico U3 (BR2.4): the PAPER block of the window ALL or RANGE. */
    public ModePnlDtos.PaperModeBlockDto block(Principal person, PnlWindow window) {
        PaperModeBlock block = reader.block(person, window);
        return new ModePnlDtos.PaperModeBlockDto(
                "AVAILABLE",
                "PAPER",
                "USD",
                block.disclaimer(),
                block.empty(),
                PnlAmountDto.of(block.total()),
                block.realized().toPlainString(),
                PnlAmountDto.of(block.unrealized()),
                block.unrealizedAsOf(),
                block.fees().toPlainString(),
                block.bots().stream().map(PaperBlockSource::line).toList());
    }

    /**
     * 261121-paper-real-unico U3 (C6, BR5.4, NFR2.3): the PAPER side of the comparison: the line of the bot by the same
     * calculation as its line in the PAPER block of C4, its creation, its strategy and the fixed disclaimer.
     */
    public ModePnlDtos.PaperComparisonSideDto side(Principal person, BotEntry bot, PnlWindow window) {
        return new ModePnlDtos.PaperComparisonSideDto(
                "AVAILABLE",
                "PAPER",
                "USD",
                PaperModeBlock.DISCLAIMER,
                bot.createdAt(),
                bot.templateId(),
                line(reader.lineOf(person, bot, window)));
    }

    private static ModePnlDtos.PaperBotPnlLineDto line(PaperBotPnlLine line) {
        return new ModePnlDtos.PaperBotPnlLineDto(
                line.botId(),
                line.name(),
                line.status().name(),
                PnlAmountDto.of(line.total()),
                line.realized().toPlainString(),
                PnlAmountDto.of(line.unrealized()),
                line.fees().toPlainString());
    }
}
