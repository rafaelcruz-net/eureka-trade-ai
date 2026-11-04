package com.memetrade.pnl.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.pnl.application.PnlQueryService.PnlView;
import com.memetrade.pnl.application.ReconciliationService.Divergence;
import com.memetrade.pnl.application.ReconciliationService.Result;
import com.memetrade.pnl.domain.PnlCalculator;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import com.memetrade.scanner.web.ScannerDtos.MarketValueDto;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Response shapes of the P&L and reconciliation routes of C5. Amounts are {@code DecimalString}; an unknown unrealized
 * result is {@code UNKNOWN} with its reason, never a zero. The {@code mark} reuses the common {@code MarketValue}
 * schema of C2 (one schema per name in the guest OpenAPI).
 */
public final class PnlDtos {

    static final String DECIMAL = "^-?\\d+(\\.\\d+)?$";

    private PnlDtos() {}

    @Schema(
            name = "PnlAmount",
            oneOf = {KnownPnlDto.class, UnknownPnlDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "KNOWN", schema = KnownPnlDto.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = UnknownPnlDto.class)
            })
    public sealed interface PnlAmountDto permits KnownPnlDto, UnknownPnlDto {
        static PnlAmountDto of(Amount amount) {
            return switch (amount) {
                case Amount.Known known ->
                    new KnownPnlDto("KNOWN", known.value().toPlainString());
                case Amount.Unknown unknown ->
                    new UnknownPnlDto("UNKNOWN", unknown.reason().name());
            };
        }
    }

    @Schema(name = "PnlKnown")
    public record KnownPnlDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String value)
            implements PnlAmountDto {}

    @Schema(name = "PnlUnknown")
    public record UnknownPnlDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNKNOWN")
            String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PRICE_UNKNOWN", "STALE", "NO_LIQUIDITY"})
            String reason)
            implements PnlAmountDto {}

    @Schema(name = "PnlTokenLine")
    public record PnlTokenLineDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String chain,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String realized,

            @Schema(requiredMode = RequiredMode.REQUIRED) PnlAmountDto unrealized,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String fees,

            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto mark,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Marcação sem liquidez (AC5.1.4)")
            boolean noLiquidity) {}

    @Schema(name = "PnlReport", description = "P&L PAPER de um período [from, to) em UTC (FR10.3)")
    public record PnlReportDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant from,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant to,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String realized,

            @Schema(requiredMode = RequiredMode.REQUIRED) PnlAmountDto unrealized,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String fees,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Algum não realizado unknown (AC5.1.3)")
            boolean partial,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Nenhuma operação nem posição (BR6.6)")
            boolean empty,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "Instante da marcação: o não realizado é das posições abertas agora")
            Instant unrealizedAsOf,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<PnlTokenLineDto> byToken) {

        static PnlReportDto of(PnlView view) {
            PnlCalculator.Report report = view.report();
            return new PnlReportDto(
                    view.from(),
                    view.to(),
                    report.realized().toPlainString(),
                    PnlAmountDto.of(report.unrealized()),
                    report.fees().toPlainString(),
                    report.partial(),
                    report.empty(),
                    view.unrealizedAsOf(),
                    report.lines().stream()
                            .map(line -> new PnlTokenLineDto(
                                    line.chain(),
                                    line.mint(),
                                    line.realized().toPlainString(),
                                    PnlAmountDto.of(line.unrealized()),
                                    line.fees().toPlainString(),
                                    MarketValueDto.of(Optional.ofNullable(view.mark(line.chain(), line.mint()))
                                            .orElse(new MarketValue.Unknown(
                                                    UnknownReason.NOT_YET_OBSERVED, Optional.empty()))),
                                    line.noLiquidity()))
                            .toList());
        }
    }

    @Schema(name = "ReconciliationDivergence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DivergenceDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID botId,
            UUID orderId,
            UUID positionId,
            UUID entryId,
            @Schema(format = "date") LocalDate utcDay,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {
                        "MISSING_ENTRY",
                        "AMOUNT_MISMATCH",
                        "ORPHAN_ENTRY",
                        "POSITION_MISMATCH",
                        "SUMMARY_MISMATCH"
                    })
            String kind,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String expected,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL)
            String found) {

        static DivergenceDto of(Divergence divergence) {
            return new DivergenceDto(
                    divergence.botId(),
                    divergence.orderId().orElse(null),
                    divergence.positionId().orElse(null),
                    divergence.entryId().orElse(null),
                    divergence.utcDay().orElse(null),
                    divergence.kind().name(),
                    divergence.expected().toPlainString(),
                    divergence.found().toPlainString());
        }
    }

    @Schema(name = "ReconciliationResult", description = "Última conferência entre operações e ledger (FR10.5)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReconciliationResultDto(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"RECONCILED", "DIVERGENT", "PENDING"},
                    description = "PENDING: a pessoa tem conta PAPER e ainda não houve conferência")
            String status,

            @Schema(allowableValues = {"SCHEDULED", "ON_DEMAND"})
            String trigger,

            @Schema(format = "date-time") Instant checkedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int divergenceCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<DivergenceDto> divergences) {

        static ReconciliationResultDto of(Result result, Optional<UUID> botId) {
            List<DivergenceDto> divergences = result.divergences().stream()
                    .filter(d -> botId.isEmpty() || botId.get().equals(d.botId()))
                    .map(DivergenceDto::of)
                    .toList();
            return new ReconciliationResultDto(
                    divergences.isEmpty() ? "RECONCILED" : "DIVERGENT",
                    result.trigger().name(),
                    result.checkedAt(),
                    divergences.size(),
                    divergences);
        }

        static ReconciliationResultDto nothingYet(boolean hasAccounts) {
            return new ReconciliationResultDto(hasAccounts ? "PENDING" : "RECONCILED", null, null, 0, List.of());
        }
    }
}
