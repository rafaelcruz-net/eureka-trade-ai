package com.memetrade.bots.api;

/**
 * 261121-paper-real-unico U4 (ENT-401, BR5.1, BR5.3, NFR3.6): the flag {@code memetrade.real.own-quantity-sell}. On,
 * the REAL cycle of a bot sells only the quantity the bot itself bought and still has available; off (the default in
 * the api and in the worker), the cycle is exactly today's. Read by the REAL cycle of the worker and by the page of the
 * bots of the api (C7); no other module reads it. Read once at the start of the process: changing it is a deployment of
 * the flag alone (no route, no writable actuator, no table).
 */
public interface OwnQuantitySell {

    boolean enabled();
}
