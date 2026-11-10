package com.memetrade.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

/**
 * RFC 7807 body ({@code application/problem+json}) with a stable {@code type} per category, a machine-readable
 * {@code code} and the request {@code correlationId}. Never contains a stack trace, SQL or a secret.
 *
 * <p>u3-reacceptance (NFR1.1): {@code currentVersion} is an optional extension, serialised only when present; it
 * carries the effective current version of the terms in {@code TERMS_REACCEPTANCE_REQUIRED} and
 * {@code TERMS_VERSION_OUTDATED}.
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
        String reason,

        @Schema(
                description = "Versão vigente efetiva dos termos; só em TERMS_REACCEPTANCE_REQUIRED e"
                        + " TERMS_VERSION_OUTDATED")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String currentVersion) {

    /** Extension key of {@link ApiException#extensions()} that fills {@link #currentVersion()}. */
    public static final String CURRENT_VERSION = "currentVersion";

    public static ProblemResponse of(ErrorCode code, String detail, String correlationId) {
        return new ProblemResponse(
                code.type(), code.title(), code.status(), detail, code.code(), correlationId, null, null, null);
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
                exception.reason(),
                exception.extensions().get(CURRENT_VERSION));
    }
}
