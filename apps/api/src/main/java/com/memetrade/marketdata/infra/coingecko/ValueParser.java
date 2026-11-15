package com.memetrade.marketdata.infra.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BR1.2 (US6.3): turns one field of a CoinGecko answer into a {@link MarketValue}. Numbers are read as exact decimals,
 * never through floating point; a JSON floating-point node that is not already a {@link BigDecimal} is refused.
 *
 * <ul>
 *   <li>absent or JSON {@code null}: {@code Unknown(NOT_PROVIDED)}, not counted (the provider simply has no value);
 *   <li>present but out of range or unreadable: {@code Unknown(NOT_PROVIDED)}, counted by field through
 *       {@code onInvalid} ({@code marketdata_invalid_values_total{field}}) and logged with the field and the reason
 *       only, never the raw content;
 *   <li>an informed zero is {@code Known 0}, never unknown.
 * </ul>
 *
 * Ranges: PRICE, LIQUIDITY and VOLUME_24H at least 0; HOLDERS a whole number at least 0; CREATED_AT an ISO-8601
 * date-time with offset, in epoch seconds, never after the time of the fetch. Values keep at most
 * {@value #MAX_INTEGER_DIGITS} integer digits and fit the stored precision ({@value #MAX_PRECISION} digits, scale up to
 * {@value #MAX_SCALE}): extra decimals are rounded half-even, and a positive value that would round to zero is invalid
 * (it is never shown as zero).
 */
public final class ValueParser {

    private static final Logger log = LoggerFactory.getLogger(ValueParser.class);

    static final int MAX_TEXT = 64;
    static final int MAX_SCALE = 18;
    static final int MAX_PRECISION = 30;
    static final int MAX_INTEGER_DIGITS = 20;

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d{1,4})?");

    private final SourceRef source;
    private final Consumer<MarketField> onInvalid;

    public ValueParser(SourceRef source, Consumer<MarketField> onInvalid) {
        this.source = Objects.requireNonNull(source, "source");
        this.onInvalid = Objects.requireNonNull(onInvalid, "onInvalid");
    }

    /** PRICE, LIQUIDITY or VOLUME_24H: an exact decimal at least 0. */
    public MarketValue amount(MarketField field, JsonNode node, Instant observedAt) {
        if (absent(node)) {
            return notProvided();
        }
        BigDecimal value = decimal(node);
        if (value == null) {
            return invalid(field, "unreadable");
        }
        if (value.signum() < 0) {
            return invalid(field, "negative");
        }
        BigDecimal fitted = fit(value);
        if (fitted == null) {
            return invalid(field, "out of the stored precision");
        }
        return known(fitted, observedAt);
    }

    /** HOLDERS: a whole number at least 0 (an integral decimal such as {@code 12.0} is accepted). */
    public MarketValue holders(JsonNode node, Instant observedAt) {
        if (absent(node)) {
            return notProvided();
        }
        BigDecimal value = decimal(node);
        if (value == null) {
            return invalid(MarketField.HOLDERS, "unreadable");
        }
        if (value.signum() < 0) {
            return invalid(MarketField.HOLDERS, "negative");
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0) {
            return invalid(MarketField.HOLDERS, "fractional");
        }
        BigDecimal whole = stripped.setScale(0, RoundingMode.UNNECESSARY);
        if (whole.precision() > MAX_INTEGER_DIGITS) {
            return invalid(MarketField.HOLDERS, "too large");
        }
        return known(whole, observedAt);
    }

    /** CREATED_AT: an ISO-8601 date-time with offset, in epoch seconds, never after {@code observedAt}. */
    public MarketValue createdAt(JsonNode node, Instant observedAt) {
        if (absent(node)) {
            return notProvided();
        }
        if (!node.isTextual() || node.textValue().length() > MAX_TEXT) {
            return invalid(MarketField.CREATED_AT, "not a date-time text");
        }
        Instant created;
        try {
            created = OffsetDateTime.parse(node.textValue().trim()).toInstant();
        } catch (DateTimeParseException unreadable) {
            return invalid(MarketField.CREATED_AT, "unreadable date-time");
        }
        if (created.isAfter(observedAt)) {
            return invalid(MarketField.CREATED_AT, "in the future");
        }
        return known(BigDecimal.valueOf(created.getEpochSecond()), observedAt);
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /** The exact decimal of a text or number node, or {@code null} when it cannot be read exactly. */
    private static BigDecimal decimal(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue().trim();
            if (text.isEmpty()
                    || text.length() > MAX_TEXT
                    || !NUMBER.matcher(text).matches()) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException unreadable) {
                return null;
            }
        }
        if (node.isBigDecimal() || node.isIntegralNumber()) {
            return node.decimalValue();
        }
        return null;
    }

    /** Rounds extra decimals half-even into the stored precision; {@code null} when it does not fit. */
    private static BigDecimal fit(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int integerDigits = Math.max(0, stripped.precision() - stripped.scale());
        if (integerDigits > MAX_INTEGER_DIGITS) {
            return null;
        }
        int scale = Math.min(MAX_SCALE, MAX_PRECISION - integerDigits);
        BigDecimal fitted = stripped.scale() > scale ? stripped.setScale(scale, RoundingMode.HALF_EVEN) : stripped;
        if (value.signum() > 0 && fitted.signum() == 0) {
            return null;
        }
        if (fitted.scale() < 0) {
            fitted = fitted.setScale(0, RoundingMode.UNNECESSARY);
        }
        return fitted;
    }

    private MarketValue known(BigDecimal value, Instant observedAt) {
        return new MarketValue.Known(value, source, observedAt, false);
    }

    private MarketValue notProvided() {
        return MarketValue.Unknown.notProvided(source);
    }

    private MarketValue invalid(MarketField field, String reason) {
        onInvalid.accept(field);
        log.warn("Invalid market value refused: source={}, field={}, reason={}", source.sourceId(), field, reason);
        return notProvided();
    }
}
