package com.memetrade.bots.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
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
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class DashboardStreamController {

    static {
        // C7 in the guest OpenAPI: the stream is text/event-stream text, never the shape of the Spring emitter.
        org.springdoc.core.utils.SpringDocUtils.getConfig().replaceWithClass(SseEmitter.class, String.class);
    }

    /** The access token lifetime (memetrade.auth.access-token-ttl) when the claim cannot be read. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(15);

    private static final ObjectMapper CLAIMS = new ObjectMapper();

    private final DashboardMarkPoller poller;
    private final AuthContext auth;
    private final Clock clock;

    public DashboardStreamController(DashboardMarkPoller poller, AuthContext auth, Clock clock) {
        this.poller = poller;
        this.auth = auth;
        this.clock = clock;
    }

    @Operation(
            operationId = "openDashboardStream",
            summary = "Server-Sent Events do painel: bot.state, trade.recorded, pnl.updated, data.status, heartbeat e"
                    + " auth.expired (FR12.2)",
            security = @SecurityRequirement(name = "accessToken"),
            parameters = @Parameter(name = "Last-Event-ID", in = ParameterIn.HEADER, schema = @Schema(type = "string")),
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "200",
                        description = "Fluxo de eventos do painel",
                        content =
                                @io.swagger.v3.oas.annotations.media.Content(
                                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                        schema = @Schema(implementation = String.class))),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(
                        responseCode = "401",
                        description = "UNAUTHENTICATED")
            })
    @GetMapping(value = "/api/v1/stream/dashboard", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Parameter(hidden = true) HttpServletRequest request) {
        Principal person = auth.current();
        Instant expiresAt = expiry(request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseGet(() -> clock.instant().plus(FALLBACK_TTL));
        return poller.open(person, expiresAt, Optional.ofNullable(request.getHeader("Last-Event-ID")));
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
