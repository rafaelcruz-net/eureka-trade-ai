package com.memetrade.realexits.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * BR3.3: the current price in USD of the token of a position, known or unknown (project.md: never zero). A price of
 * zero or below is treated as unknown.
 */
public sealed interface CurrentPrice permits CurrentPrice.Known, CurrentPrice.Unknown {

    static CurrentPrice known(BigDecimal usd) {
        Objects.requireNonNull(usd, "usd");
        return usd.signum() > 0 ? new Known(usd) : new Unknown();
    }

    static CurrentPrice unknown() {
        return new Unknown();
    }

    boolean isKnown();

    record Known(BigDecimal usd) implements CurrentPrice {
        public Known {
            Objects.requireNonNull(usd, "usd");
        }

        @Override
        public boolean isKnown() {
            return true;
        }
    }

    record Unknown() implements CurrentPrice {
        @Override
        public boolean isKnown() {
            return false;
        }
    }
}
