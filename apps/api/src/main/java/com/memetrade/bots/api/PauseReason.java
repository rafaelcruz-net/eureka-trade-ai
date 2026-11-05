package com.memetrade.bots.api;

/**
 * Why a bot is paused (C4 {@code PauseReason} with the U5 additions {@link #ADMIN_RELEASED} and
 * {@link #WAITING_ACTIVE_SLOT}). Reasons accumulate; each one leaves only by its own rule (BR2.5) and the bot is ACTIVE
 * only without any reason.
 */
public enum PauseReason {
    /** Paused by the owner; leaves on resume. */
    PERSON,
    /** Daily loss trigger of U6; leaves at UTC midnight or on resume with a limit above the day loss (BR2.4, BR2.6). */
    DAILY_LOSS,
    /** Drawdown trigger of U6; leaves freely on resume (Q9). */
    DRAWDOWN,
    /** Kill switch on; becomes {@link #ADMIN_RELEASED} when it is switched off (BR7.1, BR7.2). */
    SYSTEM,
    /** "Paused by the administration": the kill switch was switched off; leaves on the owner's resume (BR7.2). */
    ADMIN_RELEASED,
    /** UTC midnight removed the daily loss but the person already had 3 active bots; leaves on resume (BR2.6). */
    WAITING_ACTIVE_SLOT
}
