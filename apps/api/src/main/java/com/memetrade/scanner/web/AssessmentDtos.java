package com.memetrade.scanner.web;

import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentDetail;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.scanner.web.ScannerDtos.MarketValueDto;
import com.memetrade.scanner.web.ScannerDtos.RiskSummaryDto;
import com.memetrade.scanner.web.ScannerDtos.UnknownValueDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * C2 {@code Assessment}: how the indicator was made (BR3.9). U4 additions: {@code computedAt},
 * {@code bandRaisedByUnknown} and the factor risk. Percentages are DecimalString in percentage points, scale 4.
 */
public final class AssessmentDtos {

    static final Map<FactorId, String> LABELS = Map.of(
            FactorId.LIQUIDITY, "Liquidity",
            FactorId.HOLDERS, "Holders",
            FactorId.VOLUME_24H, "Volume 24h",
            FactorId.PRICE_CHANGE_1H, "Variação de preço em 1 hora");

    private AssessmentDtos() {}

    @Schema(name = "Assessment")
    public record AssessmentDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) RiskSummaryDto risk,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = ScannerDtos.DECIMAL, description = "Percentual")
            String coverage,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = ScannerDtos.DECIMAL, description = "Percentual")
            String minCoverage,

            @Schema(requiredMode = RequiredMode.REQUIRED) String weightSetVersion,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Sempre true: indicador em validação")
            boolean inValidation,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"NO_TRADE", "ELIGIBLE"},
                    description = "NO_TRADE quando indisponível (FR6.2)")
            String decisionHint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "true quando a faixa Baixo passou a Médio por fator sem dado (BR3.4)")
            boolean bandRaisedByUnknown,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time") Instant computedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AssessmentFactorDto> factors) {

        static AssessmentDto of(AssessmentDetail detail) {
            AssessmentResult risk = detail.available()
                    ? new AssessmentResult.Available(
                            detail.score().orElseThrow(), detail.band().orElseThrow(), detail.weightSetVersion())
                    : new AssessmentResult.Unavailable(
                            detail.unavailableReason().orElseThrow());
            return new AssessmentDto(
                    RiskSummaryDto.of(risk),
                    detail.coverage().setScale(4).toPlainString(),
                    detail.minCoverage().setScale(4).toPlainString(),
                    Integer.toString(detail.weightSetVersion()),
                    true,
                    detail.decisionHint().name(),
                    detail.bandRaisedByUnknown(),
                    detail.computedAt(),
                    detail.factors().stream().map(AssessmentFactorDto::of).toList());
        }
    }

    @Schema(name = "AssessmentFactor")
    public record AssessmentFactorDto(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"LIQUIDITY", "HOLDERS", "VOLUME_24H", "PRICE_CHANGE_1H"})
            String factorId,

            @Schema(requiredMode = RequiredMode.REQUIRED) String label,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = ScannerDtos.DECIMAL) String weight,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto input,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", maximum = "100") int factorRisk,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    oneOf = {String.class, UnknownValueDto.class},
                    description = "Pontos do indicador (DecimalString) ou UNKNOWN quando a entrada é unknown")
            Object contribution) {

        static AssessmentFactorDto of(AssessmentDetail.Factor factor) {
            Object contribution = factor.contribution()
                    .<Object>map(value -> value.toPlainString())
                    .orElseGet(() -> new UnknownValueDto(
                            "UNKNOWN",
                            factor.input() instanceof com.memetrade.marketdata.api.MarketValue.Unknown u
                                    ? u.reason().name()
                                    : UnknownReason.NOT_PROVIDED.name(),
                            null));
            return new AssessmentFactorDto(
                    factor.factorId().name(),
                    LABELS.get(factor.factorId()),
                    factor.weight().toPlainString(),
                    MarketValueDto.of(factor.input()),
                    factor.factorRisk(),
                    contribution);
        }
    }
}
