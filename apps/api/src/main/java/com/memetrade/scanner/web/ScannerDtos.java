package com.memetrade.scanner.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.scanner.domain.ScannerPage;
import com.memetrade.scanner.domain.ScannerRow;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * JSON shapes of C2 (U1, completed by U4). Decimals travel as text (DecimalString), never JSON numbers; every market
 * value is a MarketValue discriminated by {@code status}; {@code null} never stands for absence. U4 additions:
 * {@code divergence} on a KNOWN value, {@code risk} on a row, {@code PENDING} and an optional {@code lastUpdatedAt} on a
 * source status, and the 503 problem with {@code sources}.
 */
public final class ScannerDtos {

    static final String DECIMAL = "^-?\\d+(\\.\\d+)?$";

    private ScannerDtos() {}

    @Schema(name = "SourceRef")
    public record SourceRefDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String sourceId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean simulated) {

        static SourceRefDto of(SourceRef ref) {
            return new SourceRefDto(ref.sourceId(), ref.name(), ref.simulated());
        }
    }

    @Schema(
            name = "MarketValue",
            oneOf = {KnownValueDto.class, UnknownValueDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "KNOWN", schema = KnownValueDto.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = UnknownValueDto.class)
            })
    public sealed interface MarketValueDto permits KnownValueDto, UnknownValueDto {

        static MarketValueDto of(MarketValue value) {
            return of(value, List.of());
        }

        static MarketValueDto of(ResolvedField field) {
            return of(field.value(), field.divergent() ? field.otherOrigins() : List.of());
        }

        static MarketValueDto of(MarketValue value, List<MarketValue.Known> divergent) {
            return switch (value) {
                case MarketValue.Known k ->
                    new KnownValueDto(
                            "KNOWN",
                            k.value().toPlainString(),
                            SourceRefDto.of(k.source()),
                            k.observedAt(),
                            k.stale(),
                            divergent.isEmpty()
                                    ? null
                                    : divergent.stream().map(DivergenceDto::of).toList());
                case MarketValue.Unknown u ->
                    new UnknownValueDto(
                            "UNKNOWN",
                            u.reason().name(),
                            u.source().map(SourceRefDto::of).orElse(null));
            };
        }
    }

    @Schema(name = "KnownValue")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnownValueDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL, description = "Decimal exato em texto")
            String value,

            @Schema(requiredMode = RequiredMode.REQUIRED) SourceRefDto source,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant observedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "true quando a idade passa de 5 minutos")
            boolean stale,

            @Schema(description = "Presente só quando outra fonte diverge acima da tolerância (FR4.7)")
            List<DivergenceDto> divergence)
            implements MarketValueDto {}

    @Schema(name = "Divergence")
    public record DivergenceDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) SourceRefDto source,
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL) String value,
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time") Instant observedAt) {

        static DivergenceDto of(MarketValue.Known origin) {
            return new DivergenceDto(
                    SourceRefDto.of(origin.source()), origin.value().toPlainString(), origin.observedAt());
        }
    }

    @Schema(name = "UnknownValue")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnknownValueDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNKNOWN")
            String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"NOT_PROVIDED", "SOURCE_UNAVAILABLE", "NOT_YET_OBSERVED"})
            String reason,

            SourceRefDto source)
            implements MarketValueDto {}

    @Schema(
            name = "RiskSummary",
            oneOf = {AvailableRiskDto.class, UnavailableRiskDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "AVAILABLE", schema = AvailableRiskDto.class),
                @DiscriminatorMapping(value = "UNAVAILABLE", schema = UnavailableRiskDto.class)
            })
    public sealed interface RiskSummaryDto permits AvailableRiskDto, UnavailableRiskDto {

        static RiskSummaryDto of(AssessmentResult result) {
            return switch (result) {
                case AssessmentResult.Available a ->
                    new AvailableRiskDto("AVAILABLE", a.score(), a.band().name());
                case AssessmentResult.Unavailable u ->
                    new UnavailableRiskDto("UNAVAILABLE", u.reason().name());
            };
        }
    }

    @Schema(name = "AvailableRisk")
    public record AvailableRiskDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "AVAILABLE") String status,
            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", maximum = "100") int score,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"LOW", "MEDIUM", "HIGH"})
            String band)
            implements RiskSummaryDto {}

    @Schema(name = "UnavailableRisk")
    public record UnavailableRiskDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNAVAILABLE") String status,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"INSUFFICIENT_COVERAGE", "SOURCE_UNAVAILABLE"})
            String reason)
            implements RiskSummaryDto {}

    @Schema(name = "Coverage")
    public record CoverageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) int informed,
            @Schema(requiredMode = RequiredMode.REQUIRED) int total) {}

    @Schema(name = "ScannerRow")
    public record ScannerRowDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String chain,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,
            @Schema(requiredMode = RequiredMode.REQUIRED) String symbol,
            String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) CoverageDto coverage,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto price,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto liquidity,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto volume24h,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto holders,
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto ageMinutes,
            @Schema(requiredMode = RequiredMode.REQUIRED) RiskSummaryDto risk) {

        static ScannerRowDto of(ScannerRow row) {
            return new ScannerRowDto(
                    row.chain(),
                    row.mint(),
                    row.symbol(),
                    row.name(),
                    new CoverageDto(row.coverageInformed(), row.coverageTotal()),
                    MarketValueDto.of(row.price()),
                    MarketValueDto.of(row.liquidity()),
                    MarketValueDto.of(row.volume24h()),
                    MarketValueDto.of(row.holders()),
                    MarketValueDto.of(row.ageMinutes()),
                    RiskSummaryDto.of(row.risk()));
        }
    }

    @Schema(name = "SourceStatus")
    public record SourceStatusDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) SourceRefDto source,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "OK", "DEGRADED", "FAILED"})
            String status,

            @Schema(format = "date-time", description = "Ausente enquanto a fonte nunca coletou (PENDING)")
            Instant lastUpdatedAt) {

        static SourceStatusDto of(SourceStatus status) {
            return new SourceStatusDto(
                    SourceRefDto.of(status.source()),
                    status.status().name(),
                    status.lastUpdatedAt().orElse(null));
        }
    }

    @Schema(name = "ScannerPage")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScannerPageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ScannerRowDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null quando não há próxima página")
            String nextCursor,

            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<SourceStatusDto> sources,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Tokens fora do filtro só por campo unknown, na foto inteira (AC2.2.2)")
            long excludedByUnknown,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Novidades em espera para \"Atualizar lista\"")
            long pendingNewTokens) {

        static ScannerPageDto of(ScannerPage page) {
            return new ScannerPageDto(
                    page.items().stream().map(ScannerRowDto::of).toList(),
                    page.nextCursor().orElse(null),
                    page.total(),
                    page.sources().stream().map(SourceStatusDto::of).toList(),
                    page.excludedByUnknown(),
                    page.pendingNewTokens());
        }
    }

    /** RFC 7807 problem of {@code 503 SOURCE_UNAVAILABLE} with the {@code sources} extension (BR1.4). */
    @Schema(name = "SourceUnavailableProblem")
    public record SourceUnavailableProblemDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) URI type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) int status,
            String detail,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "SOURCE_UNAVAILABLE") String code,
            @Schema(requiredMode = RequiredMode.REQUIRED) String correlationId,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<SourceStatusDto> sources) {}
}
