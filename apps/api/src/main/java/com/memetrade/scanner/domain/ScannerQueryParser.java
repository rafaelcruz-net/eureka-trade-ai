package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * BR2.7 and NFR6.2: parses the query parameters of the list into a {@link ScannerQuery}, or refuses with
 * {@code 400 VALIDATION_FAILED} listing EVERY invalid field: a malformed or negative decimal, an integer outside the
 * contract, a sort or order outside its list, an unknown or repeated parameter. A {@code tenantId} sent by the client
 * is ignored, never refused (U1 BR2.1, C2 common rules).
 */
public final class ScannerQueryParser {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    static final int MAX_DIGITS = 30;
    static final int MAX_SCALE = 18;
    static final int MAX_CURSOR = 1024;

    static final Set<String> ACCEPTED = Set.of(
            "cursor",
            "limit",
            "sort",
            "order",
            "minLiquidity",
            "maxAgeMinutes",
            "minVolume24h",
            "minHolders",
            "maxRiskScore",
            "sourceId",
            "tenantId");

    private static final Pattern DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern INTEGER = Pattern.compile("\\d{1,9}");
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9-]{1,64}");

    private ScannerQueryParser() {}

    public static ScannerQuery parse(Map<String, List<String>> parameters) {
        List<FieldViolation> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            if (!ACCEPTED.contains(entry.getKey())) {
                violations.add(new FieldViolation(entry.getKey(), "Parameter is not accepted."));
            } else if (entry.getValue().size() > 1) {
                violations.add(new FieldViolation(entry.getKey(), "Parameter must appear once."));
            }
        }
        Reader reader = new Reader(parameters, violations);
        Filters filters = new Filters(
                reader.decimal("minLiquidity"),
                reader.integer("maxAgeMinutes", 0, 999_999_999).map(Long::valueOf),
                reader.decimal("minVolume24h"),
                reader.integer("minHolders", 0, 999_999_999).map(BigDecimal::valueOf),
                reader.integer("maxRiskScore", 0, 100),
                reader.text("sourceId", SOURCE, "Must be a source identifier."));
        SortField sort = reader.choice("sort", SortField::valueOf).orElse(SortField.AGE);
        SortOrder order = reader.choice("order", SortOrder::valueOf).orElse(ScannerQuery.defaultOrder(sort));
        int limit = reader.integer("limit", 1, MAX_LIMIT).orElse(DEFAULT_LIMIT);
        Optional<String> cursor = reader.raw("cursor").filter(c -> !c.isBlank());
        if (cursor.isPresent() && cursor.get().length() > MAX_CURSOR) {
            violations.add(new FieldViolation("cursor", "Cursor is not valid; start again from the first page."));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", violations, null, Map.of());
        }
        return new ScannerQuery(filters, sort, order, cursor, limit);
    }

    private record Reader(Map<String, List<String>> parameters, List<FieldViolation> violations) {

        Optional<String> raw(String name) {
            List<String> values = parameters.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
        }

        Optional<BigDecimal> decimal(String name) {
            Optional<String> raw = raw(name);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            String text = raw.get();
            if (!DECIMAL.matcher(text).matches()) {
                return invalid(name, "Must be a non-negative decimal.");
            }
            BigDecimal value = new BigDecimal(text);
            if (value.precision() > MAX_DIGITS || value.scale() > MAX_SCALE) {
                return invalid(name, "Has too many digits.");
            }
            return Optional.of(value);
        }

        Optional<Integer> integer(String name, int min, int max) {
            Optional<String> raw = raw(name);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            if (!INTEGER.matcher(raw.get()).matches()) {
                return invalid(name, "Must be an integer between " + min + " and " + max + ".");
            }
            int value = Integer.parseInt(raw.get());
            if (value < min || value > max) {
                return invalid(name, "Must be an integer between " + min + " and " + max + ".");
            }
            return Optional.of(value);
        }

        Optional<String> text(String name, Pattern pattern, String message) {
            Optional<String> raw = raw(name);
            if (raw.isPresent() && !pattern.matcher(raw.get()).matches()) {
                return invalid(name, message);
            }
            return raw;
        }

        <E> Optional<E> choice(String name, Function<String, E> parse) {
            Optional<String> raw = raw(name);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(parse.apply(raw.get()));
            } catch (IllegalArgumentException unknown) {
                return invalid(name, "Is not one of the accepted values.");
            }
        }

        private <T> Optional<T> invalid(String name, String message) {
            violations.add(new FieldViolation(name, message));
            return Optional.empty();
        }
    }
}
