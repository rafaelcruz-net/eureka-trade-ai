package com.memetrade.pnl.web;

import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.UnownedPnlLine;
import com.memetrade.shared.config.ProcessRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (C4, BR5.2, BR5.5, BR5.8): the REAL block of the answer, built on the thread of its block.
 * It reads and maps ONLY REAL values (lamports as signed integer text, the unknown as {@code UNKNOWN}); nothing PAPER is
 * ever seen here (rule NEVER_SUM_MODES). The name of each bot comes from the directory (C8); no wallet address is ever
 * part of the block.
 *
 * <p>261121-paper-real-unico U2 (BR10.3, BR4.7, BR5.5, BR8.7): the block of the {@code RealBotPnl}: the shares of each
 * bot (a summary of the realized by origin, their count and the 10 most recent), and the line "Sem bot", always an
 * object (zero without reasons when nothing was ever without owner). A reconciliation mismatch never reaches here: the
 * query throws {@code RealPnlUnavailable} with {@code RECONCILIATION_MISMATCH} and the block becomes UNAVAILABLE with no
 * number.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealBlockSource {

    /** The shares listed inline; the others are read by C5 (U3). */
    static final int RECENT_SHARES = 10;

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
                unowned(pnl));
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
                shares(line.shares()));
    }

    /** BR4.7: the realized by origin, the count and the most recent shares, newest first. */
    static ModePnlDtos.ProratedSharesDto shares(List<ProratedShare> shares) {
        Map<String, Long> byOrigin = new TreeMap<>();
        for (ProratedShare share : shares) {
            byOrigin.merge(share.origin(), share.realizedLamports(), Math::addExact);
        }
        List<ModePnlDtos.ProratedSummaryDto> summary = new ArrayList<>();
        byOrigin.forEach(
                (origin, realized) -> summary.add(new ModePnlDtos.ProratedSummaryDto(origin, Long.toString(realized))));
        List<ModePnlDtos.ProratedLineDto> recent = shares.stream()
                .sorted(Comparator.comparing(ProratedShare::at).reversed())
                .limit(RECENT_SHARES)
                .map(share -> new ModePnlDtos.ProratedLineDto(
                        share.at(),
                        share.mint(),
                        share.origin(),
                        Long.toString(share.tokenBaseUnits()),
                        Long.toString(share.receivedLamports()),
                        Long.toString(share.feeLamports()),
                        Long.toString(share.realizedLamports())))
                .toList();
        return new ModePnlDtos.ProratedSharesDto(summary, shares.size(), recent);
    }

    /** BR5.5: the line "Sem bot"; since the U2 always an object, zero and without reasons when it is absent. */
    static ModePnlDtos.UnownedPnlLineDto unowned(RealModePnl pnl) {
        UnownedPnlLine line =
                pnl.unowned().orElse(new UnownedPnlLine(0, new LamportsValue.Known(0), 0, java.util.Set.of()));
        return new ModePnlDtos.UnownedPnlLineDto(
                Long.toString(line.realizedLamports()),
                amount(line.unrealized()),
                Long.toString(line.feesLamports()),
                line.reasons().stream().sorted().toList());
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
