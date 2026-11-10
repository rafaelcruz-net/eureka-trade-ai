package com.memetrade.privacy.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.request.DataRequestService;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.privacy.web.DataRequestDtos.CreateDataRequest;
import com.memetrade.privacy.web.DataRequestDtos.DataRequestView;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1: the data subject requests of the guest (F1; BR4.1 to BR4.4; US5.1, US5.2). Tenant and person come only from the
 * access token; a request of another person or tenant is the same {@code 404} as an unknown one. The prefix
 * {@code /api/v1/privacy/} stays reachable during the reacceptance lock (BR4.20). An administrator token is refused by
 * the route rule of the identity module. The log carries the request id, the type and the outcome only, never the text
 * (BR4.3). Answers are never cached.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class DataRequestController {

    private static final Logger log = LoggerFactory.getLogger(DataRequestController.class);

    private final DataRequestService service;
    private final AuthContext auth;

    public DataRequestController(DataRequestService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    @Operation(
            operationId = "listMyDataRequests",
            summary = "Pedidos de titular da pessoa, do mais novo para o mais antigo (FR5.2); pessoa e tenant do token",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(
            responseCode = "200",
            description = "A lista, possivelmente vazia",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DataRequestView.class))))
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
    @GetMapping("/api/v1/privacy/requests")
    public ResponseEntity<List<DataRequestView>> list() {
        Principal person = auth.current();
        List<DataRequestView> views = service.listMine(person).stream()
                .map(DataRequestView::forPerson)
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views);
    }

    @Operation(
            operationId = "createDataRequest",
            summary = "Registra um pedido de titular (FR5.1); recebido com prazo de 15 dias",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(
            responseCode = "201",
            description = "Registrado; a mesma chave com o mesmo corpo devolve o mesmo pedido")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em type, details, campo desconhecido ou Idempotency-Key ausente",
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
            description = "DATA_REQUEST_ALREADY_OPEN (já há pedido aberto do mesmo tipo) ou IDEMPOTENCY_CONFLICT",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description =
                    "RATE_LIMITED: 10 pedidos na última hora; Retry-After diz quando tentar de novo (aditivo, U4)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/privacy/requests")
    public ResponseEntity<DataRequestView> create(
            @RequestBody CreateDataRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        DataRequestService.Created created =
                service.create(new DataRequestService.NewRequest(person, body.type(), body.details(), key));
        DataSubjectRequest saved = created.request();
        log.info(
                "Data request created: requestId={}, type={}, outcome={}",
                saved.requestId(),
                saved.type(),
                created.replayed() ? "REPLAYED" : "CREATED");
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(DataRequestView.forPerson(saved));
    }

    @Operation(
            operationId = "getMyDataRequest",
            summary = "Um pedido da pessoa; resposta, motivo e resumo só depois de concluído ou recusado",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "O pedido")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND, inclusive pedido de outra pessoa ou tenant",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/privacy/requests/{requestId}")
    public ResponseEntity<DataRequestView> get(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId")
                    String requestId) {
        Principal person = auth.current();
        DataSubjectRequest request = service.getMine(person, parse(requestId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DataRequestView.forPerson(request));
    }

    /** A malformed id is the same 404 as an unknown one: nothing tells it apart (BR4.4). */
    static UUID parse(String requestId) {
        try {
            UUID parsed = UUID.fromString(requestId);
            if (!parsed.toString().equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException("not canonical");
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Data request not found.");
        }
    }
}
