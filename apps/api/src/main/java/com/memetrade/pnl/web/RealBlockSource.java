package com.memetrade.pnl.web;

import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.shared.config.ProcessRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (C4, BR5.2, BR5.5, BR5.8): the REAL block of the answer, built on the thread of its block.
 * It reads and maps ONLY REAL values (lamports as signed integer text, the unknown as {@code UNKNOWN}); nothing PAPER is
 * ever seen here (rule NEVER_SUM_MODES). The name of each bot comes from the directory (C8); no wallet address is ever
 * part of the block. {@code unowned} is null and {@code prorated} empty until the U2.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealBlockSource {

    private final RealBotPnlQuery query;
    private final BotDirectory directory;

    public RealBlockSource(RealBotPnlQuery query, BotDirectory directory) {
        this.query = query;
        this.directory = directory;
    }

    public ModePnlDtos.RealModeBlockDto block(Principal person) {
        RealModePnl pnl = query.modePnl(person.tenantId(), person.userId(), PnlWindow.all());
        Map<UUID, String> names = directory.botsOf(person.tenantId(), person.userId(), PnlMarket.REAL).stream()
                .collect(Collectors.toMap(BotEntry::botId, BotEntry::name, (first, second) -> first));
        return new ModePnlDtos.RealModeBlockDto(
                "AVAILABLE",
                "REAL",
                "SOL",
                pnl.empty(),
                amount(pnl.total().total()),
                Long.toString(pnl.total().realizedLamports()),
                amount(pnl.total().unrealized()),
                pnl.total().unrealizedAsOf(),
                Long.toString(pnl.total().feesLamports()),
                pnl.lines().stream().map(line(names)).toList(),
                null);
    }

    private static Function<RealBotPnlLine, ModePnlDtos.RealBotPnlLineDto> line(Map<UUID, String> names) {
        return line -> new ModePnlDtos.RealBotPnlLineDto(
                line.botId(),
                names.getOrDefault(line.botId(), ""),
                line.status().name(),
                amount(line.total()),
                Long.toString(line.realizedLamports()),
                amount(line.unrealized()),
                Long.toString(line.feesLamports()),
                new ModePnlDtos.ProratedSharesDto(List.of(), 0, List.of()));
    }

    static ModePnlDtos.LamportsAmountDto amount(LamportsValue value) {
        return switch (value) {
            case LamportsValue.Known known ->
                new ModePnlDtos.LamportsKnownDto("KNOWN", Long.toString(known.lamports()));
            case LamportsValue.Unknown unknown ->
                new ModePnlDtos.LamportsUnknownDto("UNKNOWN", unknown.reason().name());
        };
    }
}
