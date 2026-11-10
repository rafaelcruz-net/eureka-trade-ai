package com.memetrade.privacy.web;

import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.privacy.domain.anonymization.AnonymizationService;
import com.memetrade.privacy.web.AdminDataRequestDtos.AnonymizationView;
import com.memetrade.privacy.web.AdminDataRequestDtos.AnonymizeAccountRequest;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 {@code adminAnonymizeAccount} of u5-anonymization (F1; BR5.1, BR5.5; US6.1; NFR2.1): under
 * {@code /api/v1/admin/privacy/**}, so the route rule of the identity module refuses any other role, and the operation
 * checks the administrator again (defence in depth). The tenant and the person come from the request, never from the
 * body. Nothing is logged here; the attempt is logged after the commit or the rollback with identifiers and counts.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class AdminAnonymizationController {

    private final AdminAccess admins;
    private final AnonymizationService anonymization;

    public AdminAnonymizationController(AdminAccess admins, AnonymizationService anonymization) {
        this.admins = admins;
        this.anonymization = anonymization;
    }

    @Operation(
            operationId = "adminAnonymizeAccount",
            summary = "Anonimiza a conta de um pedido de Exclusão em andamento (FR6); idempotente; a repetição numa"
                    + " conta já anonimizada devolve o mesmo resultado com alreadyAnonymized",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "A conta anonimizada e o pedido DONE, ou a repetição")
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "REQUEST_NOT_ELIGIBLE (não é Exclusão IN_PROGRESS) | NO_LINKED_ACCOUNT |"
                    + " ADMIN_ACCOUNT_NOT_ANONYMIZABLE | IDENTITY_NOT_VERIFIED (origem EMAIL) | IDEMPOTENCY_CONFLICT",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CONFIRMATION_REQUIRED (confirmação diferente de ANONIMIZAR)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "LOCK_TIMEOUT com Retry-After: nada mudou; repetir com a mesma Idempotency-Key",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests/{requestId}/anonymization")
    public ResponseEntity<AnonymizationView> anonymize(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @RequestBody AnonymizeAccountRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        AnonymizationResult result = anonymization.anonymize(new AnonymizationService.Anonymize(
                admin, DataRequestController.parse(requestId), body.confirmation(), key));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AnonymizationView.of(result));
    }
}
