package com.memetrade.identity.web;

import com.memetrade.identity.domain.invite.InviteLookupService;
import com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter;
import com.memetrade.identity.web.InviteDtos.InviteLookupResponse;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/invites/{code}} (C3, F4), the only route under {@code /api/v1/invites} that needs no session
 * ({@link AuthenticationFilter}). Order: per-IP limit first (every lookup counts, malformed included), then the
 * lookup by digest; malformed and unknown codes get the same {@code 404}. The code itself is never logged: logs and
 * metrics carry the route template only (BR2.3, NFR6.4).
 */
@RestController
@RequestMapping("/api/v1/invites")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class InviteLookupController {

    static final String ROUTE = "/api/v1/invites/{code}";

    private static final Logger log = LoggerFactory.getLogger(InviteLookupController.class);

    private final InviteLookupService lookups;
    private final InviteLookupRateLimiter limiter;
    private final ClientIpResolver ips;

    public InviteLookupController(InviteLookupService lookups, InviteLookupRateLimiter limiter, ClientIpResolver ips) {
        this.lookups = lookups;
        this.limiter = limiter;
        this.ips = ips;
    }

    @Operation(
            operationId = "lookupInvite",
            summary = "Estado do convite e termos a aceitar, sem sessão (AC1.1.1); o código nunca vai para log")
    @ApiResponse(responseCode = "200", description = "Estado derivado, validade, termos e aviso de risco vigentes")
    @ApiResponse(
            responseCode = "404",
            description = "INVITE_NOT_FOUND, igual para código desconhecido e mal formado",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: mais de 10 consultas por minuto deste IP; não diz se o código existe",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/{code}")
    public ResponseEntity<InviteLookupResponse> lookup(
            @Parameter(description = "Código do convite recebido no link") @PathVariable("code") String code,
            @Parameter(hidden = true) HttpServletRequest request) {
        enforceLimit(request);
        return lookups.lookup(code)
                .map(found ->
                        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(InviteLookupResponse.of(found)))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_NOT_FOUND, "Invitation not found."));
    }

    private void enforceLimit(HttpServletRequest request) {
        Optional<Duration> wait = limiter.acquire(ips.resolve(request));
        if (wait.isEmpty()) {
            return;
        }
        long seconds = Math.max(1, (long) Math.ceil(wait.get().toMillis() / 1000.0));
        log.warn("Invitation lookup rate limit exceeded; route={}", ROUTE);
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many invitation lookups; try again later.",
                List.of(),
                "IP_LIMIT",
                Map.of("Retry-After", Long.toString(seconds)));
    }
}
