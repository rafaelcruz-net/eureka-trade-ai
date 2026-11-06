package com.memetrade.administration.web;

import com.memetrade.administration.domain.SystemStatusAssembler;
import com.memetrade.administration.web.SystemStatusDtos.SystemStatusView;
import com.memetrade.administration.web.SystemStatusDtos.TenantOptionView;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System status and tenants of C8 (F5, F6; BR3.1 to BR3.3, BR5.1; AC7.4.1). Counts and the state of the sources only;
 * the tenants are the ACTIVE ones for the invitation form. Both refuse any role other than ADMIN.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class AdminSystemController {

    private final AdminAccess admins;
    private final SystemStatusAssembler assembler;
    private final IdentityAdministration identity;

    public AdminSystemController(AdminAccess admins, SystemStatusAssembler assembler, IdentityAdministration identity) {
        this.admins = admins;
        this.assembler = assembler;
        this.identity = identity;
    }

    @Operation(
            operationId = "getSystemStatus",
            summary = "Só contagens e estado das fontes (AC7.4.1); cada parte é OK com value ou UNKNOWN com reason",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "Estado do sistema; uma parte que falha não derruba as outras")
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
    @GetMapping("/api/v1/admin/system-status")
    public ResponseEntity<SystemStatusView> systemStatus(@Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SystemStatusView.of(assembler.assemble(admin)));
    }

    @Operation(
            operationId = "listActiveTenants",
            summary = "Tenants ativos para o formulário de convite (acréscimo da U8, BR5.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "Tenants ativos, por nome")
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
    @GetMapping("/api/v1/admin/tenants")
    public ResponseEntity<List<TenantOptionView>> tenants(@Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        List<TenantOptionView> tenants = identity.activeTenants(admin).stream()
                .map(option -> new TenantOptionView(option.tenantId(), option.name()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tenants);
    }
}
