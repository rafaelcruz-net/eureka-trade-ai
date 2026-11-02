package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** F2 (BR1.1, BR1.2, BR1.3, BR1.7, BR1.9): displayed value, freshness and divergence between sources. */
@Tag("u4")
class FieldResolverTest {

    private static final SourceRef A = new SourceRef("sim-a", "A", true);
    private static final SourceRef B = new SourceRef("sim-b", "B", true);
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final FieldResolver resolver = new FieldResolver(DivergencePolicy.uniform(new BigDecimal("5.0000")));

    @Test
    void theMostRecentKnownValueIsShownAndATieGoesToTheSmallestSourceId() {
        ResolvedField newer = resolve(known(A, "1", 60), known(B, "2", 30));
        ResolvedField tie = resolve(known(B, "2", 30), known(A, "1", 30));

        assertThat(value(newer)).isEqualByComparingTo("2");
        assertThat(((MarketValue.Known) tie.value()).source()).isEqualTo(A);
    }

    @Test
    void staleIsStrictlyAboveFiveMinutes() {
        assertThat(((MarketValue.Known) resolve(known(A, "1", 300)).value()).stale())
                .isFalse();
        assertThat(((MarketValue.Known) resolve(known(A, "1", 301)).value()).stale())
                .isTrue();
    }

    @Test
    void anInformedZeroStaysKnownZeroAndAMissingValueIsUnknownWithItsReason() {
        assertThat(value(resolve(known(A, "0", 10)))).isEqualByComparingTo("0");
        assertThat(resolve(unknown(A, UnknownReason.NOT_PROVIDED, 10)).value())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(A)));
        assertThat(resolver.resolve(MarketField.PRICE, List.of(), NOW).value())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
    }

    @Test
    void aFailedSourceNeverCountsAndOnlyFailedSourcesGiveSourceUnavailable() {
        SourcedObservation failed = new SourcedObservation(B, State.FAILED, new BigDecimal("9"), null, NOW);

        assertThat(value(resolve(known(A, "1", 120), failed))).isEqualByComparingTo("1");
        assertThat(resolve(failed).value())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()));
    }

    @Test
    void aKnownValueOfOneSourceWinsOverAnUnknownOfTheOther() {
        assertThat(value(resolve(unknown(B, UnknownReason.NOT_PROVIDED, 0), known(A, "7", 30))))
                .isEqualByComparingTo("7");
    }

    @Test
    void exactlyFivePercentIsNotDivergentAndTheSmallestStepAboveIs() {
        assertThat(resolve(known(A, "100", 0), known(B, "105", 10)).divergent()).isFalse();
        assertThat(resolve(known(A, "100", 0), known(B, "105.0001", 10)).divergent())
                .isTrue();
        assertThat(resolve(known(A, "100", 0), known(B, "95", 10)).divergent()).isFalse();
        assertThat(resolve(known(A, "100", 0), known(B, "94.9999", 10)).divergent())
                .isTrue();
    }

    @Test
    void withAShownZeroAnyOtherNonZeroValueDiverges() {
        assertThat(resolve(known(A, "0", 0), known(B, "0", 10)).divergent()).isFalse();
        assertThat(resolve(known(A, "0", 0), known(B, "0.0001", 10)).divergent())
                .isTrue();
    }

    @Test
    void staleValuesAreLeftOutOfTheDivergenceAndTheOriginsKeepTheFreshOnes() {
        ResolvedField withStaleOther = resolve(known(A, "100", 0), known(B, "200", 301));
        ResolvedField divergent = resolve(known(A, "100", 0), known(B, "200", 10));

        assertThat(withStaleOther.divergent()).isFalse();
        assertThat(withStaleOther.origins()).hasSize(1);
        assertThat(divergent.otherOrigins())
                .extracting(MarketValue.Known::source)
                .containsExactly(B);
    }

    @Test
    void creationTimeIsNeverComparedForDivergence() {
        ResolvedField created = resolver.resolve(
                MarketField.CREATED_AT, List.of(known(A, "1700000000", 0), known(B, "1600000000", 10)), NOW);

        assertThat(created.divergent()).isFalse();
    }

    private ResolvedField resolve(SourcedObservation... observations) {
        return resolver.resolve(MarketField.PRICE, List.of(observations), NOW);
    }

    private static SourcedObservation known(SourceRef source, String value, long secondsAgo) {
        return new SourcedObservation(
                source, State.OK, new BigDecimal(value), null, NOW.minus(Duration.ofSeconds(secondsAgo)));
    }

    private static SourcedObservation unknown(SourceRef source, UnknownReason reason, long secondsAgo) {
        return new SourcedObservation(source, State.OK, null, reason, NOW.minus(Duration.ofSeconds(secondsAgo)));
    }

    private static BigDecimal value(ResolvedField field) {
        return ((MarketValue.Known) field.value()).value();
    }
}
