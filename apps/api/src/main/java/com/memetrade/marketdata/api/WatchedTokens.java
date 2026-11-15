package com.memetrade.marketdata.api;

import java.util.Set;

/**
 * U2 (BR1.1, entities.md WatchedTokens): the tokens other modules need priced even when the discovery no longer brings
 * them, kept in the active set of the real source. The default implementation is empty; U4 provides the REAL
 * positions behind this same interface.
 */
@FunctionalInterface
public interface WatchedTokens {

    /** The empty default of U2. */
    WatchedTokens NONE = Set::of;

    Set<TokenId> watched();
}
