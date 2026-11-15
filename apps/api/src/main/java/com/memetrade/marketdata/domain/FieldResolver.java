package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * F2: reads one field of a token from the stored observations of every source (US2.3, US2.4).
 *
 * <ol>
 *   <li>Observations of FAILED sources never count (BR1.3); with only those, the field is
 *       {@code Unknown(SOURCE_UNAVAILABLE)}.
 *   <li>The displayed value is the most recent KNOWN one; a tie on the time goes to the smallest {@code sourceId}
 *       (BR1.2). Without any KNOWN value, the field is UNKNOWN with the reason of the most recent observation, or
 *       {@code NOT_YET_OBSERVED} without any (BR1.9). An informed 0 stays a KNOWN 0.
 *   <li>{@code stale} is computed now with the injectable clock: strictly above the limit of the field, 5 minutes
 *       for PRICE, LIQUIDITY and VOLUME_24H and 2 hours for HOLDERS and CREATED_AT (BR1.1, U2 BR1.4).
 *   <li>With two or more KNOWN, not stale values the divergence is checked with {@link DivergencePolicy} (BR1.7).
 * </ol>
 */
public final class FieldResolver {

    private static final Comparator<SourcedObservation> MOST_RECENT_FIRST = Comparator.comparing(
                    SourcedObservation::observedAt)
            .reversed()
            .thenComparing(o -> o.source().sourceId());

    private final DivergencePolicy divergence;

    public FieldResolver(DivergencePolicy divergence) {
        this.divergence = divergence;
    }

    /**
     * One stored observation of a field with the current state of its source.
     *
     * @param value the exact value when KNOWN, {@code null} when UNKNOWN
     * @param reason the reason when UNKNOWN, {@code null} when KNOWN
     */
    public record SourcedObservation(
            SourceRef source,
            SourceStatus.State sourceState,
            BigDecimal value,
            UnknownReason reason,
            Instant observedAt) {

        public SourcedObservation {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sourceState, "sourceState");
            Objects.requireNonNull(observedAt, "observedAt");
            if ((value == null) == (reason == null)) {
                throw new IllegalArgumentException("Exactly one of value and reason");
            }
        }

        boolean isKnown() {
            return value != null;
        }
    }

    public ResolvedField resolve(MarketField field, List<SourcedObservation> observations, Instant now) {
        List<SourcedObservation> available = observations.stream()
                .filter(o -> o.sourceState() != SourceStatus.State.FAILED)
                .sorted(MOST_RECENT_FIRST)
                .toList();
        if (available.isEmpty()) {
            UnknownReason reason =
                    observations.isEmpty() ? UnknownReason.NOT_YET_OBSERVED : UnknownReason.SOURCE_UNAVAILABLE;
            return ResolvedField.of(new MarketValue.Unknown(reason, Optional.empty()));
        }
        List<SourcedObservation> known =
                available.stream().filter(SourcedObservation::isKnown).toList();
        if (known.isEmpty()) {
            SourcedObservation latest = available.getFirst();
            return ResolvedField.of(new MarketValue.Unknown(latest.reason(), Optional.of(latest.source())));
        }
        List<MarketValue.Known> fresh = new ArrayList<>();
        for (SourcedObservation observation : known) {
            MarketValue.Known value = toKnown(field, observation, now);
            if (!value.stale()) {
                fresh.add(value);
            }
        }
        MarketValue.Known shown = toKnown(field, known.getFirst(), now);
        return new ResolvedField(shown, divergent(field, shown, fresh), fresh);
    }

    private boolean divergent(MarketField field, MarketValue.Known shown, List<MarketValue.Known> fresh) {
        if (shown.stale() || fresh.size() < 2) {
            return false;
        }
        return fresh.stream()
                .filter(other ->
                        !other.source().sourceId().equals(shown.source().sourceId()))
                .anyMatch(other -> divergence.diverges(field, shown.value(), other.value()));
    }

    /** {@code stale} by the limit of the field (U2 BR1.4: 5 min for prices, 2 h for HOLDERS and CREATED_AT). */
    private static MarketValue.Known toKnown(MarketField field, SourcedObservation observation, Instant now) {
        return new MarketValue.Known(
                observation.value(),
                observation.source(),
                observation.observedAt(),
                Freshness.isStale(field, observation.observedAt(), now));
    }
}
