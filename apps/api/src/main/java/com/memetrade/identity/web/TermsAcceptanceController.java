package com.memetrade.identity.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.domain.terms.TermsAcceptanceService;
import com.memetrade.identity.web.AccountDtos.TermsStatusDto;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * u3-reacceptance (L2; F2; BR3.4 to BR3.6; NFR2.1): {@code POST /api/v1/terms/acceptances}. The body is only
 * {@code {version}}; person and tenant come from the access token, never from the request. Always reachable during the
 * reacceptance lock (allowed prefix). Answers are never cached.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class TermsAcceptanceController {

    private final TermsAcceptanceService service;
    private final AuthContext auth;

    public TermsAcceptanceController(TermsAcceptanceService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    @Operation(
            operationId = "acceptTerms",
            summary = "Aceita a versão vigente dos termos (FR4.2); pessoa e tenant vêm do token",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(
            responseCode = "200",
            description =
                    "Aceite registrado, ou já existente para a versão (sem nova gravação); a mesma chave devolve a"
                            + " mesma resposta")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: versão ausente, vazia ou com mais de 32 caracteres, campo desconhecido ou"
                    + " Idempotency-Key ausente",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE para token de administrador",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TERMS_VERSION_OUTDATED (o corpo traz a versão vigente em currentVersion) ou"
                    + " IDEMPOTENCY_CONFLICT (mesma chave com outra versão)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/terms/acceptances")
    public ResponseEntity<TermsStatusDto> accept(
            @Valid @RequestBody AcceptTermsRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        TermsAcceptanceService.Result result = service.accept(
                new TermsAcceptanceService.Acceptance(auth.current(), body.version(), key, CorrelationId.current()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TermsStatusDto.of(result.status()));
    }

    /** L2 {@code AcceptTermsCommand}: only the version; a tenant or person in the body is an unknown field (400). */
    @Schema(name = "AcceptTermsRequest")
    public record AcceptTermsRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 32) @NotBlank @Size(max = 32)
            String version) {}
}
