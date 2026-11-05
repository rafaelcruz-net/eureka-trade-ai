package com.memetrade.identity.web;

import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.account.AccountQueryService;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.web.AccountDtos.AccountResponse;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/account} (C3, F8, US1.5). No identification parameter: person, tenant and session come from the
 * access token only (NFR5.1). Answers are never cached; REAL mode is always {@code UNAVAILABLE} (NFR6.12).
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AccountController {

    private final AccountQueryService accounts;
    private final RequestAuthContext authContext;

    public AccountController(AccountQueryService accounts, RequestAuthContext authContext) {
        this.accounts = accounts;
        this.authContext = authContext;
    }

    @Operation(
            operationId = "getAccount",
            summary = "Dados da conta: carteira abreviada, provedor, fim da sessão e termos aceitos (US1.5)",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "Conta da própria pessoa; Cache-Control no-store")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED; o app tenta renovar e depois leva à entrada com a tela de origem",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/account")
    public ResponseEntity<AccountResponse> account() {
        AuthenticatedSession session = authContext.session().orElseThrow(UnauthenticatedException::new);
        AccountResponse body = accounts.view(
                        session.principal().userId(), session.principal().tenantId(), session.sessionId())
                .map(AccountResponse::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Account not found."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
