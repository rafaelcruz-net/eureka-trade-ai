package com.memetrade.realexecution.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 261121-paper-real-unico U4 (C16 extended by D1, ENT-405, BR1.3): one sale of a bot that is not in the REAL ledger
 * yet, counted as open on the conservative side: the own available quantity of the bot is its own quantity minus its
 * open sales.
 *
 * @param source an order in progress or a decision still in the queue
 * @param sourceId the {@code realOrderId} of an ORDER, the {@code decisionRequestId} of a DECISION_REQUEST
 * @param mint the token sold
 * @param baseUnits the input base units of the order, or the token base units asked by the decision; at least 1
 */
public record OpenSell(OpenSellSource source, UUID sourceId, String mint, long baseUnits) {

    public OpenSell {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(mint, "mint");
        if (baseUnits < 1) {
            throw new IllegalArgumentException("an open sale sells at least 1 base unit");
        }
    }
}
