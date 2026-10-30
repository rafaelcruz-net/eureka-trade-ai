package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class ObservationValuesTest {

    private static final SourceRef SOURCE = new SourceRef("simulated", "Fonte simulada", true);
    private static final Instant OBSERVED = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void aKnownValueIsStaleOnlyWhenOlderThanExactlyFiveMinutes() {
        MarketValue atLimit = ObservationValues.fromStored(
                "KNOWN", BigDecimal.ONE, null, SOURCE, OBSERVED, OBSERVED.plus(Duration.ofMinutes(5)));
        MarketValue past = ObservationValues.fromStored(
                "KNOWN",
                BigDecimal.ONE,
                null,
                SOURCE,
                OBSERVED,
                OBSERVED.plus(Duration.ofMinutes(5)).plusSeconds(1));

        assertThat(((MarketValue.Known) atLimit).stale()).isFalse();
        assertThat(((MarketValue.Known) past).stale()).isTrue();
    }

    @Test
    void anUnknownRowKeepsItsReasonAndSourceAndNeverBecomesZero() {
        MarketValue value = ObservationValues.fromStored("UNKNOWN", null, "NOT_PROVIDED", SOURCE, OBSERVED, OBSERVED);

        assertThat(value).isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
        assertThat(value.isKnown()).isFalse();
    }

    @Test
    void aFieldWithoutObservationIsNotYetObserved() {
        assertThat(ObservationValues.notYetObserved())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
    }
}
