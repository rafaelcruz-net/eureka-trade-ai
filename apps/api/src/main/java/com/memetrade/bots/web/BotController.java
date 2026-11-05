package com.memetrade.bots.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.application.BotQueries;
import com.memetrade.bots.application.BotSummaryView;
import com.memetrade.bots.application.DraftInput;
import com.memetrade.bots.domain.StrategyTemplates;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.QueryParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of C4 (U5): templates, the bots of the person, the wizard, the lifecycle commands, the limits and the
 * decisions. Tenant and person come only from the token (BR2.10): another person's bot is 404. Every state change
 * takes an {@code Idempotency-Key} through the common mechanism (the filter of U1 already refused a missing one) and
 * answers the confirmed state of the server. No route transfers balance nor changes the mode: a {@code mode} other
 * than PAPER is refused with 403 by the common filter (BR2.9).
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class BotController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    private static final Set<String> COMMANDS = Set.of("start", "pause", "resume", "stop");

    private final BotCommandService commands;
    private final BotQueries queries;
    private final AuthContext auth;
    private final ObjectMapper json;
    private final Clock clock;

    public BotController(
            BotCommandService commands, BotQueries queries, AuthContext auth, ObjectMapper json, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.auth = auth;
        this.json = json;
        this.clock = clock;
    }

    @Operation(
            operationId = "listStrategyTemplates",
            summary = "Estratégias modelo com parâmetros, padrões e faixas (FR7.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/api/v1/strategy-templates")
    public List<BotDtos.TemplateDto> templates() {
        auth.current();
        return StrategyTemplates.ALL.stream().map(BotDtos.TemplateDto::of).toList();
    }

    @Operation(
            operationId = "listBots",
            summary = "Bots da pessoa por estado, inclusive rascunhos, com o contador de ativos (FR7.7)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página dos bots"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots")
    public BotDtos.BotPageDto list(
            @Parameter(schema = @Schema(implementation = BotState.class)) @RequestParam(required = false) String state,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("state", "cursor", "limit"));
        int size = query.integer("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        Optional<BotState> filter = query.text("state").flatMap(text -> parseState(text, query));
        query.validate();
        BotQueries.BotList page = queries.list(person, filter, query.text("cursor"), size);
        return new BotDtos.BotPageDto(
                page.page().items().stream()
                        .map(bot -> BotSummaryView.of(bot, clock.instant()))
                        .toList(),
                page.page().next().orElse(null),
                page.activeCount(),
                com.memetrade.bots.domain.BotStateMachine.ACTIVE_LIMIT);
    }

    @Operation(
            operationId = "createBot",
            summary = "Cria o rascunho (DRAFT) a partir de uma estratégia modelo (BR1.1)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "201", description = "Rascunho criado"),
                @ApiResponse(
                        responseCode = "422",
                        description = "VALIDATION_FAILED",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PostMapping("/api/v1/bots")
    public ResponseEntity<BotSummaryView> create(
            @RequestBody BotDtos.CreateBotRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        return answer(commands.create(person, body.templateId(), body.name(), key(request)));
    }

    @Operation(
            operationId = "getBot",
            summary = "Um bot da pessoa com o rascunho e os limites vigentes (AC4.4.1, BR6.2)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "O bot"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}")
    public BotDtos.BotDetailDto get(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId) {
        Principal person = auth.current();
        return BotDtos.BotDetailDto.of(
                queries.get(person, QueryParameters.requireUuid("botId", botId)), clock.instant());
    }

    @Operation(
            operationId = "saveBotDraft",
            summary = "Salva a etapa do assistente (AC4.1.3)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "200", description = "Etapa salva"),
                @ApiResponse(
                        responseCode = "422",
                        description = "LIMIT_INCOHERENT, VIRTUAL_BALANCE_ABOVE_CAP ou VALIDATION_FAILED",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PutMapping("/api/v1/bots/{botId}/draft")
    public ResponseEntity<BotSummaryView> saveDraft(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @RequestBody BotDtos.DraftRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        if (body.step() == null || body.step() < 1 || body.step() > 4) {
            throw ApiException.validation("step", "A etapa vai de 1 a 4.");
        }
        DraftInput input = new DraftInput(
                body.step(),
                body.parameters(),
                body.tokenSelection() == null ? null : body.tokenSelection().raw(),
                body.limits() == null ? null : body.limits().raw(),
                body.virtualBalance());
        return answer(commands.saveDraft(person, bot, input, key(request)));
    }

    @Operation(
            operationId = "runBotCommand",
            summary = "Comandos de ciclo de vida: start, pause, resume e stop (BR2.2 a BR2.7)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "200", description = "Estado confirmado"),
                @ApiResponse(
                        responseCode = "409",
                        description = "INVALID_TRANSITION, ACTIVE_BOT_LIMIT, PAUSED_BY_SYSTEM ou"
                                + " DAILY_LOSS_LIMIT_REACHED",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = "SERVICE_UNAVAILABLE: tentar de novo",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PostMapping("/api/v1/bots/{botId}/commands/{command}")
    public ResponseEntity<BotSummaryView> command(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"start", "pause", "resume", "stop"}))
                    @PathVariable("command")
                    String command,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        String name = command.toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(name)) {
            throw ApiException.validation("command", "Use start, pause, resume ou stop.");
        }
        IdempotencyKey key = key(request);
        IdempotentResponse response =
                switch (name) {
                    case "start" -> commands.start(person, bot, key);
                    case "pause" -> commands.pause(person, bot, key);
                    case "resume" -> commands.resume(person, bot, key);
                    default -> commands.stop(person, bot, key);
                };
        return answer(response);
    }

    @Operation(
            operationId = "changeBotLimits",
            summary = "Nova versão dos limites, válida só para operações futuras (US4.9)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "201", description = "Nova versão gravada"),
                @ApiResponse(
                        responseCode = "422",
                        description = "LIMIT_INCOHERENT",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PostMapping("/api/v1/bots/{botId}/limits")
    public ResponseEntity<BotSummaryView> limits(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @RequestBody BotDtos.LimitsDto body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        return answer(commands.changeLimits(person, bot, body.raw(), key(request)));
    }

    @Operation(
            operationId = "listBotDecisions",
            summary = "Decisões explicadas do bot, mais novas primeiro (US4.5)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página das decisões"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}/decisions")
    public BotDtos.DecisionPageDto decisions(
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
        var page = queries.decisions(person, bot, query.text("cursor"), size);
        return new BotDtos.DecisionPageDto(
                page.items().stream().map(BotDtos.DecisionDto::of).toList(),
                page.next().orElse(null));
    }

    // ----- helpers -----

    private static Optional<BotState> parseState(String text, QueryParameters query) {
        try {
            return Optional.of(BotState.valueOf(text));
        } catch (IllegalArgumentException unknown) {
            query.violation("state", "Estado desconhecido.");
            return Optional.empty();
        }
    }

    /** The common Idempotency-Key filter already refused a missing or malformed key with 400. */
    private static IdempotencyKey key(HttpServletRequest request) {
        return IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
    }

    private ResponseEntity<BotSummaryView> answer(IdempotentResponse response) {
        try {
            return ResponseEntity.status(response.status())
                    .cacheControl(CacheControl.noStore())
                    .body(json.readValue(response.body(), BotSummaryView.class));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A stored bot answer could not be read", failure);
        }
    }
}
