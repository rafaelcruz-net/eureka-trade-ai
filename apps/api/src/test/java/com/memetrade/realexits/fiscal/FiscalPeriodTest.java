package com.memetrade.realexits.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7, written BEFORE the code (BR7.7, S31, U7-NFR3.8): the period is two dates in the zone of the person,
 * at most one year per file (365 days accepted, 366 refused), never inverted; without them, the last 12 months.
 */
@Tag("u7-exits-and-fiscal")
class FiscalPeriodTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-17");

    private static FiscalPeriod of(String from, String to) {
        return FiscalPeriod.of(
                Optional.ofNullable(from).map(LocalDate::parse),
                Optional.ofNullable(to).map(LocalDate::parse),
                SAO_PAULO,
                TODAY);
    }

    @Test
    void threeHundredSixtyFiveDaysAreAcceptedAndThreeHundredSixtySixRefused() {
        assertThat(of("2026-01-01", "2027-01-01").days()).isEqualTo(365);
        assertThatThrownBy(() -> of("2026-01-01", "2027-01-02"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 ano");
    }

    @Test
    void anInvertedPeriodIsRefused() {
        assertThatThrownBy(() -> of("2026-05-02", "2026-05-01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutDatesTheLastTwelveMonthsEndingToday() {
        FiscalPeriod period = of(null, null);

        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.from()).isEqualTo(TODAY.minusDays(365));
    }

    @Test
    void withOnlyOneDateTheOtherCompletesAYear() {
        assertThat(of("2026-01-01", null).to()).isEqualTo(LocalDate.parse("2027-01-01"));
        assertThat(of(null, "2026-06-30").from()).isEqualTo(LocalDate.parse("2025-06-30"));
    }

    /** U7-NFR10.1: the dates are of the person; the instants of the query are UTC, the end day included. */
    @Test
    void theInstantsCoverTheWholeDaysInTheZoneOfThePerson() {
        FiscalPeriod period = of("2026-03-01", "2026-03-31");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-03-01T03:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-04-01T03:00:00Z"));
        assertThat(period.fileName()).isEqualTo("historico-real-2026-03-01-2026-03-31.csv");
    }
}
