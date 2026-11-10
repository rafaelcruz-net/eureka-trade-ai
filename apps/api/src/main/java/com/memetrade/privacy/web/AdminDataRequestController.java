package com.memetrade.privacy.web;

import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.request.DataRequestService;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.privacy.domain.request.IdentityVerification;
import com.memetrade.privacy.domain.request.IdentityVerificationService;
import com.memetrade.privacy.infra.PrivacyProperties;
import com.memetrade.privacy.web.AdminDataRequestDtos.AdminDataRequest;
import com.memetrade.privacy.web.AdminDataRequestDtos.AdminDataRequestPage;
import com.memetrade.privacy.web.AdminDataRequestDtos.ConfirmVerification;
import com.memetrade.privacy.web.AdminDataRequestDtos.DataRequestTransition;
import com.memetrade.privacy.web.AdminDataRequestDtos.RegisterEmailDataRequest;
import com.memetrade.privacy.web.AdminDataRequestDtos.VerificationView;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.KeysetCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 without the anonymisation of u5-anonymization: the administrator handles the data subject requests (F2, F3;
 * BR4.5 to BR4.12, BR4.16, BR4.17; US5.3 to US5.6, US5.9). Every route is under {@code /api/v1/admin/privacy/**}: the
 * route rule of the identity module refuses any other role, and each operation checks the administrator again
 * (defence in depth, BR4.7). The log carries the request id, the states and the outcome only, never the text, the
 * contact, the response, the reason, the message or the signature (BR4.3).
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class AdminDataRequestController {

    static final String CURSOR_SCOPE = "admin-privacy-requests";

    private static final Logger log = LoggerFactory.getLogger(AdminDataRequestController.class);

    private final AdminAccess admins;
    private final com.memetrade.identity.api.AccountAnonymizationPort people;
    private final DataRequestService requests;
    private final IdentityVerificationService verifications;
    private final Clock clock;
    private final Duration dueSoon;
    private final int pageSize;
    private final int pageMax;

    public AdminDataRequestController(
            AdminAccess admins,
            DataRequestService requests,
            IdentityVerificationService verifications,
            Clock clock,
            PrivacyProperties properties,
            com.memetrade.identity.api.AccountAnonymizationPort people) {
        this.admins = admins;
        this.people = people;
        this.requests = requests;
        this.verifications = verifications;
        this.clock = clock;
        this.dueSoon = properties.dueSoon();
        this.pageSize = properties.adminPageSize();
        this.pageMax = properties.adminPageMax();
    }

    @Operation(
            operationId = "adminListDataRequests",
            summary = "Pedidos de titular por prazo, com filtro de situação e cursor (FR7.1, NFR3.2)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = "status",
            in = ParameterIn.QUERY,
            schema =
                    @Schema(
                            type = "string",
                            allowableValues = {"RECEIVED", "AWAITING_VERIFICATION", "IN_PROGRESS", "DONE", "REFUSED"}))
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
    @ApiResponse(responseCode = "200", description = "Uma página, do prazo mais próximo para o mais distante")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em status, limit ou cursor",
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
    @GetMapping("/api/v1/admin/privacy/requests")
    public ResponseEntity<AdminDataRequestPage> list(
            @Parameter(hidden = true) @RequestParam(name = "status", required = false) String status,
            @Parameter(hidden = true) @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(name = "limit", required = false) String limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        Optional<DataRequestStatus> filter = status(status);
        int size = size(limit);
        String scope = CURSOR_SCOPE + ":" + filter.map(Enum::name).orElse("ALL");
        Optional<DataRequestStore.PageKey> after = Optional.ofNullable(cursor)
                .map(text -> KeysetCursor.decode(text, scope))
                .map(position -> new DataRequestStore.PageKey(position.at(), position.id()));
        List<DataSubjectRequest> rows = requests.page(filter, after, size + 1);
        List<DataSubjectRequest> items = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size
                ? new KeysetCursor(items.getLast().dueAt(), items.getLast().requestId()).encode(scope)
                : null;
        Instant now = clock.instant();
        // u5-anonymization: one read of the anonymized accounts of the page (J1), never the wallet.
        java.util.Set<UUID> anonymized = people.anonymizedAmong(
                items.stream().flatMap(item -> item.userId().stream()).collect(java.util.stream.Collectors.toSet()));
        List<AdminDataRequest> view = items.stream()
                .map(item -> AdminDataRequest.listItem(
                        item,
                        now,
                        dueSoon,
                        item.userId().map(anonymized::contains).orElse(false)))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AdminDataRequestPage(view, next));
    }

    @Operation(
            operationId = "adminRegisterEmailRequest",
            summary = "Registra pedido recebido pelo e-mail do encarregado (FR5.2c)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "201", description = "Registrado como EMAIL e RECEIVED, com prazo de 15 dias")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em type, details, contact, receivedAt (futuro), userId (conta inexistente)"
                    + " ou campo desconhecido; o erro nomeia o campo, nunca o valor",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "DATA_REQUEST_ALREADY_OPEN (a conta ligada já tem pedido aberto do mesmo tipo; aditivo, U4) ou"
                            + " IDEMPOTENCY_CONFLICT",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests")
    public ResponseEntity<AdminDataRequest> register(
            @RequestBody RegisterEmailDataRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        DataSubjectRequest registered = requests.registerEmail(new DataRequestService.EmailRequest(
                admin, body.type(), body.details(), body.contact(), body.receivedAt(), body.userId(), key));
        log.info(
                "Data request registered from e-mail: requestId={}, type={}",
                registered.requestId(),
                registered.type());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(detail(registered.requestId()));
    }

    @Operation(
            operationId = "adminGetDataRequest",
            summary = "Um pedido com o texto, a verificação e, para Acesso aberto, a prévia do resumo",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "O pedido")
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/admin/privacy/requests/{requestId}")
    public ResponseEntity<AdminDataRequest> get(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(detail(DataRequestController.parse(requestId)));
    }

    @Operation(
            operationId = "adminTransitionDataRequest",
            summary = "Muda a situação do pedido. RECEIVED para IN_PROGRESS é recusado num pedido EMAIL de ACCESS ou"
                    + " DELETION ligado a conta ativa: ele passa antes pela verificação de identidade",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "O pedido depois da transição")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED (destino fora do enum, resposta ou motivo longos demais)",
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
            description = "INVALID_TRANSITION (inclui AWAITING_VERIFICATION para IN_PROGRESS, que só a confirmação faz,"
                    + " e verificação de identidade primeiro) | DATA_REQUEST_CLOSED | IDENTITY_NOT_VERIFIED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "UNPROCESSABLE_FIELDS (recusa sem motivo; liberar resumo fora de um Acesso)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests/{requestId}/transitions")
    public ResponseEntity<AdminDataRequest> transition(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @RequestBody DataRequestTransition body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        DataSubjectRequest changed = requests.transition(new DataRequestService.Transition(
                admin,
                DataRequestController.parse(requestId),
                body.to(),
                body.response(),
                body.refusalReason(),
                Boolean.TRUE.equals(body.releaseAccessSummary()),
                key));
        log.info("Data request transitioned: requestId={}, to={}", changed.requestId(), changed.status());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail(changed.requestId()));
    }

    @Operation(
            operationId = "adminStartVerification",
            summary =
                    "Gera a mensagem de verificação e leva o pedido a AWAITING_VERIFICATION (só origem EMAIL com conta)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "A verificação, com a mensagem a enviar ao contato")
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "INVALID_TRANSITION (origem Conta, sem conta ligada, conta anonimizada, tipo sem verificação"
                    + " ou pedido fora de RECEIVED) | DATA_REQUEST_CLOSED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests/{requestId}/verification")
    public ResponseEntity<VerificationView> startVerification(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        IdentityVerification started = verifications.start(admin, DataRequestController.parse(requestId), key);
        log.info("Data request verification started: requestId={}", started.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(VerificationView.of(started));
    }

    @Operation(
            operationId = "adminConfirmVerification",
            summary = "Confere a assinatura da carteira da conta sobre a mensagem; a assinatura nunca é guardada",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "Identidade confirmada; o pedido está IN_PROGRESS")
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "VERIFICATION_EXPIRED | VERIFICATION_ALREADY_USED | DATA_REQUEST_CLOSED (antes de qualquer"
                    + " erro de verificação) | INVALID_TRANSITION (sem verificação iniciada)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "VERIFICATION_INVALID (o pedido continua aguardando)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests/{requestId}/verification/confirm")
    public ResponseEntity<AdminDataRequest> confirmVerification(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @RequestBody ConfirmVerification body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        if (body.signature() == null || body.signature().isBlank()) {
            throw ApiException.validation("signature", "Is required.");
        }
        DataSubjectRequest confirmed =
                verifications.confirm(admin, DataRequestController.parse(requestId), body.signature(), key);
        log.info("Data request verification confirmed: requestId={}", confirmed.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail(confirmed.requestId()));
    }

    @Operation(
            operationId = "adminMarkReplySent",
            summary = "Registra a data do envio da resposta ao contato de um pedido por e-mail (só EMAIL encerrado)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "O pedido com replySentAt; o primeiro registro é mantido")
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "INVALID_TRANSITION (origem ACCOUNT ou pedido ainda aberto)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/admin/privacy/requests/{requestId}/reply-sent")
    public ResponseEntity<AdminDataRequest> markReplySent(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        DataSubjectRequest marked = requests.markReplySent(admin, DataRequestController.parse(requestId), key);
        log.info("Data request reply marked sent: requestId={}", marked.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail(marked.requestId()));
    }

    private AdminDataRequest detail(UUID requestId) {
        DataRequestService.Detail found = requests.detail(requestId);
        boolean anonymized = found.request()
                .userId()
                .map(user -> !people.anonymizedAmong(java.util.Set.of(user)).isEmpty())
                .orElse(false);
        return AdminDataRequest.of(found, clock.instant(), dueSoon, anonymized);
    }

    private static Optional<DataRequestStatus> status(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(DataRequestStatus.parse(raw.trim())
                .orElseThrow(() -> ApiException.validation(
                        "status", "Must be one of RECEIVED, AWAITING_VERIFICATION, IN_PROGRESS, DONE, REFUSED.")));
    }

    private int size(String raw) {
        if (raw == null) {
            return pageSize;
        }
        int value = raw.trim().matches("\\d{1,3}") ? Integer.parseInt(raw.trim()) : -1;
        if (value < 1 || value > pageMax) {
            throw ApiException.validation("limit", "Must be an integer from 1 to " + pageMax + ".");
        }
        return value;
    }
}
