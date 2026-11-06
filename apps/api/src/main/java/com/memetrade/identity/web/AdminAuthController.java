package com.memetrade.identity.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.account.WalletAlreadyLinkedException;
import com.memetrade.identity.domain.idempotency.RequestDigest;
import com.memetrade.identity.domain.login.AdminLoginService;
import com.memetrade.identity.domain.login.AdminLoginSettings;
import com.memetrade.identity.domain.login.AdminRejection;
import com.memetrade.identity.domain.login.ChallengeService;
import com.memetrade.identity.domain.login.LoginVerificationService.VerifyCommand;
import com.memetrade.identity.domain.ratelimit.AdminLoginLimiter;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.RefreshOutcome;
import com.memetrade.identity.web.AdminAuthDtos.AdminChallengeRequest;
import com.memetrade.identity.web.AdminAuthDtos.AdminSession;
import com.memetrade.identity.web.AdminAuthDtos.AdminVerifyRequest;
import com.memetrade.identity.web.AuthDtos.ChallengeResponse;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.shared.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator entry of C8 (U8; F1; BR1.1 to BR1.6; AC1.2.6). {@code challenge}, {@code verify} and {@code refresh}
 * need no access token (public routes of the authentication and administrative route rules, by the normalised path);
 * {@code logout} needs the ADMIN token. Answers never reveal the list of administrators (R-05 of the NFR Design): the
 * challenge is issued for any valid address and every refusal of a wallet is the same {@code 403 FORBIDDEN_ROLE}; the
 * exact reason goes only to {@code LOGIN_REJECTED}. Logs carry the masked wallet at most; never the message, the nonce,
 * the signature or a token.
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final ChallengeService challenges;
    private final AdminLoginService admins;
    private final AdminLoginSettings settings;
    private final AdminLoginLimiter limiter;
    private final ClientIpResolver ips;
    private final RequestAuthContext authContext;
    private final AdminAccess access;
    private final IdempotencyGuard idempotency;
    private final LoginMetrics metrics;
    private final Clock clock;
    private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public AdminAuthController(
            ChallengeService challenges,
            AdminLoginService admins,
            AdminLoginSettings settings,
            AdminLoginLimiter limiter,
            ClientIpResolver ips,
            RequestAuthContext authContext,
            AdminAccess access,
            IdempotencyGuard idempotency,
            LoginMetrics metrics,
            Clock clock,
            ObjectMapper json,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.transactions = transactions;
        this.challenges = challenges;
        this.admins = admins;
        this.settings = settings;
        this.limiter = limiter;
        this.ips = ips;
        this.authContext = authContext;
        this.access = access;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.clock = clock;
        this.json = json;
    }

    @Operation(
            operationId = "issueAdminChallenge",
            summary = "Emite o desafio de entrada do administrador para qualquer endereço válido, sem revelar a lista")
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "Desafio com o domínio do app do administrador")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: endereço que não é Base58 ou Idempotency-Key ausente",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: mais de 5 pedidos por minuto do IP ou do par (endereço, IP)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/challenge")
    public ChallengeResponse challenge(@Valid @RequestBody AdminChallengeRequest body, HttpServletRequest request) {
        WalletAddress wallet = AuthController.walletOf(body.walletAddress());
        limit(request, wallet.full());
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        return ChallengeResponse.from(challenges.issueAdmin(wallet, key, digest(body), settings));
    }

    @Operation(
            operationId = "verifyAdminLogin",
            summary = "Verifica a assinatura e abre a sessão do administrador (15 min / 8 h); cookie em Set-Cookie")
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "Sessão de administrador aberta")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED: nonce reutilizado, vencido ou de outro escopo, ou assinatura inválida",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE genérico: carteira fora da lista ou de convidado (motivo só na auditoria)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/verify")
    public ResponseEntity<AdminSession> verify(
            @Valid @RequestBody AdminVerifyRequest body, HttpServletRequest request) {
        AuthController.walletOf(body.walletAddress());
        limit(request, body.walletAddress());
        VerifyCommand command = new VerifyCommand(
                body.walletAddress(),
                body.message(),
                body.signature(),
                body.provider().name(),
                CorrelationId.current());
        AdminLoginService.Outcome outcome;
        try {
            outcome = admins.verify(command);
        } catch (WalletAlreadyLinkedException concurrentFirstEntry) {
            // Two first entries of the same wallet: the loser's transaction rolled back (its nonce is ISSUED again)
            // and the account now exists; one more try finds it.
            outcome = admins.verify(command);
        }
        return switch (outcome) {
            case AdminLoginService.Outcome.Accepted accepted -> {
                log.info("Administrator signed in: wallet={}", accepted.maskedWallet());
                yield sessionResponse(accepted.session(), accepted.maskedWallet());
            }
            case AdminLoginService.Outcome.Rejected rejected -> {
                metrics.adminRefused(rejected.reason().name());
                throw rejection(rejected.reason());
            }
        };
    }

    @Operation(
            operationId = "refreshAdminSession",
            summary = "Troca o cookie de renovação do administrador por um novo token; sem Idempotency-Key")
    @ApiResponse(
            responseCode = "200",
            description = "Novo token de acesso; o cookie é trocado; a sessão não é prorrogada")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED: cookie ausente, reutilizado, sessão vencida ou carteira retirada da lista",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/refresh")
    public ResponseEntity<AdminSession> refresh(
            @Parameter(hidden = true) @CookieValue(name = AdminRefreshCookie.NAME, required = false)
                    String refreshToken) {
        RefreshOutcome outcome = admins.refresh(refreshToken, CorrelationId.current());
        return switch (outcome) {
            case RefreshOutcome.Renewed renewed -> sessionResponse(renewed.session(), null);
            case RefreshOutcome.Refused refused -> {
                if (refused.reason() == RefreshOutcome.Reason.REUSED) {
                    metrics.refreshReused();
                }
                Map<String, String> headers = refused.reason() == RefreshOutcome.Reason.MISSING
                        ? Map.of()
                        : Map.of(HttpHeaders.SET_COOKIE, AdminRefreshCookie.clear());
                throw new ApiException(
                        ErrorCode.UNAUTHENTICATED,
                        "The administrator session can no longer be renewed; sign in again.",
                        List.of(),
                        null,
                        headers);
            }
        };
    }

    @Operation(
            operationId = "adminLogout",
            summary = "Encerra a sessão do administrador e apaga o cookie; repetir não tem efeito",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "204", description = "Sessão encerrada")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE para token de convidado",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        access.requireAdministrator(request);
        AuthenticatedSession session = authContext.session().orElseThrow(UnauthenticatedException::new);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        IdempotencyRequest idempotent = new IdempotencyRequest(
                IdempotentOperation.ADMIN_AUTH_LOGOUT,
                IdempotencyScope.authenticated(session.principal(), IdempotentOperation.ADMIN_AUTH_LOGOUT),
                key,
                RequestDigest.ofJson(new byte[0]));
        transactions.executeWithoutResult(status -> idempotency.execute(idempotent, () -> {
            admins.logout(session, CorrelationId.current());
            return IdempotentOutcome.of(IdempotentResponse.fresh(204, ""));
        }));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AdminRefreshCookie.clear())
                .build();
    }

    private ResponseEntity<AdminSession> sessionResponse(OpenedSession session, String maskedWallet) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        AdminRefreshCookie.issue(session.refreshToken(), session.refreshExpiresAt(), clock.instant()))
                .body(new AdminSession(
                        session.accessToken(),
                        session.accessTokenExpiresAt(),
                        session.sessionExpiresAt(),
                        session.role().name(),
                        maskedWallet));
    }

    /** Q4: both limits; a refusal is recorded (LOGIN_REJECTED, RATE_LIMITED) before the 429. */
    private void limit(HttpServletRequest request, String wallet) {
        Optional<Duration> wait = limiter.acquire(ips.resolve(request), wallet);
        if (wait.isEmpty()) {
            return;
        }
        admins.recordRateLimited(wallet, CorrelationId.current());
        metrics.adminRefused(AdminRejection.RATE_LIMITED.name());
        long seconds = Math.max(1, (long) Math.ceil(wait.get().toMillis() / 1000.0));
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many attempts; try again later.",
                List.of(),
                null,
                Map.of("Retry-After", Long.toString(seconds)));
    }

    /** One generic answer per family of refusal; the reason already went to the committed LOGIN_REJECTED. */
    static ApiException rejection(AdminRejection reason) {
        if (reason.forbidden()) {
            return new ApiException(ErrorCode.FORBIDDEN_ROLE, "This wallet cannot use the administrator entry.")
                    .alreadyAudited();
        }
        return new ApiException(ErrorCode.UNAUTHENTICATED, "The challenge was refused; request a new one.")
                .alreadyAudited();
    }

    private String digest(Object body) {
        try {
            return RequestDigest.ofJson(json.writeValueAsBytes(body));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Request could not be serialised for its digest", impossible);
        }
    }
}
