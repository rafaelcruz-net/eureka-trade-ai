package com.memetrade.identity.web;

import com.memetrade.identity.web.LegalDocumentsView.Prepared;
import com.memetrade.identity.web.LegalDtos.LegalDocumentsResponse;
import com.memetrade.identity.web.LegalDtos.LegalVersionRef;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * L1 (contract-summary.md): the public legal documents, read only, without a session (BR1.7). The bodies were built at
 * start-up ({@link LegalDocumentsView}). A 200 and a 304 carry {@code Cache-Control: public, max-age=300} and the
 * {@code ETag}, set BEFORE {@code checkNotModified}; every other answer (404, 429, errors) keeps the {@code no-store}
 * of the security headers filter (NFR3.4). The per-IP limit runs before, in {@link LegalDocumentsRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/v1/legal")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LegalDocumentsController {

    static final String CACHE_CONTROL = "public, max-age=300";

    private final LegalDocumentsView view;

    public LegalDocumentsController(LegalDocumentsView view) {
        this.view = view;
    }

    @Operation(
            operationId = "getCurrentLegalDocuments",
            summary = "Versão vigente dos termos, da política e do aviso de risco, sem sessão (FR1.1, FR2.1)")
    @ApiResponse(
            responseCode = "200",
            description = "Versão vigente efetiva; Cache-Control public, max-age=300 e ETag por hash do conteúdo",
            headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "304",
            description = "Não modificado (If-None-Match igual ao ETag), sem corpo",
            content = @Content)
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: mais de 60 requisições por minuto deste IP",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/current")
    public LegalDocumentsResponse current(
            @Parameter(hidden = true) ServletWebRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {
        return answer(view.current(), request, response);
    }

    @Operation(
            operationId = "listLegalVersions",
            summary = "Histórico das versões publicadas, mais nova primeiro (FR8.1)")
    @ApiResponse(
            responseCode = "200",
            description = "Vigente e anteriores; nunca versões futuras ou ignoradas na ativação",
            headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "304",
            description = "Não modificado (If-None-Match igual ao ETag), sem corpo",
            content = @Content)
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: mais de 60 requisições por minuto deste IP",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/versions")
    public List<LegalVersionRef> versions(
            @Parameter(hidden = true) ServletWebRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {
        return answer(view.history(), request, response);
    }

    @Operation(operationId = "getLegalVersion", summary = "Uma versão do histórico (FR8.1, BR1.10)")
    @ApiResponse(
            responseCode = "200",
            description = "A versão pedida, se estiver no histórico",
            headers = @Header(name = "ETag", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "304",
            description = "Não modificado (If-None-Match igual ao ETag), sem corpo",
            content = @Content)
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: versão fora do histórico (futura, ignorada ou desconhecida)",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "RATE_LIMITED: mais de 60 requisições por minuto deste IP",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/versions/{version}")
    public LegalDocumentsResponse version(
            @Parameter(description = "Identificador da versão, por exemplo v2") @PathVariable("version") String version,
            @Parameter(hidden = true) ServletWebRequest request,
            @Parameter(hidden = true) HttpServletResponse response) {
        Prepared<LegalDocumentsResponse> prepared = view.version(version)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Legal version not found."));
        return answer(prepared, request, response);
    }

    /** Cache headers first, then the conditional check: a 304 carries them too and has no body. */
    private static <T> T answer(Prepared<T> prepared, ServletWebRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        response.setHeader(HttpHeaders.ETAG, prepared.etag());
        if (request.checkNotModified(prepared.etag())) {
            return null;
        }
        return prepared.body();
    }
}
