package com.memetrade.bots.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 261121-paper-real-unico U4 (ENT-409; F2; BR1.5, BR1.6, BR1.8, BR2.3, BR4.2, NFR2.6): the inputs of the decision of a
 * REAL bot with the flag on, built from the mints of its bot wallet and its own holdings, in base units and lamports:
 *
 * <ul>
 *   <li>the exits are evaluated on the own AVAILABLE quantity of each mint, at
 *       {@code floor(ownCost x available / own)} lamports (the own average cost of the bot);
 *   <li>a mint of the wallet without an own available quantity, or every mint of the wallet when the read is not
 *       available, is {@code heldNotSellable}: it leaves the tokens of the request, so the engine evaluates it neither
 *       for an exit nor for a buy; its opening record is not closed;
 *   <li>the set that blocks a buy (exits plus heldNotSellable) stays the set of mints of the wallet, as today.
 * </ul>
 *
 * Every mint of the wallet has its note, so the reason a mint was not sold is recorded even when the engine ends with
 * NO_TOKENS or NO_TRADE.
 */
public record OwnDecisionInputs(
        List<OwnExit> ownPositions,
        Set<String> heldNotSellable,
        Map<String, OwnQuantityNote> notes,
        Optional<OwnQuantityReason> unavailableReason) {

    /** A position of the bot evaluated for an exit: its available base units at its own average cost in lamports. */
    public record OwnExit(String mint, long tokenBaseUnits, long costLamports) {

        public OwnExit {
            Objects.requireNonNull(mint, "mint");
            if (tokenBaseUnits < 1 || costLamports < 0) {
                throw new IllegalArgumentException("an exit has at least 1 base unit and a cost of at least 0");
            }
        }
    }

    public OwnDecisionInputs {
        ownPositions = List.copyOf(ownPositions);
        heldNotSellable = Collections.unmodifiableSet(new LinkedHashSet<>(heldNotSellable));
        notes = Collections.unmodifiableMap(new LinkedHashMap<>(notes));
        Objects.requireNonNull(unavailableReason, "unavailableReason");
    }

    /** The inputs for the mints the wallet holds ({@code walletMints}, the quantity-only path of the U2). */
    public static OwnDecisionInputs of(List<String> walletMints, OwnHoldings holdings) {
        List<OwnExit> exits = new ArrayList<>();
        Set<String> held = new LinkedHashSet<>();
        Map<String, OwnQuantityNote> notes = new LinkedHashMap<>();
        Optional<OwnQuantityReason> unavailable =
                switch (holdings) {
                    case OwnHoldings.Known known -> Optional.empty();
                    case OwnHoldings.Unavailable failed -> Optional.of(failed.reason());
                    case OwnHoldings.NotRead notRead -> Optional.of(OwnQuantityReason.READ_FAILED);
                };
        for (String mint : new LinkedHashSet<>(walletMints)) {
            if (unavailable.isPresent()) {
                held.add(mint);
                notes.put(mint, OwnQuantityNote.OWN_QUANTITY_UNAVAILABLE);
                continue;
            }
            Optional<OwnPosition> own = ((OwnHoldings.Known) holdings)
                    .positions().stream()
                            .filter(position -> position.mint().equals(mint))
                            .findFirst();
            if (own.isEmpty() || own.get().availableTokenBaseUnits() == 0) {
                held.add(mint);
                notes.put(mint, OwnQuantityNote.NO_OWN_QUANTITY);
                continue;
            }
            OwnPosition position = own.get();
            exits.add(new OwnExit(mint, position.availableTokenBaseUnits(), costOf(position)));
            notes.put(mint, OwnQuantityNote.OWN_QUANTITY_AVAILABLE);
        }
        return new OwnDecisionInputs(exits, held, notes, unavailable);
    }

    /** {@code floor(ownCost x available / own)} in lamports, exact (the three are at least 0, own at least 1). */
    static long costOf(OwnPosition position) {
        return BigInteger.valueOf(position.ownCostLamports())
                .multiply(BigInteger.valueOf(position.availableTokenBaseUnits()))
                .divide(BigInteger.valueOf(position.ownTokenBaseUnits()))
                .longValueExact();
    }

    /** The mints that left the request because the bot has nothing of its own available in them (BR1.5). */
    public List<String> noOwnQuantityMints() {
        List<String> mints = new ArrayList<>();
        notes.forEach((mint, note) -> {
            if (note == OwnQuantityNote.NO_OWN_QUANTITY) {
                mints.add(mint);
            }
        });
        return mints;
    }

    /** The tokens of the request of today without the mints of {@code heldNotSellable} (BR1.6). */
    public List<TokenId> requestTokens(List<TokenId> today) {
        return today.stream()
                .filter(token -> !heldNotSellable.contains(token.mint()))
                .toList();
    }
}
