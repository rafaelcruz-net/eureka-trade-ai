package com.memetrade.bots.domain;

/**
 * 261121-paper-real-unico U4 (ENT-408, BR1.5, BR2.3): the note of one mint of the wallet in the context of a REAL
 * decision with the flag on, so a NO_TOKENS or NO_TRADE of the engine never hides why a mint was not sold.
 */
public enum OwnQuantityNote {
    OWN_QUANTITY_AVAILABLE,
    NO_OWN_QUANTITY,
    OWN_QUANTITY_UNAVAILABLE
}
