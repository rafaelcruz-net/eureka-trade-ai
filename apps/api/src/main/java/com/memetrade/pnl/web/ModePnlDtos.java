package com.memetrade.pnl.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.pnl.modeview.BlockResult;
import com.memetrade.pnl.web.PnlDtos.PnlAmountDto;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1, C4 (BR5.1 to BR5.8): the bodies of {@code GET /api/v1/pnl/by-mode}. The answer has ONLY
 * {@code period}, {@code paper} and {@code real}; each block only its own market and currency. PAPER amounts are exact
 * decimal text in US$, REAL amounts are lamports as signed integer text; an unknown value is {@code UNKNOWN} with its
 * reason, never a zero. These are wire shapes only: nothing here computes a value.
 */
public final class ModePnlDtos {

    static final String DECIMAL = "^-?\\d+(\\.\\d+)?$";
    static final String LAMPORTS = "^-?[0-9]{1,20}$";

    private ModePnlDtos() {}

    /**
     * 261121-paper-real-unico U3 (BR3.1, PQ-6): with {@code market} only the block asked comes; the key of the other
     * block is ABSENT from the JSON, never {@code null} ({@code NON_ABSENT}). Without {@code market}, both.
     */
    @Schema(
            name = "PnlByMode",
            description =
                    "Só o período e os blocos. Sem market, os dois blocos; com market, só o bloco pedido, e a chave"
                            + " do outro fica ausente (nunca null). Não existe campo de total fora dos blocos (FR4.1, NFR3)")
    public record PnlByModeDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) PnlPeriodDto period,

            @Schema(requiredMode = RequiredMode.NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_ABSENT)
            PaperModeBlockResultDto paper,

            @Schema(requiredMode = RequiredMode.NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_ABSENT)
            RealModeBlockResultDto real) {

        static PnlByModeDto of(
                PnlPeriodDto period,
                Optional<BlockResult<PaperModeBlockDto>> paper,
                Optional<BlockResult<RealModeBlockDto>> real) {
            if (paper.isEmpty() && real.isEmpty()) {
                throw new IllegalArgumentException("The P&L by mode has at least one block");
            }
            return new PnlByModeDto(
                    period,
                    paper.map(PnlByModeDto::paperOf).orElse(null),
                    real.map(PnlByModeDto::realOf).orElse(null));
        }

        private static PaperModeBlockResultDto paperOf(BlockResult<PaperModeBlockDto> result) {
            return switch (result) {
                case BlockResult.Available<PaperModeBlockDto> available -> available.block();
                case BlockResult.Unavailable<PaperModeBlockDto> unavailable -> ModeBlockUnavailableDto.of(unavailable);
            };
        }

        private static RealModeBlockResultDto realOf(BlockResult<RealModeBlockDto> result) {
            return switch (result) {
                case BlockResult.Available<RealModeBlockDto> available -> available.block();
                case BlockResult.Unavailable<RealModeBlockDto> unavailable -> ModeBlockUnavailableDto.of(unavailable);
            };
        }
    }

    @Schema(name = "PnlPeriod", description = "Período do P&L: ALL é desde o início; RANGE é [from, to) em UTC")
    public record PnlPeriodDto(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ALL", "RANGE"})
            String kind,

            @Schema(format = "date-time", description = "Só em RANGE")
            Instant from,

            @Schema(format = "date-time", description = "Só em RANGE")
            Instant to) {}

    @Schema(
            name = "PaperModeBlockResult",
            oneOf = {PaperModeBlockDto.class, ModeBlockUnavailableDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "AVAILABLE", schema = PaperModeBlockDto.class),
                @DiscriminatorMapping(value = "UNAVAILABLE", schema = ModeBlockUnavailableDto.class)
            })
    public sealed interface PaperModeBlockResultDto permits PaperModeBlockDto, ModeBlockUnavailableDto {}

    @Schema(
            name = "RealModeBlockResult",
            oneOf = {RealModeBlockDto.class, ModeBlockUnavailableDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "AVAILABLE", schema = RealModeBlockDto.class),
                @DiscriminatorMapping(value = "UNAVAILABLE", schema = ModeBlockUnavailableDto.class)
            })
    public sealed interface RealModeBlockResultDto permits RealModeBlockDto, ModeBlockUnavailableDto {}

    @Schema(name = "ModeBlockUnavailable", description = "Um modo que falhou; o outro bloco continua na mesma resposta")
    public record ModeBlockUnavailableDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNAVAILABLE")
            String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PAPER", "REAL"})
            String market,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"USD", "SOL"})
            String currency,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Código legível por máquina: SERVICE_UNAVAILABLE, ou, no bloco REAL desde a U2,"
                            + " RECONCILIATION_MISMATCH quando o P&L REAL não fecha com o ledger")
            String code,

            @Schema(requiredMode = RequiredMode.REQUIRED) String correlationId)
            implements PaperModeBlockResultDto,
                    RealModeBlockResultDto,
                    PaperComparisonSideResultDto,
                    RealComparisonSideResultDto {

        static ModeBlockUnavailableDto of(BlockResult.Unavailable<?> unavailable) {
            return new ModeBlockUnavailableDto(
                    "UNAVAILABLE",
                    unavailable.market().name(),
                    unavailable.currency(),
                    unavailable.code(),
                    unavailable.correlationId());
        }
    }

    @Schema(name = "PaperModeBlock")
    public record PaperModeBlockDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "AVAILABLE")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "PAPER")
            String market,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "USD")
            String currency,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Aviso fixo do simulado: \"O resultado simulado não garante o resultado real.\""
                            + " (FR4.2, C-16)")
            String disclaimer,

            @Schema(requiredMode = RequiredMode.REQUIRED) boolean empty,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "realized + unrealized - fees; UNKNOWN com o" + " não realizado UNKNOWN")
            PnlAmountDto total,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String realized,

            @Schema(requiredMode = RequiredMode.REQUIRED) PnlAmountDto unrealized,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant unrealizedAsOf,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String fees,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Ordem de criação, nunca por valor")
            List<PaperBotPnlLineDto> bots)
            implements PaperModeBlockResultDto {}

    @Schema(name = "PaperBotPnlLine")
    public record PaperBotPnlLineDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID botId,

            @Schema(requiredMode = RequiredMode.REQUIRED) String name,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACTIVE", "PAUSED", "REMOVED"})
            String botStatus,

            @Schema(requiredMode = RequiredMode.REQUIRED) PnlAmountDto total,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String realized,

            @Schema(requiredMode = RequiredMode.REQUIRED) PnlAmountDto unrealized,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String fees) {}

    @Schema(
            name = "RealModeBlock",
            description = "realizedLamports é bruto de taxas e totalLamports = realized + unrealized - fees (C10, desde"
                    + " a U2); o total do modo vem do ledger e é igual à soma dos bots mais a linha \"Sem bot\"")
    public record RealModeBlockDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "AVAILABLE")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "REAL")
            String market,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "SOL")
            String currency,

            @Schema(requiredMode = RequiredMode.REQUIRED) boolean empty,

            @Schema(requiredMode = RequiredMode.REQUIRED) LamportsAmountDto totalLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED) LamportsAmountDto unrealizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant unrealizedAsOf,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    pattern = LAMPORTS,
                    description = "Taxas do período; descontadas do total, nunca do realizado nem do custo")
            String feesLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Ordem de criação, nunca por valor")
            List<RealBotPnlLineDto> bots,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    implementation = UnownedPnlLineDto.class,
                    types = {"object", "null"},
                    description = "Linha \"Sem bot\" (FR3.4, FR5.3). Desde a U2, sempre um objeto: zero e sem motivo"
                            + " quando nada ficou sem dono")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            UnownedPnlLineDto unowned)
            implements RealModeBlockResultDto {}

    @Schema(name = "RealBotPnlLine")
    public record RealBotPnlLineDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID botId,

            @Schema(requiredMode = RequiredMode.REQUIRED) String name,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACTIVE", "PAUSED", "REMOVED"})
            String botStatus,

            @Schema(requiredMode = RequiredMode.REQUIRED) LamportsAmountDto totalLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED) LamportsAmountDto unrealizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String feesLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED) ProratedSharesDto prorated) {}

    @Schema(name = "ProratedShares", description = "Dono do formato: U2. Vazio até a U2")
    public record ProratedSharesDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ProratedSummaryDto> summary,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            int count,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "As 10 parcelas mais recentes; as demais pelo C5")
            List<ProratedLineDto> lines) {}

    @Schema(name = "ProratedSummary")
    public record ProratedSummaryDto(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"LIQUIDATION", "CONDITIONAL", "SOLD_BY_OTHER_BOT"})
            String origin,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports) {}

    @Schema(name = "ProratedLine")
    public record ProratedLineDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant at,

            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"LIQUIDATION", "CONDITIONAL", "SOLD_BY_OTHER_BOT"})
            String origin,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,30}$")
            String tokenBaseUnits,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String receivedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String feeLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports) {}

    @Schema(name = "UnownedPnlLine", description = "Diferente de zero só com pelo menos um motivo (FR5.3)")
    public record UnownedPnlLineDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED) LamportsAmountDto unrealizedLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String feesLamports,

            // The allowed values go on the items: on the array itself they made an enum of the array (U1 defect).
            @ArraySchema(
                    schema = @Schema(allowableValues = {"UNOWNED_EXCESS", "UNOWNED_NO_COST", "ORDER_RECORD_MISSING"}),
                    arraySchema =
                            @Schema(
                                    requiredMode = RequiredMode.REQUIRED,
                                    description =
                                            "Conjunto, sem repetição (BR5.6: SURPLUS_WITHOUT_OWNER da U1 virou UNOWNED_EXCESS)"))
            List<String> reasons) {}

    // ----------------------------------------------------------------- C5 (261121-paper-real-unico U3)

    /** C5 (BR4.2, BR4.3): one page of the lines of a REAL bot, newest first; {@code nextCursor} null at the end. */
    @Schema(name = "RealBotPnlLinePage")
    public record RealBotPnlLinePageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "SOL")
            String currency,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<RealBotPnlLineItemDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "Cursor opaco da página seguinte; null quando não há mais linhas")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String nextCursor) {}

    /** C5 (BR4.1, BR4.4): one line; a buy realizes {@code "0"}, which is not missing data. */
    @Schema(name = "RealBotPnlLineItem")
    public record RealBotPnlLineItemDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant at,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL"})
            String side,

            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BOT", "LIQUIDATION", "CONDITIONAL", "SOLD_BY_OTHER_BOT"},
                    description = "BOT para as ordens do próprio bot; as demais são parcelas rateadas")
            String origin,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,30}$")
            String tokenBaseUnits,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String amountLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String feeLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS)
            String realizedLamports) {}

    // ----------------------------------------------------------------- C6 (261121-paper-real-unico U3)

    /**
     * C6 (BR5.4, BR6.2, AC8.1.2): only the period and the two sides; no field of a difference, a percentage, a ranking
     * nor a total.
     */
    @Schema(
            name = "PnlComparison",
            description = "Só o período e os dois lados; nenhum campo de diferença, percentual, ranking ou total")
    public record PnlComparisonDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) PnlPeriodDto period,
            @Schema(requiredMode = RequiredMode.REQUIRED) PaperComparisonSideResultDto paper,
            @Schema(requiredMode = RequiredMode.REQUIRED) RealComparisonSideResultDto real) {

        static PnlComparisonDto of(
                PnlPeriodDto period,
                BlockResult<PaperComparisonSideDto> paper,
                BlockResult<RealComparisonSideDto> real) {
            PaperComparisonSideResultDto paperSide =
                    switch (paper) {
                        case BlockResult.Available<PaperComparisonSideDto> available -> available.block();
                        case BlockResult.Unavailable<PaperComparisonSideDto> unavailable ->
                            ModeBlockUnavailableDto.of(unavailable);
                    };
            RealComparisonSideResultDto realSide =
                    switch (real) {
                        case BlockResult.Available<RealComparisonSideDto> available -> available.block();
                        case BlockResult.Unavailable<RealComparisonSideDto> unavailable ->
                            ModeBlockUnavailableDto.of(unavailable);
                    };
            return new PnlComparisonDto(period, paperSide, realSide);
        }
    }

    @Schema(
            name = "PaperComparisonSideResult",
            oneOf = {PaperComparisonSideDto.class, ModeBlockUnavailableDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "AVAILABLE", schema = PaperComparisonSideDto.class),
                @DiscriminatorMapping(value = "UNAVAILABLE", schema = ModeBlockUnavailableDto.class)
            })
    public sealed interface PaperComparisonSideResultDto permits PaperComparisonSideDto, ModeBlockUnavailableDto {}

    @Schema(
            name = "RealComparisonSideResult",
            oneOf = {RealComparisonSideDto.class, ModeBlockUnavailableDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "AVAILABLE", schema = RealComparisonSideDto.class),
                @DiscriminatorMapping(value = "UNAVAILABLE", schema = ModeBlockUnavailableDto.class)
            })
    public sealed interface RealComparisonSideResultDto permits RealComparisonSideDto, ModeBlockUnavailableDto {}

    /** C6, the PAPER side (AC8.1.3): in US$, with the fixed disclaimer. */
    @Schema(name = "PaperComparisonSide")
    public record PaperComparisonSideDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "AVAILABLE")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "PAPER")
            String market,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "USD")
            String currency,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Aviso fixo do simulado: \"O resultado simulado não garante o resultado real.\"")
            String disclaimer,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt,

            @Schema(requiredMode = RequiredMode.REQUIRED) String templateId,
            @Schema(requiredMode = RequiredMode.REQUIRED) PaperBotPnlLineDto line)
            implements PaperComparisonSideResultDto {}

    /** C6, the REAL side (AC8.1.3): in SOL, never a disclaimer. */
    @Schema(name = "RealComparisonSide")
    public record RealComparisonSideDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "AVAILABLE")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "REAL")
            String market,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "SOL")
            String currency,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt,

            @Schema(requiredMode = RequiredMode.REQUIRED) String templateId,
            @Schema(requiredMode = RequiredMode.REQUIRED) RealBotPnlLineDto line)
            implements RealComparisonSideResultDto {}

    @Schema(
            name = "LamportsAmount",
            oneOf = {LamportsKnownDto.class, LamportsUnknownDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "KNOWN", schema = LamportsKnownDto.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = LamportsUnknownDto.class)
            })
    public sealed interface LamportsAmountDto permits LamportsKnownDto, LamportsUnknownDto {}

    @Schema(name = "LamportsKnown")
    public record LamportsKnownDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    pattern = LAMPORTS,
                    description = "Lamports em texto inteiro com sinal; nunca número JSON nem ponto flutuante")
            String value)
            implements LamportsAmountDto {}

    @Schema(name = "LamportsUnknown", description = "Valor desconhecido; nunca zero (FR3.5, FR3.10)")
    public record LamportsUnknownDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNKNOWN")
            String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PRICE_UNKNOWN", "STALE", "NO_LIQUIDITY"})
            String reason)
            implements LamportsAmountDto {}
}
