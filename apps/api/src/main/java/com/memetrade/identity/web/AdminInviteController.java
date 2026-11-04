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
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.IssuedInvite;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.domain.idempotency.RequestDigest;
import com.memetrade.identity.domain.invite.InviteService;
import com.memetrade.identity.web.AdminInviteDtos.InvitePageResponse;
import com.memetrade.identity.web.AdminInviteDtos.InviteResponse;
import com.memetrade.identity.web.AdminInviteDtos.IssueInviteRequest;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative invitation routes (C8; F1 to F3; BR1.1 to BR1.5, BR1.8), used by the administrator app of U8. The
 * administrative route rule answers {@code 403 FORBIDDEN_ROLE} to any other role (audited) and each operation checks
 * the role again through {@link AdminAccess}. Issuance and revocation run inside ONE transaction with the common
 * idempotency mechanism: the stored answer is the invitation WITHOUT the code, so a replay never shows it (NFR6.3);
 * the code is added to the HTTP answer only when the answer is not a replay.
 */
@RestController
public class AdminInviteController {

    private static final Logger log = LoggerFactory.getLogger(AdminInviteController.class);

    private final IdentityAdministration administration;
    private final AdminAccess admins;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public AdminInviteController(
            IdentityAdministration administration,
            AdminAccess admins,
            IdempotencyGuard idempotency,
            TransactionTemplate transactions,
            ObjectMapper json) {
        this.administration = administration;
        this.admins = admins;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.json = json;
    }

    @Operation(
            operationId = "listInvites",
            summary = "Convites com estado em texto, validade e final do código, mais recentes primeiro (AC7.1.2)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = "cursor",
            in = ParameterIn.QUERY,
            description = "Cursor opaco devolvido em nextCursor",
            schema = @Schema(type = "string"))
    @Parameter(
            name = "limit",
            in = ParameterIn.QUERY,
            description = "Itens por página",
            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
    @ApiResponse(responseCode = "200", description = "Página sem o código e sem quem emitiu")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em limit ou cursor",
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
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/admin/invites")
    public ResponseEntity<InvitePageResponse> list(
            @Parameter(hidden = true) @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        int size = limit == null ? InviteService.DEFAULT_PAGE_SIZE : limit;
        InvitePageResponse page = InvitePageResponse.of(administration.list(Optional.ofNullable(cursor), size, admin));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @Operation(
            operationId = "issueInvite",
            summary = "Emite convite de uso único para um tenant ativo (AC7.1.1); o código só vem nesta resposta",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(
            responseCode = "201",
            description = "Convite emitido com o código completo; repetir com a mesma chave devolve o mesmo convite"
                    + " sem o código")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: Idempotency-Key ausente ou corpo inválido",
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
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT: mesma chave com outro corpo",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "VALIDATION_FAILED nos campos tenantId (tenant inexistente ou não ativo) ou expiresAt (fora de"
                            + " 1 hora a 30 dias)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/invites")
    public ResponseEntity<InviteResponse> issue(
            @Valid @RequestBody IssueInviteRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        AtomicReference<IssuedInvite> fresh = new AtomicReference<>();
        IdempotentResponse stored = transactions.execute(status ->
                idempotency.execute(idempotent(IdempotentOperation.ADMIN_INVITE_ISSUE, admin, request, body), () -> {
                    IssuedInvite issued = administration.issue(body.tenantId(), body.expiresAt(), admin);
                    fresh.set(issued);
                    return IdempotentOutcome.of(
                            IdempotentResponse.fresh(201, write(InviteResponse.of(issued.invite()))));
                }));
        InviteResponse answer;
        if (stored.replayed()) {
            answer = read(stored.body());
        } else if (fresh.get() != null) {
            answer = InviteResponse.withCode(fresh.get());
        } else {
            throw new IllegalStateException("A fresh issuance answered without running the issuance");
        }
        log.info("Invitation issued: route=/api/v1/admin/invites, replayed={}", stored.replayed());
        return ResponseEntity.status(stored.status())
                .cacheControl(CacheControl.noStore())
                .body(answer);
    }

    @Operation(
            operationId = "revokeInvite",
            summary = "Revoga um convite pendente; revogar de novo devolve o mesmo convite (BR1.4)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "Convite revogado (sem o código)")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: Idempotency-Key ausente ou inviteId inválido",
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
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: convite inexistente",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "INVITE_NOT_REVOCABLE com reason USED ou EXPIRED; ou IDEMPOTENCY_CONFLICT",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/invites/{inviteId}/revoke")
    public ResponseEntity<InviteResponse> revoke(
            @PathVariable("inviteId") UUID inviteId, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotentResponse stored = transactions.execute(status -> idempotency.execute(
                idempotent(
                        IdempotentOperation.ADMIN_INVITE_REVOKE,
                        admin,
                        request,
                        java.util.Map.of("inviteId", inviteId)),
                () -> IdempotentOutcome.of(IdempotentResponse.fresh(
                        200, write(InviteResponse.of(administration.revoke(inviteId, admin)))))));
        return ResponseEntity.status(stored.status())
                .cacheControl(CacheControl.noStore())
                .body(read(stored.body()));
    }

    private IdempotencyRequest idempotent(
            IdempotentOperation operation, Principal admin, HttpServletRequest request, Object content) {
        IdempotencyKey key = (IdempotencyKey) request.getAttribute(IdempotencyKeyFilter.ATTRIBUTE);
        if (key == null) {
            throw new IllegalStateException("The Idempotency-Key filter did not run for " + operation);
        }
        return new IdempotencyRequest(
                operation, IdempotencyScope.authenticated(admin, operation), key, digest(content));
    }

    private String digest(Object content) {
        try {
            return RequestDigest.ofJson(json.writeValueAsBytes(content));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Request could not be serialised for its digest", impossible);
        }
    }

    private String write(InviteResponse invite) {
        try {
            return json.writeValueAsString(invite);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Invitation could not be serialised", impossible);
        }
    }

    private InviteResponse read(String body) {
        try {
            return json.readValue(body, InviteResponse.class);
        } catch (JsonProcessingException corrupted) {
            throw new IllegalStateException("Stored invitation answer could not be read", corrupted);
        }
    }
}
