package com.memetrade.riskcontrol.real.domain;

import com.memetrade.marketdata.api.MarketValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 261113-trading-real-bot U3 (BR5.2, BR2.4): the price of an open position in lamports per base unit of the token, or
 * unknown. Never zero for a missing value.
 *
 * <p>The market source quotes tokens in dollars per whole token; the barrier needs lamports per base unit. The quote of
 * the token is divided by the quote of SOL (the wrapped SOL mint, read in the same batch) and moved by the decimals of
 * both: {@code lamports per base unit = tokenUsd x 10^9 / (solUsd x 10^decimals)}, with 18 decimal places rounded DOWN
 * (the value of a position is never overstated). Any of the three inputs unknown, or a SOL quote that is not positive,
 * makes the price unknown; so do decimals that are not an integer from 0 to 18, and a positive quote that rounds to zero
 * at 18 places (a known zero is only a quote of exactly zero). The instant is the OLDER of the two quotes, so the 60 s of BR2.4 hold for both.
 */
public sealed interface LamportPrice permits LamportPrice.Known, LamportPrice.Unknown {

    /** Lamports per SOL. */
    int SOL_DECIMALS = 9;

    /** Decimal places kept by the conversion. */
    int SCALE = 18;

    /** The most decimals a mint has (the {@code token.decimals} check of MarketData). */
    int MAX_DECIMALS = 18;

    record Known(BigDecimal lamportsPerBaseUnit, Instant observedAt) implements LamportPrice {
        public Known {
            Objects.requireNonNull(lamportsPerBaseUnit, "lamportsPerBaseUnit");
            Objects.requireNonNull(observedAt, "observedAt");
            if (lamportsPerBaseUnit.signum() < 0) {
                throw new IllegalArgumentException("a price is never negative");
            }
        }
    }

    record Unknown() implements LamportPrice {}

    static LamportPrice from(MarketValue tokenUsd, MarketValue solUsd, MarketValue decimals) {
        if (!(tokenUsd instanceof MarketValue.Known token)
                || !(solUsd instanceof MarketValue.Known sol)
                || !(decimals instanceof MarketValue.Known places)
                || sol.value().signum() <= 0
                || token.value().signum() < 0) {
            return new Unknown();
        }
        OptionalInt tokenDecimals = decimals(places.value());
        if (tokenDecimals.isEmpty()) {
            return new Unknown();
        }
        BigDecimal perBaseUnit = token.value()
                .movePointRight(SOL_DECIMALS - tokenDecimals.getAsInt())
                .divide(sol.value(), SCALE, RoundingMode.DOWN);
        if (token.value().signum() > 0 && perBaseUnit.signum() == 0) {
            // Review R-03: a positive quote below the 18 places is unknown, never a known zero.
            return new Unknown();
        }
        Instant older = token.observedAt().isBefore(sol.observedAt()) ? token.observedAt() : sol.observedAt();
        return new Known(perBaseUnit, older);
    }

    /** Review R-03: the decimals of a mint are an integer from 0 to 18; anything else is unknown, never an error. */
    private static OptionalInt decimals(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0 || stripped.compareTo(BigDecimal.valueOf(MAX_DECIMALS)) > 0 || stripped.signum() < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(stripped.intValueExact());
    }
}
