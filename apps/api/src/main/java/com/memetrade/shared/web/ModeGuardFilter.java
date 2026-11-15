package com.memetrade.shared.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * No request declares {@code mode}; a body or query carrying {@code mode} with any value other than {@code PAPER}
 * is refused with {@code 403 REAL_MODE_UNAVAILABLE} and audited (contract-summary.md "Modo", FR15.2).
 */
@Component
@Order(FilterOrder.MODE_GUARD)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class ModeGuardFilter extends OncePerRequestFilter {

    static final String PAPER = "PAPER";

    private final ObjectMapper objectMapper;
    private final ProblemWriter problemWriter;
    private final ForbiddenRequestRecorder recorder;

    public ModeGuardFilter(ObjectMapper objectMapper, ProblemWriter problemWriter, ForbiddenRequestRecorder recorder) {
        this.objectMapper = objectMapper;
        this.problemWriter = problemWriter;
        this.recorder = recorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 261113-trading-real-bot (contract-summary.md "Modo"): the REAL routes exist only for the REAL; their
        // "mode" field is the operation mode (SIGNED, AUTO_EXITS, AUTO_FULL), never the PAPER/REAL mode.
        // The normalised path, never the raw URI: "/api/v1/real/%2e%2e/<PAPER route>" must not escape the guard.
        String path = RequestPaths.normalized(request);
        return !path.startsWith("/api/") || path.startsWith("/api/v1/real/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (queryDeclaresNonPaperMode(request)) {
            refuse(request, response);
            return;
        }
        if (!hasBody(request)) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = CachedBodyRequest.wrap(request);
        if (cached == null) {
            problemWriter.write(response, ApiException.validation("body", "Request body is too large."));
            return;
        }
        if (bodyDeclaresNonPaperMode(cached.body())) {
            refuse(cached, response);
            return;
        }
        chain.doFilter(cached, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        recorder.record(request, ErrorCode.REAL_MODE_UNAVAILABLE, "MODE_NOT_PAPER");
        problemWriter.write(
                response,
                new ApiException(ErrorCode.REAL_MODE_UNAVAILABLE, "Only PAPER mode exists; REAL mode is unavailable."));
    }

    private static boolean hasBody(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static boolean queryDeclaresNonPaperMode(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) {
            return false;
        }
        MultiValueMap<String, String> params =
                UriComponentsBuilder.newInstance().query(query).build().getQueryParams();
        List<String> modes = params.get("mode");
        return modes != null && modes.stream().anyMatch(value -> !PAPER.equals(value));
    }

    private boolean bodyDeclaresNonPaperMode(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject() || !node.has("mode")) {
                return false;
            }
            JsonNode mode = node.get("mode");
            return !(mode.isTextual() && PAPER.equals(mode.asText()));
        } catch (JsonProcessingException notJson) {
            // Malformed JSON is not a mode declaration; the controller answers 400 VALIDATION_FAILED.
            return false;
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cached request body could not be read", unreadable);
        }
    }
}
