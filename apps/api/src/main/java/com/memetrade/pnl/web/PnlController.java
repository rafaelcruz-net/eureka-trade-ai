package com.memetrade.pnl.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.application.PnlQueryService;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.pnl.application.ReconciliationService.Trigger;
import com.memetrade.pnl.web.PnlDtos.PnlReportDto;
import com.memetrade.pnl.web.PnlDtos.ReconciliationResultDto;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.QueryParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes of C5 owned by the P&L (F7, F9, BR6.1 to BR6.6, BR8.1 to BR8.4): the P&L of a period, the latest
 * reconciliation and "check now". Tenant and person come from the token. "Check now" takes an Idempotency-Key through
 * the common mechanism, only reads the ledger, and is limited to 6 runs per person and minute ({@code 429} with
 * {@code Retry-After}); the reads have no limit.
 */
@RestController
public class PnlController {

    private final PnlQueryService pnl;
    private final ReconciliationService reconciliation;
    private final ReconciliationRunLimiter limiter;
    private final IdempotencyGuard idempotency;
    private final AuthContext auth;
    private final ObjectMapper json;
    private final TransactionTemplate repeatableRead;

    public PnlController(
            PnlQueryService pnl,
            ReconciliationService reconciliation,
            ReconciliationRunLimiter limiter,
            IdempotencyGuard idempotency,
            AuthContext auth,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.pnl = pnl;
        this.reconciliation = reconciliation;
        this.limiter = limiter;
        this.idempotency = idempotency;
        this.auth = auth;
        this.json = json;
        this.repeatableRead = new TransactionTemplate(transactions);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Operation(
            operationId = "getPnl",
            summary = "P&L por token, bot e período [from, to) em UTC (FR10.3)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "P&L do período"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: from >= to, período acima de 366 dias ou campo inválido",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = "SERVICE_UNAVAILABLE: não foi possível calcular agora (BR6.6)",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/pnl")
    public PnlReportDto pnl(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String botId,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String from,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String to,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("botId", "from", "to"));
        Optional<UUID> bot = query.uuid("botId");
        Optional<Instant> start = query.requiredInstant("from");
        Optional<Instant> end = query.requiredInstant("to");
        query.validate();
        return PnlReportDto.of(pnl.report(person, start.orElseThrow(), end.orElseThrow(), bot));
    }

    @Operation(
            operationId = "getReconciliation",
            summary = "Resultado da última conferência entre operações e ledger (FR10.5)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Última conferência"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/reconciliation")
    public ReconciliationResultDto latest(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String botId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("botId"));
        Optional<UUID> bot = query.uuid("botId");
        query.validate();
        bot.ifPresent(id -> pnl.visible(person, id));
        return reconciliation
                .latest(person.tenantId(), person.userId())
                .map(result -> ReconciliationResultDto.of(result, bot))
                .orElseGet(() -> ReconciliationResultDto.nothingYet(
                        reconciliation.hasAccounts(person.tenantId(), person.userId())));
    }

    @Operation(
            operationId = "runReconciliation",
            summary = "Verificar agora: confere as contas PAPER da pessoa na hora (BR8.3)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "200", description = "Resultado da conferência, gravada como ON_DEMAND"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: Idempotency-Key ausente",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "429",
                        description = "RATE_LIMITED, com Retry-After (6 por minuto por pessoa)",
                        headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PostMapping("/api/v1/reconciliation/run")
    public ResponseEntity<ReconciliationResultDto> run(@Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        // The common Idempotency-Key filter already refused a missing or malformed key with 400.
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        IdempotencyRequest idempotent = new IdempotencyRequest(
                IdempotentOperation.RECONCILIATION_RUN,
                IdempotencyScope.authenticated(person, IdempotentOperation.RECONCILIATION_RUN),
                key,
                RUN_DIGEST);
        IdempotentResponse stored = repeatableRead.execute(status -> idempotency.execute(idempotent, () -> {
            limiter.acquire(person.userId()).ifPresent(PnlController::rateLimited);
            ReconciliationResultDto result = ReconciliationResultDto.of(
                    reconciliation.check(person.tenantId(), person.userId(), Trigger.ON_DEMAND), Optional.empty());
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, write(result)));
        }));
        return ResponseEntity.status(stored.status())
                .cacheControl(CacheControl.noStore())
                .body(read(stored.body()));
    }

    /**
     * The request has no body: its content is the operation itself, as a SHA-256 hex digest like every digest of the
     * common mechanism (the stored column holds 64 characters).
     */
    static final String RUN_DIGEST = sha256("POST /api/v1/reconciliation/run");

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }

    private static void rateLimited(Duration wait) {
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many checks; try again later.",
                List.of(),
                null,
                Map.of("Retry-After", String.valueOf(wait.toSeconds())));
    }

    private String write(ReconciliationResultDto result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The reconciliation result could not be serialised", failure);
        }
    }

    private ReconciliationResultDto read(String body) {
        try {
            return json.readValue(body, ReconciliationResultDto.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The stored reconciliation result could not be read", failure);
        }
    }
}
