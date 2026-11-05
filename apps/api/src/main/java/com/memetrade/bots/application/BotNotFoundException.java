package com.memetrade.bots.application;

/** BR2.10, NFR5.1: the bot does not exist for this person (another person's, another tenant's or unknown): 404. */
public class BotNotFoundException extends RuntimeException {

    public BotNotFoundException() {
        super("Bot not found");
    }
}
