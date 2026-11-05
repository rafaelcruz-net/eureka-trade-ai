package com.memetrade.riskcontrol.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.riskcontrol.domain.MarketInputs;
import com.memetrade.riskcontrol.domain.ReasonDetail;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.scanner.web.ScannerDtos.MarketValueDto;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of C21 (contract-summary.md, with the additive fields of the U6 Functional Design, Q10). Every
 * amount, price and percentage is a {@code DecimalString} or {@code UNKNOWN}, never a JSON number; market values are
 * the common {@code MarketValue} (KNOWN or UNKNOWN, never zero); instants are UTC; {@code mode} is always PAPER.
 */
public final class RejectionDtos {

    static final String DECIMAL_OR_UNKNOWN = "^(-?\\d+(\\.\\d+)?|UNKNOWN)$";

    private RejectionDtos() {}

    @Schema(
            name = "RejectionPage",
            description = "Página das ordens recusadas do bot, da mais recente para a mais antiga")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RejectionPageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<OrderRejectionDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null quando não há próxima página")
            String nextCursor,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Contagem limitada a 10000")
            long total,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "true quando há mais de 10000 recusas")
            boolean totalCapped) {}

    @Schema(name = "OrderRejection", description = "Ordem recusada com o limite que barrou e o horário (AC4.10.4)")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrderRejectionDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID rejectionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID decisionId,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "PAPER")
            String mode,

            @ArraySchema(
                    schema =
                            @Schema(
                                    allowableValues = {
                                        "ORDER_SIZE",
                                        "TOKEN_EXPOSURE",
                                        "TOTAL_EXPOSURE",
                                        "SLIPPAGE",
                                        "MIN_LIQUIDITY",
                                        "STALE_DATA",
                                        "INTERVAL",
                                        "DUPLICATE",
                                        "INSUFFICIENT_PAPER_BALANCE",
                                        "DAILY_LOSS",
                                        "DRAWDOWN"
                                    }),
                    arraySchema =
                            @Schema(
                                    requiredMode = RequiredMode.REQUIRED,
                                    description = "Na ordem fixa; o primeiro é o motivo principal"))
            List<String> reasons,

            @Schema(requiredMode = RequiredMode.REQUIRED) long limitsVersion,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant evaluatedAt,

            @ArraySchema(
                    schema = @Schema(allowableValues = {"DAILY_LOSS", "DRAWDOWN"}),
                    arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED, description = "Vazio quando nenhum"))
            List<String> pauseTriggers,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<RejectionReasonDetailDto> details,
            @Schema(requiredMode = RequiredMode.REQUIRED) RejectionMarketDto market) {

        static OrderRejectionDto of(RejectionRecord rejection) {
            return new OrderRejectionDto(
                    rejection.rejectionId(),
                    rejection.decisionId(),
                    "PAPER",
                    rejection.reasons().stream().map(Enum::name).toList(),
                    rejection.limitsVersion(),
                    rejection.evaluatedAt(),
                    rejection.pauseTriggers().stream().map(Enum::name).toList(),
                    rejection.details().stream()
                            .map(RejectionReasonDetailDto::of)
                            .toList(),
                    RejectionMarketDto.of(rejection.market()));
        }
    }

    @Schema(name = "RejectionReasonDetail", description = "O número que barrou um motivo")
    public record RejectionReasonDetailDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String reason,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "DecimalString na escala da unidade ou UNKNOWN; em DUPLICATE, a decisão")
            String measured,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "DecimalString; em DUPLICATE, o evaluationId original")
            String limit,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"USDC", "PERCENT", "SECONDS", "REFERENCE"})
            String unit) {

        static RejectionReasonDetailDto of(ReasonDetail detail) {
            return new RejectionReasonDetailDto(
                    detail.reason().name(),
                    detail.measured(),
                    detail.limit(),
                    detail.unit().name());
        }
    }

    @Schema(name = "RejectionMarket", description = "Dados de mercado usados na avaliação")
    public record RejectionMarketDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto referencePrice,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto liquidity,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    pattern = DECIMAL_OR_UNKNOWN,
                    description = "Pontos com 4 casas, ou UNKNOWN")
            String estimatedSlippagePct,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    pattern = DECIMAL_OR_UNKNOWN,
                    description = "USDC com 6 casas, ou UNKNOWN")
            String orderValue) {

        static RejectionMarketDto of(MarketInputs market) {
            return new RejectionMarketDto(
                    MarketValueDto.of(market.referencePrice()),
                    MarketValueDto.of(market.liquidity()),
                    market.estimatedSlippagePct().text(),
                    market.orderValue().text());
        }
    }
}
