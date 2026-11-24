package com.memetrade.pnl.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealPnlLineCursorRejected;
import com.memetrade.pnl.application.BotLinesCursor;
import com.memetrade.pnl.application.PnlQueryService;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.pnl.application.ReconciliationService.Trigger;
import com.memetrade.pnl.modeview.ModePnlStatement;
import com.memetrade.pnl.modeview.ModePnlView;
import com.memetrade.pnl.web.PnlDtos.PnlReportDto;
import com.memetrade.pnl.web.PnlDtos.ReconciliationResultDto;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.QueryParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes of C5 owned by the P&L (F7, F9, BR6.1 to BR6.6, BR8.1 to BR8.4): the P&L of a period, the latest
 * reconciliation and "check now". Tenant and person come from the token. "Check now" takes an Idempotency-Key through
 * the common mechanism, only reads the ledger, and is limited to 6 runs per person and minute ({@code 429} with
 * {@code Retry-After}); the reads have no limit.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class PnlController {

    private static final Logger log = LoggerFactory.getLogger(PnlController.class);

    private final PnlQueryService pnl;
    private final ReconciliationService reconciliation;
    private final ReconciliationRunLimiter limiter;
    private final IdempotencyGuard idempotency;
    private final AuthContext auth;
    private final ObjectMapper json;
    private final TransactionTemplate repeatableRead;
    private final ModePnlView modeView;
    private final PaperBlockSource paperBlocks;
    private final RealBlockSource realBlocks;
    private final PnlViewsParts views;
    private final TransactionTemplate readOnly;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PnlController(
            PnlQueryService pnl,
            ReconciliationService reconciliation,
            ReconciliationRunLimiter limiter,
            IdempotencyGuard idempotency,
            AuthContext auth,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            ModePnlView modeView,
            PaperBlockSource paperBlocks,
            RealBlockSource realBlocks,
            PnlViewsParts views) {
        this.modeView = modeView;
        this.paperBlocks = paperBlocks;
        this.realBlocks = realBlocks;
        this.views = views;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.pnl = pnl;
        this.reconciliation = reconciliation;
        this.limiter = limiter;
        this.idempotency = idempotency;
        this.auth = auth;
        this.json = json;
        this.repeatableRead = new TransactionTemplate(transactions);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Operation(
            operationId = "getPnl",
            summary = "P&L por token, bot e período [from, to) em UTC (FR10.3)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "P&L do período"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: from >= to, período acima de 366 dias ou campo inválido",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = "SERVICE_UNAVAILABLE: não foi possível calcular agora (BR6.6)",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/pnl")
    public PnlReportDto pnl(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String botId,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String from,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String to,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("botId", "from", "to"));
        Optional<UUID> bot = query.uuid("botId");
        Optional<Instant> start = query.requiredInstant("from");
        Optional<Instant> end = query.requiredInstant("to");
        query.validate();
        return PnlReportDto.of(pnl.report(person, start.orElseThrow(), end.orElseThrow(), bot));
    }

    @Operation(
            operationId = "getPnlByMode",
            summary = "P&L em dois blocos separados, PAPER (US$) e REAL (SOL), no mesmo período; nunca somados (FR4.1,"
                    + " ADR-003)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Os dois blocos; um modo que falhou vem com status UNAVAILABLE sem derrubar o"
                                + " outro"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: RANGE sem from ou to, from >= to, período acima de 366 dias,"
                                + " from ou to sem RANGE, market fora de PAPER e REAL ou parâmetro desconhecido",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/pnl/by-mode")
    public ResponseEntity<ModePnlDtos.PnlByModeDto> byMode(
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"ALL", "RANGE"},
                                            defaultValue = "ALL"))
                    @RequestParam(required = false)
                    String period,
            @Parameter(
                            description = "Obrigatório em RANGE; instante UTC inicial, incluído",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String from,
            @Parameter(
                            description = "Obrigatório em RANGE; instante UTC final, excluído",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    String to,
            @Parameter(
                            description = "261121-paper-real-unico U3 (BR3.1): só o bloco deste modo; ausente, os dois",
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"PAPER", "REAL"}))
                    @RequestParam(required = false)
                    String market,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        // 261121-paper-real-unico U3 (BR2.4, BR3.1, BR8.1): a closed list of parameters, read without any read of the
        // database; a tenantId or accountId is refused, the person of the token is always the one read.
        QueryParameters query = QueryParameters.of(parameters, Set.of("period", "from", "to", "market"));
        Optional<PnlWindow> read = PnlWindows.read(query);
        Optional<PnlMarket> only = query.text("market").flatMap(text -> market(text, query));
        query.validate();
        PnlWindow window = read.orElseThrow();
        ModePnlDtos.PnlPeriodDto periodDto = PnlWindows.dto(window);
        ModePnlDtos.PnlByModeDto body;
        if (only.isEmpty()) {
            ModePnlStatement<ModePnlDtos.PaperModeBlockDto, ModePnlDtos.RealModeBlockDto> statement =
                    modeView.statement(
                            window,
                            () -> paperBlocks.block(person, window),
                            () -> realBlocks.block(person, window),
                            views.modeMetrics::unavailable);
            body = ModePnlDtos.PnlByModeDto.of(
                    periodDto, Optional.of(statement.paper()), Optional.of(statement.real()));
        } else if (only.get() == PnlMarket.PAPER) {
            body = ModePnlDtos.PnlByModeDto.of(
                    periodDto,
                    Optional.of(modeView.single(
                            PnlMarket.PAPER, () -> paperBlocks.block(person, window), views.modeMetrics::unavailable)),
                    Optional.empty());
        } else {
            body = ModePnlDtos.PnlByModeDto.of(
                    periodDto,
                    Optional.empty(),
                    Optional.of(modeView.single(
                            PnlMarket.REAL, () -> realBlocks.block(person, window), views.modeMetrics::unavailable)));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @Operation(
            operationId = "listBotPnlLines",
            summary = "As linhas que formam o P&L de um bot REAL, com a origem de cada uma, da mais recente para a mais"
                    + " antiga, desde o início (FR3.6)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Uma página das linhas"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: cursor ou limit inválido, ou cursor de outro bot",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa, inexistente ou PAPER; o mesmo corpo nos três",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "503",
                        description =
                                "SERVICE_UNAVAILABLE: não foi possível calcular as linhas agora; com correlationId",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}/pnl/lines")
    public ResponseEntity<ModePnlDtos.RealBotPnlLinePageDto> botLines(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        // 1. Validation without the database (NFR3.6): the id, the limit, the form and the bot of the cursor.
        UUID bot = QueryParameters.requireUuid("botId", botId);
        QueryParameters query = QueryParameters.of(parameters, Set.of("cursor", "limit"));
        int size = query.integer("limit", LINES_PAGE, 1, LINES_MAX);
        Optional<String> after = query.text("cursor");
        after.ifPresent(text -> {
            try {
                BotLinesCursor.parse(text, bot);
            } catch (BotLinesCursor.Malformed malformed) {
                query.violation("cursor", "The cursor is not valid for this bot.");
            }
        });
        query.validate();
        // 2. The ownership, before any task: a bot of somebody else, unknown or PAPER is the same 404.
        Optional<BotEntry> entry = readOnly.execute(status -> views.directory
                .botOf(person.tenantId(), person.userId(), bot)
                .filter(found -> found.market() == PnlMarket.REAL));
        if (entry == null || entry.isEmpty()) {
            throw refuseNotFound(person, ReadRoute.LINES, ReadSide.NONE, bot);
        }
        // 3. One REAL task in the pool of the blocks, under the deadline of a block.
        ModePnlView.Attempt<Optional<ModePnlDtos.RealBotPnlLinePageDto>> outcome =
                modeView.attempt(PnlMarket.REAL, () -> realBlocks.lines(person, bot, after, size));
        return switch (outcome) {
            case ModePnlView.Attempt.Done<Optional<ModePnlDtos.RealBotPnlLinePageDto>> done ->
                done.value()
                        .map(page -> ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore())
                                .body(page))
                        .orElseThrow(() -> refuseNotFound(person, ReadRoute.LINES, ReadSide.NONE, bot));
            case ModePnlView.Attempt.Failed<Optional<ModePnlDtos.RealBotPnlLinePageDto>> failed -> {
                if (failed.failure() instanceof RealPnlLineCursorRejected) {
                    throw ApiException.validation("cursor", "The list changed since the last page.");
                }
                log.warn("event=PNL_BOT_LINES_UNAVAILABLE cause={}", failed.label());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The lines could not be computed now.");
            }
        };
    }

    @Operation(
            operationId = "comparePaperAndRealBots",
            summary = "Um bot PAPER e um bot REAL da mesma estratégia, cada um na sua moeda, sem nenhuma diferença"
                    + " calculada (FR8.1)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Os dois lados; um lado que falhou vem UNAVAILABLE"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: id ou período inválido",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: um dos bots é de outra pessoa, não existe ou é do modo errado; reason"
                                + " diz qual lado (PAPER_BOT ou REAL_BOT)",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "422",
                        description = "VALIDATION_FAILED com reason TEMPLATE_MISMATCH: os dois bots não têm o mesmo"
                                + " templateId; sem nenhum valor de P&L",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/pnl/compare")
    public ResponseEntity<ModePnlDtos.PnlComparisonDto> compare(
            @Parameter(required = true, schema = @Schema(type = "string", format = "uuid"))
                    @RequestParam(required = false)
                    String paperBotId,
            @Parameter(required = true, schema = @Schema(type = "string", format = "uuid"))
                    @RequestParam(required = false)
                    String realBotId,
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"ALL", "RANGE"},
                                            defaultValue = "ALL"))
                    @RequestParam(required = false)
                    String period,
            @Parameter(schema = @Schema(type = "string", format = "date-time")) @RequestParam(required = false)
                    String from,
            @Parameter(schema = @Schema(type = "string", format = "date-time")) @RequestParam(required = false)
                    String to,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        // 1. Validation without the database: the two ids and the period.
        QueryParameters query =
                QueryParameters.of(parameters, Set.of("paperBotId", "realBotId", "period", "from", "to"));
        Optional<UUID> paperId = required(query, "paperBotId");
        Optional<UUID> realId = required(query, "realBotId");
        Optional<PnlWindow> read = PnlWindows.read(query);
        query.validate();
        PnlWindow window = read.orElseThrow();
        // 2. The ownership of BOTH bots, always both, before the strategy: the 422 never tells anything of a bot of
        // somebody else (BR5.2, BR5.3).
        Owned owned = readOnly.execute(status -> owned(person, paperId.get(), realId.get()));
        if (owned == null || owned.paper().isEmpty()) {
            throw refuseNotFound(person, ReadRoute.COMPARE, ReadSide.PAPER, paperId.get());
        }
        if (owned.real().isEmpty()) {
            throw refuseNotFound(person, ReadRoute.COMPARE, ReadSide.REAL, realId.get());
        }
        BotEntry paper = owned.paper().get();
        BotEntry real = owned.real().get();
        // 3. The strategy (BR5.2, PQ-8): a 422 with no value of P&L, counted and audited without waiting.
        if (!paper.templateId().equals(real.templateId())) {
            views.compareMetrics.templateMismatch();
            log.info("event=PNL_COMPARE_TEMPLATE_MISMATCH");
            views.refusals.templateMismatch(person, ReadRoute.COMPARE.name(), List.of(paper.botId(), real.botId()));
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_FIELDS,
                    "The two bots use different strategies.",
                    List.of(),
                    "TEMPLATE_MISMATCH",
                    Map.of());
        }
        // 4. The two sides in parallel, each under the deadline of a block; a side that fails is UNAVAILABLE.
        ModePnlStatement<ModePnlDtos.PaperComparisonSideDto, ModePnlDtos.RealComparisonSideDto> statement =
                modeView.statement(
                        window,
                        () -> paperBlocks.side(person, paper, window),
                        () -> realBlocks.side(person, real, window),
                        (market, cause) -> {
                            views.compareMetrics.sideUnavailable(market, cause);
                            log.warn("event=PNL_COMPARE_SIDE_UNAVAILABLE market={} cause={}", market, cause);
                        });
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ModePnlDtos.PnlComparisonDto.of(PnlWindows.dto(window), statement.paper(), statement.real()));
    }

    /** The first page of C5 and its largest. */
    static final int LINES_PAGE = 50;

    static final int LINES_MAX = 100;

    /** The routes whose 404 may be audited (closed values of the log and of the event). */
    enum ReadRoute {
        LINES,
        COMPARE
    }

    /** The side of C6 of a 404; {@code NONE} in C5. */
    enum ReadSide {
        NONE,
        PAPER,
        REAL
    }

    /** The two bots of a comparison, each a bot of the person in its expected mode, or empty. */
    private record Owned(Optional<BotEntry> paper, Optional<BotEntry> real) {}

    private Owned owned(Principal person, UUID paperId, UUID realId) {
        Optional<BotEntry> paper = views.directory
                .botOf(person.tenantId(), person.userId(), paperId)
                .filter(found -> found.market() == PnlMarket.PAPER);
        Optional<BotEntry> real = views.directory
                .botOf(person.tenantId(), person.userId(), realId)
                .filter(found -> found.market() == PnlMarket.REAL);
        return new Owned(paper, real);
    }

    /**
     * 261121-paper-real-unico U3 (BR8.2, BR5.3; decision F-1 of the NFR Design; NFR3.2, NFR3.13, NFR3.17): the ONLY
     * caller of {@link BotDirectory#ownedByAnotherTenant}. It runs the query on the request thread in BOTH cases of 404
     * (an id that does not exist and a bot of another tenant), so the time of the answer does not tell one from the
     * other; only a {@code true} is handed to the audit, which never waits. The body is the same in every case.
     *
     * @return the 404 to throw
     */
    private ApiException refuseNotFound(Principal person, ReadRoute route, ReadSide side, UUID botId) {
        Boolean other = readOnly.execute(status -> views.directory.ownedByAnotherTenant(person.tenantId(), botId));
        boolean anotherTenant = Boolean.TRUE.equals(other);
        log.info(
                "event=PNL_READ_NOT_FOUND route={} side={} reason={}",
                route,
                side,
                anotherTenant ? "TENANT_MISMATCH" : "NOT_FOUND");
        if (anotherTenant) {
            views.refusals.tenantMismatch(person, route.name(), side.name(), List.of(botId));
        }
        String reason =
                switch (side) {
                    case NONE -> null;
                    case PAPER -> "PAPER_BOT";
                    case REAL -> "REAL_BOT";
                };
        return new ApiException(ErrorCode.NOT_FOUND, "Bot not found.", List.of(), reason, Map.of());
    }

    private static Optional<UUID> required(QueryParameters query, String name) {
        Optional<UUID> id = query.uuid(name);
        if (query.text(name).isEmpty()) {
            query.violation(name, "Required.");
        }
        return id;
    }

    private static Optional<PnlMarket> market(String text, QueryParameters query) {
        if ("PAPER".equals(text)) {
            return Optional.of(PnlMarket.PAPER);
        }
        if ("REAL".equals(text)) {
            return Optional.of(PnlMarket.REAL);
        }
        query.violation("market", "Must be PAPER or REAL.");
        return Optional.empty();
    }

    @Operation(
            operationId = "getReconciliation",
            summary = "Resultado da última conferência entre operações e ledger (FR10.5)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Última conferência"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/reconciliation")
    public ReconciliationResultDto latest(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String botId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("botId"));
        Optional<UUID> bot = query.uuid("botId");
        query.validate();
        bot.ifPresent(id -> pnl.visible(person, id));
        return reconciliation
                .latest(person.tenantId(), person.userId())
                .map(result -> ReconciliationResultDto.of(result, bot))
                .orElseGet(() -> ReconciliationResultDto.nothingYet(
                        reconciliation.hasAccounts(person.tenantId(), person.userId())));
    }

    @Operation(
            operationId = "runReconciliation",
            summary = "Verificar agora: confere as contas PAPER da pessoa na hora (BR8.3)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters =
                    @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid")),
            responses = {
                @ApiResponse(responseCode = "200", description = "Resultado da conferência, gravada como ON_DEMAND"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED: Idempotency-Key ausente",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "429",
                        description = "RATE_LIMITED, com Retry-After (6 por minuto por pessoa)",
                        headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @PostMapping("/api/v1/reconciliation/run")
    public ResponseEntity<ReconciliationResultDto> run(@Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        // The common Idempotency-Key filter already refused a missing or malformed key with 400.
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        IdempotencyRequest idempotent = new IdempotencyRequest(
                IdempotentOperation.RECONCILIATION_RUN,
                IdempotencyScope.authenticated(person, IdempotentOperation.RECONCILIATION_RUN),
                key,
                RUN_DIGEST);
        IdempotentResponse stored = repeatableRead.execute(status -> idempotency.execute(idempotent, () -> {
            limiter.acquire(person.userId()).ifPresent(PnlController::rateLimited);
            ReconciliationResultDto result = ReconciliationResultDto.of(
                    reconciliation.check(person.tenantId(), person.userId(), Trigger.ON_DEMAND), Optional.empty());
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, write(result)));
        }));
        return ResponseEntity.status(stored.status())
                .cacheControl(CacheControl.noStore())
                .body(read(stored.body()));
    }

    /**
     * The request has no body: its content is the operation itself, as a SHA-256 hex digest like every digest of the
     * common mechanism (the stored column holds 64 characters).
     */
    static final String RUN_DIGEST = sha256("POST /api/v1/reconciliation/run");

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }

    private static void rateLimited(Duration wait) {
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many checks; try again later.",
                List.of(),
                null,
                Map.of("Retry-After", String.valueOf(wait.toSeconds())));
    }

    private String write(ReconciliationResultDto result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The reconciliation result could not be serialised", failure);
        }
    }

    private ReconciliationResultDto read(String body) {
        try {
            return json.readValue(body, ReconciliationResultDto.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The stored reconciliation result could not be read", failure);
        }
    }
}
