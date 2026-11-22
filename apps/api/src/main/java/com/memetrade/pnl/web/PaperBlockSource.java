package com.memetrade.pnl.web;

import com.memetrade.identity.api.Principal;
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
        PaperModeBlock block = reader.block(person, PnlWindow.all());
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
