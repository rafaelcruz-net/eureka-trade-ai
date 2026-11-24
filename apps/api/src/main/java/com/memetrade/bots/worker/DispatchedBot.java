package com.memetrade.bots.worker;

import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (BR2.5, alignment A15): one ACTIVE bot handed by the slot dispatcher to its cycle, with the
 * market read from its row, so the cycle of a PAPER bot never reads the flag of the U4 nor the own holdings.
 */
public record DispatchedBot(BotRef ref, BotMarket market) {

    public DispatchedBot {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(market, "market");
    }
}
