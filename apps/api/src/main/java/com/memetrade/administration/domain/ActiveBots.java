package com.memetrade.administration.domain;

/** How many bots are ACTIVE across every tenant (BR2.7), read from C16 without any identifier. */
@FunctionalInterface
public interface ActiveBots {

    long count();
}
