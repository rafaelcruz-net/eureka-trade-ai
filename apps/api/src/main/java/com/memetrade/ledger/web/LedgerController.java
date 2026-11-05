package com.memetrade.ledger.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel.EntryKey;
import com.memetrade.ledger.api.LedgerReadModel.EntryPage;
import com.memetrade.ledger.api.LedgerReadModel.TradeKey;
import com.memetrade.ledger.api.LedgerReadModel.TradePage;
import com.memetrade.ledger.application.LedgerQueries;
import com.memetrade.ledger.web.LedgerDtos.DayUsageDto;
import com.memetrade.ledger.web.LedgerDtos.LedgerEntryDto;
import com.memetrade.ledger.web.LedgerDtos.LedgerEntryPageDto;
import com.memetrade.ledger.web.LedgerDtos.PaperTradeDto;
import com.memetrade.ledger.web.LedgerDtos.PaperTradePageDto;
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
 * Routes of C5 owned by the ledger (F8, BR4.3, BR7.5, BR9.1, BR10.2): only GET, never a route that changes or deletes
 * an entry. Tenant and person come from the token; a {@code tenantId} or {@code ownerUserId} sent by the client is an
 * unknown parameter. A bot of another person is {@code 404}; a bot not yet activated is empty.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LedgerController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final LedgerQueries queries;
    private final AuthContext auth;

    public LedgerController(LedgerQueries queries, AuthContext auth) {
        this.queries = queries;
        this.auth = auth;
    }

    @Operation(
            operationId = "listBotTrades",
            summary = "Operações simuladas do bot, cada uma uma única vez (AC4.10.3)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página das operações"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED",
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
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}/trades")
    public PaperTradePageDto trades(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        QueryParameters query = QueryParameters.of(parameters, Set.of("cursor", "limit"));
        int size = query.integer("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        query.validate();
        String scope = "trades:" + bot;
        Optional<TradeKey> after = query.text("cursor")
                .map(text -> KeysetCursor.decode(text, scope))
                .map(c -> new TradeKey(c.at(), c.id()));
        TradePage page = queries.trades(person, bot, after, size);
        return new PaperTradePageDto(
                page.items().stream().map(PaperTradeDto::of).toList(),
                page.next()
                        .map(k -> new KeysetCursor(k.createdAt(), k.orderId()).encode(scope))
                        .orElse(null),
                page.total());
    }

    @Operation(
            operationId = "getBotDayUsage",
            summary = "Uso do dia UTC: exposição pelo custo e perda do dia (AC4.10.1, BR7.5)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Uso do dia"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND: bot de outra pessoa",
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/bots/{botId}/day-usage")
    public DayUsageDto dayUsage(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("botId") String botId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        UUID bot = QueryParameters.requireUuid("botId", botId);
        QueryParameters.of(parameters, Set.of()).validate();
        LedgerQueries.DayUsage usage = queries.dayUsage(person, bot);
        return new DayUsageDto(usage.exposure().toPlainString(), usage.dayLoss().toPlainString(), usage.utcDay());
    }

    @Operation(
            operationId = "listLedgerEntries",
            summary = "Lançamentos do ledger PAPER da pessoa (AC5.2.1)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Página dos lançamentos"),
                @ApiResponse(
                        responseCode = "400",
                        description = "VALIDATION_FAILED",
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
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/ledger")
    public LedgerEntryPageDto ledger(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String botId,
            @Parameter(schema = @Schema(type = "string")) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Principal person = auth.current();
        QueryParameters query = QueryParameters.of(parameters, Set.of("botId", "cursor", "limit"));
        Optional<UUID> bot = query.uuid("botId");
        int size = query.integer("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        query.validate();
        String scope = "ledger:" + bot.map(UUID::toString).orElse("all");
        Optional<EntryKey> after = query.text("cursor")
                .map(text -> KeysetCursor.decode(text, scope))
                .map(c -> new EntryKey(c.at(), c.id()));
        EntryPage page = queries.entries(person, bot, after, size);
        return new LedgerEntryPageDto(
                page.items().stream().map(LedgerEntryDto::of).toList(),
                page.next()
                        .map(k -> new KeysetCursor(k.createdAt(), k.entryId()).encode(scope))
                        .orElse(null),
                page.total());
    }
}
