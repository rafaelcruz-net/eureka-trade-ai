package com.memetrade.ledger.api;

import com.memetrade.identity.api.Principal;

/**
 * C18, owned by U7: a number that only grows for the orders and entries of one person, changed by every relevant write
 * in any process. Derived from the append-only rows themselves (orders and entries are never deleted), so no extra
 * table and no extra write in the cycle are needed.
 */
public interface LedgerChangeMarks {

    long markFor(Principal principal);
}
