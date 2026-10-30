package com.memetrade.marketdata.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A market value is either {@link Known} (exact decimal, source, time, freshness) or {@link Unknown} (reason). Absence
 * is never zero and never {@code null} (BR3.1, project.md). Values are {@link BigDecimal}, never floating point.
 */
public sealed interface MarketValue permits MarketValue.Known, MarketValue.Unknown {

    boolean isKnown();

    record Known(BigDecimal value, SourceRef source, Instant observedAt, boolean stale) implements MarketValue {
        public Known {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(observedAt, "observedAt");
        }

        @Override
        public boolean isKnown() {
            return true;
        }
    }

    record Unknown(UnknownReason reason, Optional<SourceRef> source) implements MarketValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(source, "source");
        }

        public static Unknown notProvided(SourceRef source) {
            return new Unknown(UnknownReason.NOT_PROVIDED, Optional.of(source));
        }

        @Override
        public boolean isKnown() {
            return false;
        }
    }
}
