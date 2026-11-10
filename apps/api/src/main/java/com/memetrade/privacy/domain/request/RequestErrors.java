package com.memetrade.privacy.domain.request;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The problems of the request routes (contract-summary.md P1, P2). Each names the field or the states, never a value
 * the person or the administrator typed (BR4.3).
 */
final class RequestErrors {

    private RequestErrors() {}

    static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Data request not found.");
    }

    static ApiException alreadyOpen() {
        return new ApiException(
                ErrorCode.DATA_REQUEST_ALREADY_OPEN, "An open request of the same type already exists for the person.");
    }

    static ApiException closed() {
        return new ApiException(ErrorCode.DATA_REQUEST_CLOSED, "The request is closed; it does not change any more.");
    }

    static ApiException invalidTransition(DataRequestStatus from, String to) {
        return new ApiException(
                ErrorCode.INVALID_TRANSITION, "The transition from " + from + " to " + to + " is not allowed.");
    }

    static ApiException invalidTransition(String detail) {
        return new ApiException(ErrorCode.INVALID_TRANSITION, detail);
    }

    static ApiException identityNotVerified() {
        return new ApiException(
                ErrorCode.IDENTITY_NOT_VERIFIED,
                "An EMAIL request needs a confirmed identity before the summary is released.");
    }

    static ApiException validation(List<FieldViolation> violations) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", violations, null, Map.of());
    }

    static ApiException unprocessable(String field, String message) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_FIELDS,
                "One or more fields break a rule of the request.",
                List.of(new FieldViolation(field, message)),
                null,
                Map.of());
    }

    static ApiException rateLimited(Duration wait) {
        long seconds = Math.max(1, wait.toSeconds());
        return new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many requests in the last hour; try again later.",
                List.of(),
                "DATA_REQUEST_LIMIT",
                Map.of("Retry-After", Long.toString(seconds)));
    }
}
