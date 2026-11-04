package com.memetrade.ledger.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.ledger.api.LedgerReadModel.EntryView;
import com.memetrade.ledger.api.LedgerReadModel.TradeView;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of the ledger routes of C5. Every amount, price, quantity and percentage is a {@code DecimalString}
 * (never a JSON number); instants are UTC; {@code mode} appears only in responses and is always {@code PAPER}.
 */
public final class LedgerDtos {

    static final String DECIMAL = "^-?\\d+(\\.\\d+)?$";

    private LedgerDtos() {}

    static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    @Schema(name = "PaperTrade", description = "Uma operação simulada do bot, cada uma uma única vez (AC4.10.3)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaperTradeDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID orderId,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "PAPER")
            String mode,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"TRADE", "CLOSE"})
            String kind,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL"})
            String side,

            @Schema(requiredMode = RequiredMode.REQUIRED) String chain,
            @Schema(requiredMode = RequiredMode.REQUIRED) String tokenMint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"FILLED", "PARTIALLY_FILLED", "NOT_FILLED", "CLOSE_PENDING"})
            String status,

            @Schema(
                    allowableValues = {
                        "PRICE_UNAVAILABLE",
                        "NO_LIQUIDITY",
                        "QUANTITY_BELOW_MINIMUM",
                        "NO_POSITION",
                        "ACCOUNT_CLOSING",
                        "INSUFFICIENT_BALANCE",
                        "TENANT_MISMATCH"
                    })
            String notFilledReason,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String requestedQuantity,

            @Schema(pattern = DECIMAL) String filledQuantity,
            @Schema(pattern = DECIMAL) String referencePrice,
            @Schema(pattern = DECIMAL) String quotedPrice,
            @Schema(pattern = DECIMAL) String fillPrice,

            @Schema(pattern = DECIMAL, description = "Pontos percentuais")
            String spreadPct,

            @Schema(pattern = DECIMAL, description = "Pontos percentuais")
            String impactPct,

            @Schema(pattern = DECIMAL, description = "Pontos percentuais: spread mais impacto")
            String slippage,

            @Schema(pattern = DECIMAL, description = "Pontos percentuais")
            String liquidityConsumedPct,

            @Schema(pattern = DECIMAL) String grossAmount,
            @Schema(pattern = DECIMAL) String fees,

            @Schema(pattern = DECIMAL, description = "Só em venda e fechamento")
            String realized,

            String marketSource,
            @Schema(format = "date-time") Instant marketObservedAt,
            String simulationPolicyVersion,
            @Schema(requiredMode = RequiredMode.REQUIRED) long limitsVersion,
            @Schema(requiredMode = RequiredMode.REQUIRED) String idempotencyKey,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt,

            @Schema(format = "date-time") Instant filledAt) {

        static PaperTradeDto of(TradeView trade) {
            FillDetails fill = trade.fill().orElse(null);
            return new PaperTradeDto(
                    trade.orderId(),
                    PaperEnums.MODE,
                    trade.kind().name(),
                    trade.side().name(),
                    trade.chain(),
                    trade.tokenMint(),
                    trade.status().name(),
                    trade.notFilledReason().map(Enum::name).orElse(null),
                    text(trade.requestedQuantity()),
                    fill == null ? null : text(fill.filledQuantity()),
                    fill == null ? null : text(fill.referencePrice()),
                    fill == null ? null : text(fill.quotedPrice()),
                    fill == null ? null : text(fill.fillPrice()),
                    fill == null ? null : text(fill.spreadPct()),
                    fill == null ? null : text(fill.impactPct()),
                    fill == null ? null : text(fill.slippagePct()),
                    fill == null ? null : text(fill.liquidityConsumedPct()),
                    fill == null ? null : text(fill.grossAmount()),
                    fill == null ? null : text(fill.fees()),
                    fill == null
                            ? null
                            : fill.realized().map(BigDecimal::toPlainString).orElse(null),
                    fill == null ? null : fill.marketSource(),
                    fill == null ? null : fill.marketObservedAt(),
                    fill == null ? null : fill.simulationPolicyVersion(),
                    trade.limitsVersion(),
                    trade.idempotencyKey(),
                    trade.createdAt(),
                    fill == null ? null : fill.filledAt());
        }
    }

    @Schema(name = "PaperTradePage")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaperTradePageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<PaperTradeDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null quando não há próxima página")
            String nextCursor,

            @Schema(requiredMode = RequiredMode.REQUIRED) long total) {}

    @Schema(name = "LedgerEntry", description = "Lançamento imutável do ledger PAPER (AC5.2.1)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LedgerEntryDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID entryId,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "PAPER")
            String mode,

            @Schema(requiredMode = RequiredMode.REQUIRED) UUID botId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {
                        "FUNDING",
                        "TRADE_COST",
                        "TRADE_PROCEEDS",
                        "SLIPPAGE",
                        "FEE",
                        "CLOSE",
                        "COMPENSATION"
                    })
            String type,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String amount,

            @Schema(requiredMode = RequiredMode.REQUIRED) String asset,
            @Schema(requiredMode = RequiredMode.REQUIRED) String referenceId,

            @Schema(description = "Operação do lançamento, para agrupar (BR10.2)")
            UUID orderId,

            String simulationPolicyVersion,
            UUID compensatesEntryId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt) {

        static LedgerEntryDto of(EntryView entry) {
            return new LedgerEntryDto(
                    entry.entryId(),
                    PaperEnums.MODE,
                    entry.botId(),
                    entry.type().name(),
                    text(entry.amount()),
                    entry.asset(),
                    entry.referenceId(),
                    entry.orderId().orElse(null),
                    entry.simulationPolicyVersion().orElse(null),
                    entry.compensatesEntryId().orElse(null),
                    entry.createdAt());
        }
    }

    @Schema(name = "LedgerEntryPage")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LedgerEntryPageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<LedgerEntryDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null quando não há próxima página")
            String nextCursor,

            @Schema(requiredMode = RequiredMode.REQUIRED) long total) {}

    @Schema(name = "DayUsage", description = "Uso do dia UTC; os limites vêm da U5 na tela (BR7.5)")
    public record DayUsageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String exposure,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String dayLoss,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date")
            LocalDate utcDay) {}
}
