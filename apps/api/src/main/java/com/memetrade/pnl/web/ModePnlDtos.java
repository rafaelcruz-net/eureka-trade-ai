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

    @Schema(
            name = "PnlByMode",
            description = "Só o período e os dois blocos. Não existe campo de total fora dos blocos" + " (FR4.1, NFR3)")
    public record PnlByModeDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) PnlPeriodDto period,
            @Schema(requiredMode = RequiredMode.REQUIRED) PaperModeBlockResultDto paper,
            @Schema(requiredMode = RequiredMode.REQUIRED) RealModeBlockResultDto real) {

        static PnlByModeDto of(
                String period, BlockResult<PaperModeBlockDto> paper, BlockResult<RealModeBlockDto> real) {
            return new PnlByModeDto(new PnlPeriodDto(period, null, null), paperOf(paper), realOf(real));
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
            implements PaperModeBlockResultDto, RealModeBlockResultDto {

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
