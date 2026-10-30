package com.memetrade.shared.error;

import java.util.List;
import java.util.Map;

/**
 * Explicit, expected API error mapped to an RFC 7807 problem. Messages must never carry secrets, SQL, nonces,
 * signatures, tokens or full wallet addresses.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> fields;
    private final String reason;
    private final Map<String, String> headers;
    private boolean audited;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null, Map.of());
    }

    public ApiException(
            ErrorCode code, String detail, List<FieldViolation> fields, String reason, Map<String, String> headers) {
        super(detail);
        this.code = code;
        this.fields = List.copyOf(fields);
        this.reason = reason;
        this.headers = Map.copyOf(headers);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                List.of(new FieldViolation(field, message)),
                null,
                Map.of());
    }

    /**
     * Marks a refusal whose single audit event was already written by the domain (for example LOGIN_REJECTED), so
     * the error handler does not add a second REQUEST_FORBIDDEN for the same refusal.
     */
    public ApiException alreadyAudited() {
        this.audited = true;
        return this;
    }

    public boolean isAudited() {
        return audited;
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> fields() {
        return fields;
    }

    public String reason() {
        return reason;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
