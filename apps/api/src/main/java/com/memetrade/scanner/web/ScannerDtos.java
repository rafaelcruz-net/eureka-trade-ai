package com.memetrade.scanner.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.scanner.domain.ScannerPage;
import com.memetrade.scanner.domain.ScannerRow;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;

/**
 * JSON shapes of C2 in U1. Decimals travel as text (DecimalString), never JSON numbers; every market value is a
 * MarketValue discriminated by {@code status}; {@code null} never stands for absence.
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
            return switch (value) {
                case MarketValue.Known k ->
                    new KnownValueDto(
                            "KNOWN", k.value().toPlainString(), SourceRefDto.of(k.source()), k.observedAt(), k.stale());
                case MarketValue.Unknown u ->
                    new UnknownValueDto(
                            "UNKNOWN",
                            u.reason().name(),
                            u.source().map(SourceRefDto::of).orElse(null));
            };
        }
    }

    @Schema(name = "KnownValue")
    public record KnownValueDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = DECIMAL, description = "Decimal exato em texto")
            String value,

            @Schema(requiredMode = RequiredMode.REQUIRED) SourceRefDto source,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant observedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "true quando a idade passa de 5 minutos")
            boolean stale)
            implements MarketValueDto {}

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
            @Schema(requiredMode = RequiredMode.REQUIRED) MarketValueDto ageMinutes) {

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
                    MarketValueDto.of(row.ageMinutes()));
        }
    }

    @Schema(name = "SourceStatus")
    public record SourceStatusDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) SourceRefDto source,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"OK", "DEGRADED", "FAILED"})
            String status,

            @Schema(format = "date-time", description = "Ausente enquanto a fonte nunca coletou")
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
            @Schema(requiredMode = RequiredMode.REQUIRED) int excludedByUnknown,
            @Schema(requiredMode = RequiredMode.REQUIRED) int pendingNewTokens) {

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
}
