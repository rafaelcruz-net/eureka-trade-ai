package com.memetrade.riskcontrol.real.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Step 1 (U3-NFR8.3): the range of every key and the refusal of a looser value outside the test profiles. */
class RealRiskLimitsTest {

    private static final RealRiskLimits D = RealRiskLimits.DEFAULTS;

    @Test
    void theDefaultsAreTheValuesOfTheRequirements() {
        assertThat(D.orderMaxBps()).isEqualTo(1000);
        assertThat(D.dailyLossMaxBps()).isEqualTo(2000);
        assertThat(D.dailyBuysMax()).isEqualTo(20);
        assertThat(D.signalMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(D.reservationTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(D.checked(false)).isSameAs(D);
    }

    @Test
    void stricterValuesAreAcceptedInEveryProfile() {
        RealRiskLimits strict = new RealRiskLimits(500, 1000, 10, Duration.ofSeconds(30), Duration.ofMinutes(2));

        assertThat(strict.checked(false)).isSameAs(strict);
        assertThat(strict.checked(true)).isSameAs(strict);
    }

    @Test
    void aLooserOrderLimitIsRefusedOutsideTheTestProfilesNamingTheKeyOnly() {
        RealRiskLimits looser = new RealRiskLimits(1001, 2000, 20, D.signalMaxAge(), D.reservationTtl());

        assertThatThrownBy(() -> looser.checked(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("memetrade.real.risk.order-max-bps is looser than the default; only stricter values start")
                .hasMessageNotContaining("1001");
        assertThat(looser.checked(true)).isSameAs(looser);
    }

    @Test
    void aLooserDailyLossIsRefused() {
        assertThatThrownBy(
                        () -> new RealRiskLimits(1000, 2001, 20, D.signalMaxAge(), D.reservationTtl()).checked(false))
                .hasMessageContaining("daily-loss-max-bps");
    }

    @Test
    void moreBuysPerDayAreRefused() {
        assertThatThrownBy(
                        () -> new RealRiskLimits(1000, 2000, 21, D.signalMaxAge(), D.reservationTtl()).checked(false))
                .hasMessageContaining("daily-buys-max");
    }

    @Test
    void aLongerSignalAgeIsRefused() {
        assertThatThrownBy(() ->
                        new RealRiskLimits(1000, 2000, 20, Duration.ofSeconds(61), D.reservationTtl()).checked(false))
                .hasMessageContaining("signal-max-age");
    }

    @Test
    void aLongerReservationIsRefused() {
        assertThatThrownBy(() ->
                        new RealRiskLimits(1000, 2000, 20, D.signalMaxAge(), Duration.ofMinutes(6)).checked(false))
                .hasMessageContaining("reservation-ttl");
    }

    @Test
    void aValueBelowTheRangeIsRefusedInEveryProfile() {
        assertThatThrownBy(() -> new RealRiskLimits(0, 2000, 20, D.signalMaxAge(), D.reservationTtl()).checked(true))
                .hasMessage("memetrade.real.risk.order-max-bps is below its valid range");
        assertThatThrownBy(() -> new RealRiskLimits(1000, 2000, 0, D.signalMaxAge(), D.reservationTtl()).checked(true))
                .hasMessageContaining("daily-buys-max");
        assertThatThrownBy(() ->
                        new RealRiskLimits(1000, 2000, 20, Duration.ofSeconds(4), D.reservationTtl()).checked(true))
                .hasMessageContaining("signal-max-age");
        assertThatThrownBy(() ->
                        new RealRiskLimits(1000, 2000, 20, D.signalMaxAge(), Duration.ofSeconds(59)).checked(true))
                .hasMessageContaining("reservation-ttl");
    }

    @Test
    void theTestProfilesExtendTheRangeOnlyUpToTheTestMaximum() {
        RealRiskLimits wide = new RealRiskLimits(10_000, 10_000, 10_000, Duration.ofHours(24), Duration.ofHours(24));
        assertThat(wide.checked(true)).isSameAs(wide);

        assertThatThrownBy(
                        () -> new RealRiskLimits(10_001, 2000, 20, D.signalMaxAge(), D.reservationTtl()).checked(true))
                .hasMessageContaining("order-max-bps");
        assertThatThrownBy(() ->
                        new RealRiskLimits(1000, 2000, 20, Duration.ofHours(25), D.reservationTtl()).checked(true))
                .hasMessageContaining("signal-max-age");
    }

    @Test
    void aMissingDurationIsRefused() {
        assertThatThrownBy(() -> new RealRiskLimits(1000, 2000, 20, null, D.reservationTtl()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("signal-max-age");
    }
}
