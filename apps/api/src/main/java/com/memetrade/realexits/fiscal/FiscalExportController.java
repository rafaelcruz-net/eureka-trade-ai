package com.memetrade.realexits.fiscal;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.realexits.infra.RealExitsMetrics;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Route R5 {@code GET /api/v1/real/exports/fiscal} (BR7, F4): the CSV of the REAL ledger of the person of the token,
 * generated on demand in a stream ({@link StreamingResponseBody}) and never stored. It needs neither the barrier, nor the
 * exits flag, nor a release in force: it works with the kill switch on and the REAL paused (BR7.11, U7-NFR4.12); like
 * every REAL read it follows the reacceptance of the terms. The period is two dates of the person (and her zone, by
 * default {@code America/Sao_Paulo}); the limit is 10 per hour ({@code 429 RATE_LIMITED} with {@code Retry-After}).
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
@Tag(name = "REAL — exportação fiscal")
public class FiscalExportController {

    /** The zone of the person when the app does not send one (the beta is in Brazil). */
    static final String DEFAULT_ZONE = "America/Sao_Paulo";

    private static final Logger log = LoggerFactory.getLogger(FiscalExportController.class);
    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final FiscalExportService exports;
    private final FiscalExportLimiter limiter;
    private final RealExitsMetrics metrics;
    private final AuthContext auth;
    private final Clock clock;

    public FiscalExportController(
            FiscalExportService exports,
            FiscalExportLimiter limiter,
            RealExitsMetrics metrics,
            AuthContext auth,
            Clock clock) {
        this.exports = exports;
        this.limiter = limiter;
        this.metrics = metrics;
        this.auth = auth;
        this.clock = clock;
    }

    @Operation(
            operationId = "exportRealFiscalCsv",
            summary = "Histórico REAL da pessoa em CSV (UTF-8 com BOM, ponto e vírgula, vírgula decimal)",
            security = @SecurityRequirement(name = "accessToken"),
            responses =
                    @ApiResponse(
                            responseCode = "200",
                            description = "O arquivo CSV, gerado na hora e não guardado",
                            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
    @GetMapping("/api/v1/real/exports/fiscal")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String tz) {
        Principal person = auth.current();
        FiscalPeriod period = period(from, to, tz);
        Optional<Duration> wait = limiter.acquire(person.userId());
        if (wait.isPresent()) {
            metrics.exported("RATE_LIMITED");
            throw new ApiException(
                    ErrorCode.RATE_LIMITED,
                    "At most 10 fiscal exports per hour.",
                    List.of(),
                    null,
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(wait.get().toSeconds())));
        }
        StreamingResponseBody body = out -> {
            try {
                exports.export(person, period, out);
                metrics.exported("OK");
            } catch (RuntimeException failure) {
                log.error(
                        "REAL fiscal export stopped: error={}",
                        failure.getClass().getSimpleName());
                throw failure;
            }
        };
        return ResponseEntity.ok()
                .contentType(CSV)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(period.fileName())
                                .build()
                                .toString())
                .body(body);
    }

    /** BR7.7, S31: dates of the person and her zone; more than one year, an inverted period or a bad date is 422. */
    private FiscalPeriod period(String from, String to, String tz) {
        try {
            ZoneId zone = ZoneId.of(tz == null || tz.isBlank() ? DEFAULT_ZONE : tz);
            LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
            return FiscalPeriod.of(date(from), date(to), zone, today);
        } catch (DateTimeException | IllegalArgumentException invalid) {
            metrics.exported("INVALID");
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_FIELDS,
                    "One or more fields are invalid.",
                    List.of(new FieldViolation(
                            "period",
                            invalid instanceof DateTimeException
                                    ? "Use datas AAAA-MM-DD e um fuso válido."
                                    : invalid.getMessage())),
                    null,
                    Map.of());
        }
    }

    private static Optional<LocalDate> date(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // A malformed date is a DateTimeParseException, a DateTimeException: 422 in period().
        return Optional.of(LocalDate.parse(text));
    }
}
