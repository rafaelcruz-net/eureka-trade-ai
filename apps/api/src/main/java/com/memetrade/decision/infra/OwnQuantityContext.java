package com.memetrade.decision.infra;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U4 (ENT-408, alignment A10; observability-design.md "Contexto da decisão"): the note of the own
 * quantity of a REAL decision with the flag on, recorded as {@code context.ownQuantity}. Texts of enums only, so the
 * {@code decision} module depends neither on the bots nor on the P&L (NFR4.1). Internal: no route nor contract exposes
 * {@code decision.context} (review R-06).
 *
 * @param read {@code AVAILABLE}, {@code UNAVAILABLE} or {@code NOT_READ}
 * @param reason the reason of an unavailable read ({@code BUDGET_EXCEEDED}, {@code READ_FAILED}, {@code BOT_NOT_OWNED})
 * @param mints the note of each mint of the wallet ({@code OWN_QUANTITY_AVAILABLE}, {@code NO_OWN_QUANTITY},
 *     {@code OWN_QUANTITY_UNAVAILABLE})
 * @param cap the verdict of the cut of a SELL
 */
public record OwnQuantityContext(String read, Optional<String> reason, Map<String, String> mints, Cap cap) {

    public OwnQuantityContext {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(reason, "reason");
        mints = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mints));
        Objects.requireNonNull(cap, "cap");
    }

    /**
     * The verdict of the cut: {@code NONE} (no SELL, or a SELL sold as asked), {@code CAPPED}, {@code NO_OWN_QUANTITY},
     * {@code STALE_SNAPSHOT} or {@code UNAVAILABLE}, with the base units asked and sold when there was a SELL.
     */
    public record Cap(String outcome, Optional<Long> requestedBaseUnits, Optional<Long> sellBaseUnits) {

        public static final Cap NONE = new Cap("NONE", Optional.empty(), Optional.empty());

        public Cap {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(requestedBaseUnits, "requestedBaseUnits");
            Objects.requireNonNull(sellBaseUnits, "sellBaseUnits");
        }
    }
}
