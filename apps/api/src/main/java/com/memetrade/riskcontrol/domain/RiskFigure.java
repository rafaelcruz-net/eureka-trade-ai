package com.memetrade.riskcontrol.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A figure the barrier computed or could not compute (BR3.2): {@link Known} with an exact decimal, or {@link Unknown}
 * when a market value it depends on is missing or stale. Never zero and never {@code null} in place of unknown.
 */
public sealed interface RiskFigure permits RiskFigure.Known, RiskFigure.Unknown {

    /** The text recorded and returned by C21: the decimal in its scale, or {@code UNKNOWN}. */
    String text();

    static RiskFigure known(BigDecimal value) {
        return new Known(value);
    }

    static RiskFigure unknown() {
        return Unknown.INSTANCE;
    }

    record Known(BigDecimal value) implements RiskFigure {
        public Known {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String text() {
            return value.toPlainString();
        }
    }

    final class Unknown implements RiskFigure {
        static final Unknown INSTANCE = new Unknown();

        private Unknown() {}

        @Override
        public String text() {
            return ReasonDetail.UNKNOWN;
        }

        @Override
        public String toString() {
            return ReasonDetail.UNKNOWN;
        }
    }
}
