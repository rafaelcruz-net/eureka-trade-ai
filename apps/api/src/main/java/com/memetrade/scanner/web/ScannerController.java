package com.memetrade.scanner.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.scanner.domain.ScannerPage;
import com.memetrade.scanner.domain.ScannerQuery;
import com.memetrade.scanner.domain.ScannerQueryParser;
import com.memetrade.scanner.domain.ScannerService;
import com.memetrade.scanner.domain.SourcesUnavailableException;
import com.memetrade.scanner.web.ScannerDtos.ScannerPageDto;
import com.memetrade.scanner.web.ScannerDtos.SourceStatusDto;
import com.memetrade.scanner.web.ScannerDtos.SourceUnavailableProblemDto;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.shared.web.ProblemWriter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F3, {@code GET /api/v1/scanner/tokens} (C2, completed by U4): authenticated guest route over global market data. The
 * request is validated as a whole by {@link ScannerQueryParser} (BR2.7: every invalid field in one {@code 400}); a
 * {@code tenantId} sent by the client is ignored. Every source FAILED answers {@code 503 SOURCE_UNAVAILABLE} with the
 * state of the sources (BR1.4). Authentication, the role rule and the per-person rate limit run before (filters).
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class ScannerController {

    private final ScannerService scanner;
    private final AuthContext auth;
    private final com.memetrade.marketdata.api.MarketDataQuery market;
    private final DistributionSummary excluded;

    /** @param market M4: the kind of the active source and its attribution on every page (U2 BR1.8) */
    public ScannerController(
            ScannerService scanner,
            AuthContext auth,
            com.memetrade.marketdata.api.MarketDataQuery market,
            MeterRegistry registry) {
        this.scanner = scanner;
        this.auth = auth;
        this.market = market;
        this.excluded =
                DistributionSummary.builder("scanner_excluded_by_unknown").register(registry);
    }

    @Operation(
            operationId = "listScannerTokens",
            summary = "Lista paginada com filtros, ordem determinística e desempate estável (FR5.1, FR5.2)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página da foto da lista"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED com a lista de campos (BR2.7)",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "429",
                        description = "RATE_LIMITED, com Retry-After",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = "SOURCE_UNAVAILABLE quando todas as fontes falham (AC2.3.3)",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = SourceUnavailableProblemDto.class)))
            })
    @GetMapping("/api/v1/scanner/tokens")
    public ScannerPageDto list(
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"AGE", "LIQUIDITY", "VOLUME_24H", "HOLDERS", "RISK_SCORE"
                                            }))
                    @RequestParam(required = false)
                    String sort,
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"ASC", "DESC"}))
                    @RequestParam(required = false)
                    String order,
            @Parameter(schema = @Schema(type = "string", pattern = ScannerDtos.DECIMAL)) @RequestParam(required = false)
                    String minLiquidity,
            @Parameter(schema = @Schema(type = "integer", minimum = "0")) @RequestParam(required = false)
                    String maxAgeMinutes,
            @Parameter(schema = @Schema(type = "string", pattern = ScannerDtos.DECIMAL)) @RequestParam(required = false)
                    String minVolume24h,
            @Parameter(schema = @Schema(type = "integer", minimum = "0")) @RequestParam(required = false)
                    String minHolders,
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100"))
                    @RequestParam(required = false)
                    String maxRiskScore,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String sourceId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        auth.current();
        ScannerQuery query = ScannerQueryParser.parse(parameters);
        ScannerPage page = scanner.list(query);
        if (query.filters().anyActive()) {
            excluded.record(page.excludedByUnknown());
        }
        return ScannerPageDto.of(page, ScannerDtos.DataSourceDto.of(market));
    }

    /** BR1.4: {@code 503 SOURCE_UNAVAILABLE} with the {@code sources} extension, never zeroed or old values. */
    @ExceptionHandler(SourcesUnavailableException.class)
    public ResponseEntity<SourceUnavailableProblemDto> sourcesUnavailable(SourcesUnavailableException failure) {
        ErrorCode code = failure.code();
        return ResponseEntity.status(code.status())
                .contentType(MediaType.parseMediaType(ProblemWriter.PROBLEM_JSON))
                .body(new SourceUnavailableProblemDto(
                        code.type(),
                        code.title(),
                        code.status(),
                        failure.getMessage(),
                        code.name(),
                        CorrelationId.current(),
                        failure.sources().stream().map(SourceStatusDto::of).toList()));
    }
}
