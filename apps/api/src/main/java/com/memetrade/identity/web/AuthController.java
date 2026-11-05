package com.memetrade.identity.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.memetrade.identity.domain.login.ChallengeService;
import com.memetrade.identity.domain.login.LoginVerificationService;
import com.memetrade.identity.domain.login.LoginVerificationService.VerifyCommand;
import com.memetrade.identity.domain.login.RejectionReason;
import com.memetrade.identity.domain.login.VerificationOutcome;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.RefreshOutcome;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.web.AuthDtos.ChallengeRequest;
import com.memetrade.identity.web.AuthDtos.ChallengeResponse;
import com.memetrade.identity.web.AuthDtos.GuestSession;
import com.memetrade.identity.web.AuthDtos.VerifyRequest;
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
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Guest authentication routes of contract C1. */
@RestController
@RequestMapping("/api/v1/auth")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AuthController {

    private final ChallengeService challenges;
    private final LoginVerificationService verification;
    private final SessionService sessions;
    private final RequestAuthContext authContext;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactions;
    private final LoginMetrics metrics;
    private final LoginGate gate;
    private final Clock clock;
    private final ObjectMapper json;

    public AuthController(
            ChallengeService challenges,
            LoginVerificationService verification,
            SessionService sessions,
            RequestAuthContext authContext,
            IdempotencyGuard idempotency,
            TransactionTemplate transactions,
            LoginMetrics metrics,
            LoginGate gate,
            Clock clock,
            ObjectMapper json) {
        this.challenges = challenges;
        this.verification = verification;
        this.sessions = sessions;
        this.authContext = authContext;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.metrics = metrics;
        this.gate = gate;
        this.clock = clock;
        this.json = json;
    }

    @Operation(
            operationId = "refreshSession",
            summary = "Troca o cookie de renovação por um novo token de acesso; sem Idempotency-Key")
    @ApiResponse(responseCode = "200", description = "Novo token de acesso; o cookie de renovação é trocado")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED; o app volta à entrada e depois à tela de origem",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/refresh")
    public ResponseEntity<GuestSession> refresh(
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
        RefreshOutcome outcome = sessions.refresh(refreshToken, CorrelationId.current());
        return switch (outcome) {
            case RefreshOutcome.Renewed renewed -> sessionResponse(renewed.session());
            case RefreshOutcome.Refused refused -> {
                if (refused.reason() == RefreshOutcome.Reason.REUSED) {
                    metrics.refreshReused();
                }
                // Clear the cookie only when one was presented and refused. Without a cookie there is nothing to
                // clear, and a late "clear" answer to the anonymous refresh fired when /entrar loads would delete the
                // cookie that a login completed in the meantime has just set (race found by the e2e screen-time spec).
                Map<String, String> headers = refused.reason() == RefreshOutcome.Reason.MISSING
                        ? Map.of()
                        : Map.of(HttpHeaders.SET_COOKIE, RefreshCookie.clear());
                throw new ApiException(
                        ErrorCode.UNAUTHENTICATED,
                        "The session can no longer be renewed; sign in again.",
                        List.of(),
                        null,
                        headers);
            }
        };
    }

    @Operation(
            operationId = "logout",
            summary = "Encerra a sessão e revoga o token de renovação; bots continuam rodando",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "204", description = "Sessão encerrada")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthenticatedSession session = authContext.session().orElseThrow(UnauthenticatedException::new);
        IdempotencyKey key = (IdempotencyKey) request.getAttribute(IdempotencyKeyFilter.ATTRIBUTE);
        IdempotencyRequest idempotent = new IdempotencyRequest(
                IdempotentOperation.AUTH_LOGOUT,
                IdempotencyScope.authenticated(session.principal(), IdempotentOperation.AUTH_LOGOUT),
                key,
                RequestDigest.ofJson(new byte[0]));
        transactions.executeWithoutResult(status -> idempotency.execute(idempotent, () -> {
            sessions.logout(session, CorrelationId.current());
            return IdempotentOutcome.of(IdempotentResponse.fresh(204, ""));
        }));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.clear())
                .build();
    }

    @Operation(
            operationId = "verifyLogin",
            summary = "Verifica a assinatura e abre a sessão; o cookie de renovação vem em Set-Cookie")
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "Sessão aberta")
    @ApiResponse(
            responseCode = "401",
            description = "LOGIN_CHALLENGE_REJECTED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE quando a carteira é a de administrador",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "INVITE_NOT_FOUND: código do convite desconhecido ou mal formado (U3)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "WALLET_ALREADY_LINKED: a carteira já tem conta; o convite continua pendente (U3)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "INVITE_INVALID com reason EXPIRED, REVOKED, USED ou TENANT_INACTIVE; ou TERMS_NOT_ACCEPTED"
                    + " (U3)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/verify")
    public ResponseEntity<GuestSession> verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        walletOf(body.walletAddress());
        gate.beforeVerification(request, body.walletAddress());
        VerifyCommand command = new VerifyCommand(
                body.walletAddress(),
                body.message(),
                body.signature(),
                body.provider().name(),
                CorrelationId.current(),
                body.inviteCode(),
                body.acceptedTermsVersion());
        VerificationOutcome outcome;
        try {
            outcome = verification.verify(command);
        } catch (WalletAlreadyLinkedException concurrentLink) {
            // BR3.7: the creation was rolled back; a NEW transaction consumes the challenge and records the refusal.
            outcome = verification.rejectAfterRollback(command, RejectionReason.WALLET_ALREADY_LINKED);
        }
        // The transaction has committed here: the challenge state and the audit event exist before the response.
        return switch (outcome) {
            case VerificationOutcome.Accepted accepted -> {
                metrics.accepted();
                yield sessionResponse(accepted.session());
            }
            case VerificationOutcome.Rejected rejected -> {
                metrics.rejected(rejected.reason().name());
                gate.afterRejectedVerification(request, body.walletAddress());
                throw rejection(rejected);
            }
        };
    }

    ResponseEntity<GuestSession> sessionResponse(OpenedSession session) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        RefreshCookie.issue(session.refreshToken(), session.refreshExpiresAt(), clock.instant()))
                .body(new GuestSession(
                        session.accessToken(),
                        session.accessTokenExpiresAt(),
                        session.sessionExpiresAt(),
                        session.role().name()));
    }

    /** The single LOGIN_REJECTED event was already written in the committed transaction (BR1.6, BR3.5). */
    static ApiException rejection(VerificationOutcome.Rejected rejected) {
        ApiException invitation = invitationRejection(rejected.reason());
        if (invitation != null) {
            return invitation.alreadyAudited();
        }
        if (rejected.forbiddenRole()) {
            return new ApiException(
                            ErrorCode.FORBIDDEN_ROLE,
                            "This wallet cannot sign in through the guest entry.",
                            List.of(),
                            rejected.reason().name(),
                            Map.of())
                    .alreadyAudited();
        }
        return new ApiException(
                        ErrorCode.LOGIN_CHALLENGE_REJECTED,
                        "The login challenge was rejected; request a new challenge.",
                        List.of(),
                        rejected.reason().name(),
                        Map.of())
                .alreadyAudited();
    }

    /** U3 refusals of the verification with an invitation (BR3.2); {@code null} for the refusals of U1. */
    private static ApiException invitationRejection(RejectionReason reason) {
        return switch (reason) {
            case INVITE_NOT_FOUND -> new ApiException(ErrorCode.INVITE_NOT_FOUND, "Invitation not found.");
            case INVITE_EXPIRED -> invalidInvitation("EXPIRED");
            case INVITE_REVOKED -> invalidInvitation("REVOKED");
            case INVITE_USED -> invalidInvitation("USED");
            case TENANT_INACTIVE -> invalidInvitation("TENANT_INACTIVE");
            case TERMS_NOT_ACCEPTED ->
                new ApiException(
                        ErrorCode.TERMS_NOT_ACCEPTED,
                        "Accept the current terms and risk notice, then request a new challenge.",
                        List.of(),
                        reason.name(),
                        Map.of());
            case WALLET_ALREADY_LINKED ->
                new ApiException(
                        ErrorCode.WALLET_ALREADY_LINKED,
                        "This wallet already has an account; sign in through the normal entry.",
                        List.of(),
                        reason.name(),
                        Map.of());
            default -> null;
        };
    }

    private static ApiException invalidInvitation(String reason) {
        return new ApiException(
                ErrorCode.INVITE_INVALID, "This invitation can no longer be used.", List.of(), reason, Map.of());
    }

    @Operation(
            operationId = "issueChallenge",
            summary = "Emite mensagem de login com domínio, nonce de uso único, expiração de 5 minutos e chain (FR1.3)")
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PostMapping("/challenge")
    public ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest body, HttpServletRequest request) {
        gate.beforeChallenge(request);
        WalletAddress wallet = walletOf(body.walletAddress());
        IdempotencyKey key = (IdempotencyKey) request.getAttribute(IdempotencyKeyFilter.ATTRIBUTE);
        return ChallengeResponse.from(challenges.issue(wallet, key, digest(body)));
    }

    static WalletAddress walletOf(String address) {
        if (!WalletAddress.isValid(address)) {
            throw ApiException.validation("walletAddress", "Must be a Base58 Solana public address.");
        }
        return WalletAddress.of(address);
    }

    private String digest(Object body) {
        try {
            return RequestDigest.ofJson(json.writeValueAsBytes(body));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Request could not be serialised for its digest", impossible);
        }
    }
}
