package com.memetrade.feedback.web;

import com.memetrade.feedback.domain.FeedbackService;
import com.memetrade.feedback.web.FeedbackDtos.FeedbackReceipt;
import com.memetrade.feedback.web.FeedbackDtos.FeedbackRequest;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * C6: a guest sends a comment from any screen (F7; BR6.1 to BR6.4; AC6.1.1 to AC6.1.3). Only a guest token reaches
 * this route: an administrator token is refused by the route rule of the identity module (BR1.5). The log carries the
 * screen, the length and the outcome only, never the text (NFR6.7).
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackService service;
    private final AuthContext auth;

    public FeedbackController(FeedbackService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    @Operation(
            operationId = "sendFeedback",
            summary = "Registra um comentário com a tela de origem (FR13.1); tenant e pessoa vêm do token",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "201", description = "Registrado; a mesma chave com o mesmo corpo devolve o mesmo 201")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em text (vazio ou mais de 2.000 caracteres), screen (fora de S1 a S9) ou"
                    + " campo desconhecido; o app preserva o texto (AC6.1.3)",
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
            description = "IDEMPOTENCY_CONFLICT: mesma chave com outro corpo",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: 10 comentários na última hora; Retry-After diz quando tentar de novo (U8)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/api/v1/feedback")
    public ResponseEntity<FeedbackReceipt> send(
            @RequestBody FeedbackRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        FeedbackService.Receipt receipt =
                service.submit(new FeedbackService.Submission(person, body.screen(), body.text(), key));
        log.info(
                "Feedback received: screen={}, length={}, outcome={}",
                body.screen(),
                body.text() == null
                        ? 0
                        : body.text().codePointCount(0, body.text().length()),
                receipt.replayed() ? "REPLAYED" : "CREATED");
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(new FeedbackReceipt(receipt.feedbackId(), receipt.createdAt()));
    }
}
