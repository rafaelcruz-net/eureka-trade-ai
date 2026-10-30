package com.memetrade.audit.domain;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Query of the trail by an administrator (entities.md, BR2.2): {@code from} inclusive and {@code to} exclusive (UTC,
 * at most 31 days apart), optional exact {@code action}, optional cursor and {@code limit} 1-100 (default 50).
 * Value object, never persisted.
 */
public record AuditEventQuery(
        Instant from, Instant to, Optional<String> action, Optional<AuditCursor> cursor, int limit) {

    public static final Duration MAX_PERIOD = Duration.ofDays(31);
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /** The only query parameters of {@code GET /api/v1/admin/audit-events}; anything else is refused. */
    public static final Set<String> PARAMETERS = Set.of("from", "to", "action", "cursor", "limit");

    public AuditEventQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cursor, "cursor");
    }

    /** Rows to read: one more than the page, only to know whether a next page exists (performance-design.md). */
    public int rowsToRead() {
        return limit + 1;
    }

    /**
     * Validates the raw query parameters (BR2.2) and collects every violation.
     *
     * @throws ApiException {@code 400 VALIDATION_FAILED} with the list of invalid fields
     */
    public static AuditEventQuery fromParameters(Map<String, List<String>> parameters) {
        List<FieldViolation> violations = new ArrayList<>();
        parameters.forEach((name, values) -> {
            if (!PARAMETERS.contains(name)) {
                violations.add(new FieldViolation(name, "Parameter is not accepted."));
            } else if (values.size() > 1) {
                violations.add(new FieldViolation(name, "Parameter must appear only once."));
            }
        });
        Instant from = instant(parameters, "from", violations);
        Instant to = instant(parameters, "to", violations);
        if (from != null && to != null) {
            checkPeriod(from, to, violations);
        }
        Optional<String> action = single(parameters, "action").map(value -> {
            if (!AuditEventValidator.isValidAction(value)) {
                violations.add(new FieldViolation(
                        "action", "Must be upper case letters, digits and underscores, up to 64 characters."));
            }
            return value;
        });
        int limit = limit(parameters, violations);
        Optional<AuditCursor> cursor = cursor(parameters, from, to, violations);
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", violations, null, Map.of());
        }
        return new AuditEventQuery(from, to, action, cursor, limit);
    }

    private static void checkPeriod(Instant from, Instant to, List<FieldViolation> violations) {
        if (!to.isAfter(from)) {
            violations.add(new FieldViolation("to", "Must be after from."));
        } else if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            violations.add(new FieldViolation("to", "The period is at most 31 days."));
        }
    }

    private static Instant instant(Map<String, List<String>> parameters, String name, List<FieldViolation> out) {
        Optional<String> text = single(parameters, name);
        if (text.isEmpty()) {
            out.add(new FieldViolation(name, "Parameter is required."));
            return null;
        }
        try {
            return Instant.parse(text.get());
        } catch (DateTimeParseException invalid) {
            out.add(new FieldViolation(name, "Must be an ISO-8601 instant, for example 2026-10-01T00:00:00Z."));
            return null;
        }
    }

    private static int limit(Map<String, List<String>> parameters, List<FieldViolation> out) {
        Optional<String> text = single(parameters, "limit");
        if (text.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(text.get());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException notANumber) {
            // reported below as the same violation as an out-of-range number
        }
        out.add(new FieldViolation("limit", "Must be an integer between 1 and 100."));
        return DEFAULT_LIMIT;
    }

    private static Optional<AuditCursor> cursor(
            Map<String, List<String>> parameters, Instant from, Instant to, List<FieldViolation> out) {
        Optional<String> text = single(parameters, "cursor");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            AuditCursor cursor = AuditCursor.decode(text.get());
            boolean outside = (from != null && cursor.occurredAt().isBefore(from))
                    || (to != null && !cursor.occurredAt().isBefore(to));
            if (!outside) {
                return Optional.of(cursor);
            }
        } catch (ApiException invalid) {
            // reported below with the same message as a cursor outside the period
        }
        out.add(AuditCursor.invalid().fields().getFirst());
        return Optional.empty();
    }

    private static Optional<String> single(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null
                || values.isEmpty()
                || values.getFirst() == null
                || values.getFirst().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(values.getFirst().trim());
    }
}
