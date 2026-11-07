package com.memetrade.bots.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * C7, hosted by U5 in the api process: the live channel of the dashboard, opened with {@code fetch} and the
 * {@code Authorization} header (no EventSource, project.md). The person comes from the token only; the channel closes
 * with {@code auth.expired} at the expiry of the access token, read from the {@code exp} claim of the token the
 * authentication filter already validated (the token is never logged nor stored).
 *
 * <p>U9 (NFR2.1, security-design.md): with {@code max-channels-per-person} channels of the same person already open,
 * the next opening is refused with {@code 429 RATE_LIMITED} as an RFC 7807 problem with {@code Retry-After: 30} and the
 * {@code correlationId}, BEFORE any stream opens; the refusal is logged at WARN with the masked person and the count
 * only, and is not audited (not a sensitive domain action). The OpenAPI documents the six event envelopes
 * ({@link DashboardEvents}) so the guest app generates their types.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class DashboardStreamController {

    static {
        // C7 in the guest OpenAPI: the stream is text/event-stream text, never the shape of the Spring emitter.
        org.springdoc.core.utils.SpringDocUtils.getConfig().replaceWithClass(SseEmitter.class, String.class);
    }

    /** U9 (NFR2.1): seconds the app waits at least before opening again after a 429. */
    static final String RETRY_AFTER_SECONDS = "30";

    private static final Logger log = LoggerFactory.getLogger(DashboardStreamController.class);

    /** The access token lifetime (memetrade.auth.access-token-ttl) when the claim cannot be read. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(15);

    private static final ObjectMapper CLAIMS = new ObjectMapper();

    private final DashboardMarkPoller poller;
    private final AuthContext auth;
    private final Clock clock;
    private final ProblemWriter problems;

    public DashboardStreamController(
            DashboardMarkPoller poller, AuthContext auth, Clock clock, ProblemWriter problems) {
        this.poller = poller;
        this.auth = auth;
        this.clock = clock;
        this.problems = problems;
    }

    @Operation(
            operationId = "openDashboardStream",
            summary = "Server-Sent Events do painel: bot.state, trade.recorded, pnl.updated, data.status, heartbeat e"
                    + " auth.expired (FR12.2)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters = @Parameter(name = "Last-Event-ID", in = ParameterIn.HEADER, schema = @Schema(type = "string")),
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Fluxo de eventos do painel. Cada evento tem id (b<marca>.l<marca>.s<marca>, só"
                                + " para Last-Event-ID), event (o nome) e data: o JSON de um destes envelopes, conforme"
                                + " o nome: bot.state DashboardBotStateEvent, trade.recorded"
                                + " DashboardTradeRecordedEvent, pnl.updated DashboardPnlUpdatedEvent, data.status"
                                + " DashboardDataStatusEvent, heartbeat DashboardHeartbeatEvent, auth.expired"
                                + " DashboardAuthExpiredEvent (U9)",
                        content =
                                @Content(
                                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                        schema =
                                                @Schema(
                                                        oneOf = {
                                                            DashboardEvents.BotState.class,
                                                            DashboardEvents.TradeRecorded.class,
                                                            DashboardEvents.PnlUpdated.class,
                                                            DashboardEvents.DataStatus.class,
                                                            DashboardEvents.Heartbeat.class,
                                                            DashboardEvents.AuthExpired.class
                                                        }))),
                @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED"),
                @ApiResponse(
                        responseCode = "429",
                        description = "RATE_LIMITED: a pessoa já tem 5 canais abertos; Retry-After diz quando tentar de"
                                + " novo (U9, NFR2.1)",
                        headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
                        content =
                                @Content(
                                        mediaType = ProblemWriter.PROBLEM_JSON,
                                        schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping(value = "/api/v1/stream/dashboard", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response)
            throws IOException {
        Principal person = auth.current();
        Instant expiresAt = expiry(request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseGet(() -> clock.instant().plus(FALLBACK_TTL));
        Optional<SseEmitter> opened =
                poller.open(person, expiresAt, Optional.ofNullable(request.getHeader("Last-Event-ID")));
        if (opened.isPresent()) {
            return opened.get();
        }
        log.warn(
                "Live channel refused: person={}, openChannels={}, limit={}",
                masked(person.userId()),
                poller.openChannelsOf(person),
                poller.maxChannelsPerPerson());
        // Written here, as a filter would, because the route produces text/event-stream (no JSON negotiation).
        problems.write(
                response,
                new ApiException(
                        ErrorCode.RATE_LIMITED,
                        "Too many open live channels; close another tab or try again later.",
                        List.of(),
                        null,
                        Map.of("Retry-After", RETRY_AFTER_SECONDS)));
        // A null emitter tells Spring MVC the response is already complete.
        return null;
    }

    /** The first 8 hex digits of the person id: enough to correlate, never the whole identifier. */
    static String masked(UUID person) {
        return person.toString().substring(0, 8) + "…";
    }

    /** The {@code exp} claim of the bearer token (seconds since the epoch), if readable. */
    static Optional<Instant> expiry(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String[] parts = authorization.substring("Bearer ".length()).trim().split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            JsonNode payload =
                    CLAIMS.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return payload.has("exp")
                    ? Optional.of(Instant.ofEpochSecond(payload.get("exp").asLong()))
                    : Optional.empty();
        } catch (IllegalArgumentException | java.io.IOException unreadable) {
            return Optional.empty();
        }
    }
}
