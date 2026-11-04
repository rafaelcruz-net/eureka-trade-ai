package com.memetrade.shared.web;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

/**
 * Strict reading of query parameters for the read routes of U7 (NFR6.6): an unknown or repeated parameter, a value that
 * is not a canonical UUID, an integer out of range or an instant that is not ISO 8601 UTC is {@code 400
 * VALIDATION_FAILED} with every invalid field listed.
 */
public final class QueryParameters {

    private final MultiValueMap<String, String> values;
    private final List<FieldViolation> violations = new ArrayList<>();

    private QueryParameters(MultiValueMap<String, String> values) {
        this.values = values;
    }

    /** Starts the reading; {@code mode} is always allowed (the mode guard already refused anything but PAPER). */
    public static QueryParameters of(MultiValueMap<String, String> values, Set<String> allowed) {
        QueryParameters parameters = new QueryParameters(values);
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (!allowed.contains(entry.getKey()) && !"mode".equals(entry.getKey())) {
                parameters.violations.add(new FieldViolation(entry.getKey(), "Unknown parameter."));
            } else if (entry.getValue().size() > 1) {
                parameters.violations.add(new FieldViolation(entry.getKey(), "Repeated parameter."));
            }
        }
        return parameters;
    }

    public Optional<String> text(String name) {
        return Optional.ofNullable(values.getFirst(name)).filter(value -> !value.isBlank());
    }

    public Optional<UUID> uuid(String name) {
        return text(name).flatMap(value -> parseUuid(name, value));
    }

    public int integer(String name, int defaultValue, int min, int max) {
        Optional<String> value = text(name);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.get());
            if (parsed < min || parsed > max) {
                violations.add(new FieldViolation(name, "Must be between " + min + " and " + max + "."));
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            violations.add(new FieldViolation(name, "Must be an integer."));
            return defaultValue;
        }
    }

    public Optional<Instant> requiredInstant(String name) {
        Optional<String> value = text(name);
        if (value.isEmpty()) {
            violations.add(new FieldViolation(name, "Required."));
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.get()));
        } catch (DateTimeParseException notAnInstant) {
            violations.add(new FieldViolation(name, "Must be an ISO 8601 UTC instant."));
            return Optional.empty();
        }
    }

    public void violation(String field, String message) {
        violations.add(new FieldViolation(field, message));
    }

    /** Throws {@code 400 VALIDATION_FAILED} with every violation found so far. */
    public void validate() {
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", violations, null, Map.of());
        }
    }

    /** A path or query UUID: canonical form only. */
    public static UUID requireUuid(String name, String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) {
                return parsed;
            }
        } catch (IllegalArgumentException notUuid) {
            throw ApiException.validation(name, "Must be a UUID.");
        }
        throw ApiException.validation(name, "Must be a UUID.");
    }

    private Optional<UUID> parseUuid(String name, String value) {
        try {
            return Optional.of(requireUuid(name, value));
        } catch (ApiException invalid) {
            violations.add(new FieldViolation(name, "Must be a UUID."));
            return Optional.empty();
        }
    }
}
