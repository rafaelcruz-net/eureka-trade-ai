package com.memetrade.riskcontrol.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.domain.RejectionQueries;
import com.memetrade.riskcontrol.domain.RejectionRepository.PageKey;
import com.memetrade.riskcontrol.domain.RejectionRepository.RejectionPage;
import com.memetrade.riskcontrol.web.RejectionDtos.OrderRejectionDto;
import com.memetrade.riskcontrol.web.RejectionDtos.RejectionPageDto;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.KeysetCursor;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.QueryParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C21 (F3, BR4.1, BR4.2; NFR5.1, NFR6.2 to NFR6.4): the refused orders of a bot of the caller. Only GET. Tenant,
 * person and PAPER come from the token; a {@code tenantId} or any other undeclared parameter is {@code 400}. A bot
 * without an evaluation of the caller is {@code 404 NOT_FOUND}, the same for every reason. No rate limit of its own
 * (NFR6.4): authentication and pages of at most 100 items.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class RejectionController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final RejectionQueries queries;
    private final AuthContext auth;

    public RejectionController(RejectionQueries queries, AuthContext auth) {
        this.queries = queries;
        this.auth = auth;
    }

    @Operation(
            operationId = "listBotRejections",
            summary = "Ordens recusadas com o limite que barrou e o horário (AC4.10.4)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página das recusas, vazia quando nenhuma"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: nenhuma avaliação desse bot para a pessoa do token",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}/rejections")
    public RejectionPageDto rejections(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        QueryParameters query = QueryParameters.of(parameters, Set.of("cursor", "limit"));
        int size = query.integer("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        query.validate();
        String scope = "rejections:" + bot;
        Optional<PageKey> after = query.text("cursor")
                .map(text -> KeysetCursor.decode(text, scope))
                .map(c -> new PageKey(c.at(), c.id()));
        RejectionPage page = queries.page(person, bot, after, size)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bot not found."));
        return new RejectionPageDto(
                page.items().stream().map(OrderRejectionDto::of).toList(),
                page.next()
                        .map(k -> new KeysetCursor(k.evaluatedAt(), k.rejectionId()).encode(scope))
                        .orElse(null),
                page.total(),
                page.totalCapped());
    }
}
