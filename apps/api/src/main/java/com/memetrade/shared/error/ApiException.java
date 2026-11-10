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
    private final Map<String, String> extensions;
    private boolean audited;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null, Map.of());
    }

    public ApiException(
            ErrorCode code, String detail, List<FieldViolation> fields, String reason, Map<String, String> headers) {
        this(code, detail, fields, reason, headers, Map.of());
    }

    /**
     * u3-reacceptance (NFR1.1): {@code extensions} are optional members of the problem body, copied by the error
     * handler and by the {@code ProblemWriter} of the filters (today only {@code currentVersion}).
     */
    public ApiException(
            ErrorCode code,
            String detail,
            List<FieldViolation> fields,
            String reason,
            Map<String, String> headers,
            Map<String, String> extensions) {
        super(detail);
        this.code = code;
        this.fields = List.copyOf(fields);
        this.reason = reason;
        this.headers = Map.copyOf(headers);
        this.extensions = Map.copyOf(extensions);
    }

    /** u3-reacceptance: a problem that carries the effective current version of the terms in {@code currentVersion}. */
    public static ApiException withCurrentVersion(ErrorCode code, String detail, String currentVersion) {
        return new ApiException(
                code, detail, List.of(), null, Map.of(), Map.of(ProblemResponse.CURRENT_VERSION, currentVersion));
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

    public Map<String, String> extensions() {
        return extensions;
    }
}
