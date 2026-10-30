package com.memetrade.audit.web;

import com.memetrade.audit.domain.AuditEventPage;
import com.memetrade.audit.domain.AuditEventQuery;
import com.memetrade.audit.domain.AuditQueryService;
import com.memetrade.audit.web.AuditTrailDtos.AuditEventPageDto;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/audit-events} (C8 operation provided by the AuditTrail, US7.3): read-only, no screen.
 * Authentication answers {@code 401}; the administrative route rule answers {@code 403 FORBIDDEN_ROLE} for any role
 * other than administrator (audited), and the operation checks the role again through {@link AdminAccess}. Parameters are validated strictly (BR2.2) and every answered query records
 * {@code AUDIT_QUERIED} in the same transaction (BR2.5). Responses are never cached.
 */
@RestController
public class AdminAuditController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditController.class);

    private final AuditQueryService queries;
    private final AdminAccess admins;

    public AdminAuditController(AuditQueryService queries, AdminAccess admins) {
        this.queries = queries;
        this.admins = admins;
    }

    @Operation(
            operationId = "listAuditEvents",
            summary = "Eventos da trilha por período e ação, mais recentes primeiro (US7.3, NFR7)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = "from",
            in = ParameterIn.QUERY,
            required = true,
            description = "Início inclusivo (UTC)",
            schema = @Schema(type = "string", format = "date-time"))
    @Parameter(
            name = "to",
            in = ParameterIn.QUERY,
            required = true,
            description = "Fim exclusivo (UTC); no máximo 31 dias depois de from",
            schema = @Schema(type = "string", format = "date-time"))
    @Parameter(
            name = "action",
            in = ParameterIn.QUERY,
            description = "Igualdade exata; maiúsculas com sublinhado",
            schema = @Schema(type = "string", maxLength = 64, pattern = "^[A-Z][A-Z0-9_]{0,63}$"))
    @Parameter(
            name = "cursor",
            in = ParameterIn.QUERY,
            description = "Cursor opaco devolvido em nextCursor",
            schema = @Schema(type = "string"))
    @Parameter(
            name = "limit",
            in = ParameterIn.QUERY,
            description = "Itens por página",
            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
    @ApiResponse(responseCode = "200", description = "Página com items e nextCursor; Cache-Control no-store")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED com a lista de campos",
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
            responseCode = "500",
            description = "INTERNAL_ERROR sem dados quando AUDIT_QUERIED não pôde ser gravado",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/admin/audit-events")
    public ResponseEntity<AuditEventPageDto> list(
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters,
            @Parameter(hidden = true) HttpServletRequest request) {
        // Defence in depth: the operation refuses any role other than ADMIN itself, even if a spelling of the path
        // ever slipped past the administrative route rule (review finding "admin route bypass").
        Principal administrator = admins.requireAdministrator(request);
        AuditEventQuery query = AuditEventQuery.fromParameters(parameters);
        AuditEventPage page = queries.query(query, administrator.userId(), CorrelationId.current());
        log.info(
                "Audit trail query answered: route={}, status=200, items={}",
                AuditQueryService.ROUTE,
                page.items().size());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuditEventPageDto.of(page));
    }
}
