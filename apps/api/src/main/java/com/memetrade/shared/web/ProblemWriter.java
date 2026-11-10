package com.memetrade.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ProblemResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Writes an RFC 7807 problem directly from servlet filters, which run before the controller advice. The body is the
 * same {@link ProblemResponse} the advice writes, extensions included (u3-reacceptance: {@code currentVersion}).
 */
@Component
public class ProblemWriter {

    public static final String PROBLEM_JSON = "application/problem+json";

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.code().status());
        exception.headers().forEach(response::setHeader);
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ProblemResponse.of(exception, CorrelationId.current()));
    }
}
