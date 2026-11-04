package com.memetrade.shared.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

/**
 * RFC 7807 body ({@code application/problem+json}) with a stable {@code type} per category, a machine-readable
 * {@code code} and the request {@code correlationId}. Never contains a stack trace, SQL or a secret.
 */
@Schema(name = "Problem")
public record ProblemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) URI type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        String detail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String correlationId,
        List<FieldViolation> fields,
        String reason) {

    public static ProblemResponse of(ErrorCode code, String detail, String correlationId) {
        return new ProblemResponse(
                code.type(), code.title(), code.status(), detail, code.code(), correlationId, null, null);
    }

    public static ProblemResponse of(ApiException exception, String correlationId) {
        List<FieldViolation> fields = exception.fields().isEmpty() ? null : exception.fields();
        ErrorCode code = exception.code();
        return new ProblemResponse(
                code.type(),
                code.title(),
                code.status(),
                exception.getMessage(),
                code.code(),
                correlationId,
                fields,
                exception.reason());
    }
}
