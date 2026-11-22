package com.memetrade.riskcontrol.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import com.memetrade.riskcontrol.api.RealLimitsUnavailable;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 261121-paper-real-unico U1, C3 (BR3.1 to BR3.3, F3): the REAL limits of the account, read only, for the limits step
 * of a REAL bot. Tenant and person come from the token; nothing is reserved nor written. Without an ACTIVE bot wallet
 * the answer is {@code 409 REAL_NOT_ELIGIBLE} with {@code WALLET_INACTIVE}; a failed read is 503. The reads run in their
 * own read-only transaction with a {@code statement_timeout} of 500 ms (NFR1.3), no lock. The route is outside the
 * exceptions of the terms reacceptance: with new terms pending it answers {@code 403 TERMS_REACCEPTANCE_REQUIRED}.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class RealAccountLimitsController {

    private static final Logger log = LoggerFactory.getLogger(RealAccountLimitsController.class);

    /** NFR1.3: the deadline of the reads of C3 (performance-design.md), fixed. */
    static final Duration TIMEOUT = Duration.ofMillis(500);

    private final RealAccountLimits limits;
    private final AuthContext auth;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    public RealAccountLimitsController(
            RealAccountLimits limits, AuthContext auth, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.limits = limits;
        this.auth = auth;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Operation(
            operationId = "getRealAccountLimits",
            summary = "Limites REAL da conta, só leitura, em lamports arredondados para baixo, iguais aos da barreira"
                    + " (ADR-008)",
            tags = "bot-controller",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Os limites"),
                @ApiResponse(
                        responseCode = "409",
                        description = "REAL_NOT_ELIGIBLE com reason WALLET_INACTIVE: sem carteira do bot ativa não há"
                                + " limite a mostrar",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = "SERVICE_UNAVAILABLE: a leitura falhou",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/real/account-limits")
    public ResponseEntity<RealAccountLimitsDtos.RealAccountLimitsDto> accountLimits() {
        Principal person = auth.current();
        Optional<RealAccountLimitsView> view;
        try {
            view = readOnly.execute(status -> {
                jdbc.sql("set local statement_timeout = '" + TIMEOUT.toMillis() + "ms'")
                        .update();
                return limits.read(person.tenantId(), person.userId());
            });
        } catch (RealLimitsUnavailable
                | org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            log.warn(
                    "REAL account limits unavailable: cause={}",
                    failure.getClass().getSimpleName());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable.");
        }
        if (view.isEmpty()) {
            throw new ApiException(
                    ErrorCode.REAL_NOT_ELIGIBLE,
                    "The person has no active bot wallet.",
                    List.of(),
                    "WALLET_INACTIVE",
                    Map.of());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RealAccountLimitsDtos.RealAccountLimitsDto.of(view.get()));
    }
}
