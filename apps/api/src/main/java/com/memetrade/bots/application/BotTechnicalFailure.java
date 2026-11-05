package com.memetrade.bots.application;

/**
 * A technical failure of a command (BR2.2, BR2.7): U7 answered a conflict or a refusal that a valid command never gets.
 * The transaction rolls back and the screen offers "tentar de novo" (503); the message carries no data.
 */
public class BotTechnicalFailure extends RuntimeException {

    public BotTechnicalFailure(String message) {
        super(message);
    }
}
