package com.memetrade.shared.retention;

import java.time.Duration;
import java.util.Objects;

/**
 * A retention period in the units of the privacy policy (J7): whole hours (days are 24 hours) or calendar months. Two
 * terms are equal when they name the same span: {@code days(1)} equals {@code hours(24)}; months never equal hours.
 *
 * @param amount a positive count of {@code unit}
 */
public record RetentionTerm(long amount, Unit unit) {

    /** The unit of a term. */
    public enum Unit {
        HOURS,
        MONTHS
    }

    public RetentionTerm {
        Objects.requireNonNull(unit, "unit");
        if (amount < 1) {
            throw new IllegalArgumentException("A retention term must be positive");
        }
    }

    public static RetentionTerm hours(long hours) {
        return new RetentionTerm(hours, Unit.HOURS);
    }

    public static RetentionTerm days(long days) {
        return new RetentionTerm(Math.multiplyExact(days, 24), Unit.HOURS);
    }

    public static RetentionTerm months(long months) {
        return new RetentionTerm(months, Unit.MONTHS);
    }

    /**
     * The term of a configured duration, as the purges read it from their properties.
     *
     * @throws IllegalArgumentException when the duration is not a positive whole number of hours
     */
    public static RetentionTerm of(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero() || !duration.equals(Duration.ofHours(duration.toHours()))) {
            throw new IllegalArgumentException("A retention duration must be a positive whole number of hours");
        }
        return hours(duration.toHours());
    }

    /** {@code 12 months}, {@code 30 days} or {@code 36 hours}: the form used in conformance messages. */
    @Override
    public String toString() {
        if (unit == Unit.MONTHS) {
            return amount + (amount == 1 ? " month" : " months");
        }
        if (amount % 24 == 0) {
            long days = amount / 24;
            return days + (days == 1 ? " day" : " days");
        }
        return amount + (amount == 1 ? " hour" : " hours");
    }
}
