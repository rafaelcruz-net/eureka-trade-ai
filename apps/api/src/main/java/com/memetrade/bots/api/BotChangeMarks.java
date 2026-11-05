package com.memetrade.bots.api;

import com.memetrade.identity.api.Principal;

/**
 * C18, owned by U5: a number that only grows for the bots of one person. Every change of state, pause reason or limits
 * renews {@code bot.change_seq} from a global database sequence in the same write (BR8.1), so the mark is the maximum
 * of the person's bots and works for writes made by the api and by the worker alike. Reading it locks nothing.
 *
 * <p>Ordering caveat (registered by the plan): two transactions of the same person that commit out of sequence order
 * can make a reader see the larger value before the smaller one is visible; the effect is only a delayed screen
 * update, corrected by the next change or by the full refresh the live channel does every 60 seconds.
 */
public interface BotChangeMarks {

    long markFor(Principal principal);
}
