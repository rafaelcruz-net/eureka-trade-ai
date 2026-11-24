package com.memetrade.pnl.api;

import java.util.Optional;
import java.util.UUID;

/**
 * C10 (owner U2; first implementation 261121-paper-real-unico U1): the P&L REAL by bot. Tenant and person always come
 * from the authentication of the caller; read only, no write lock, never blocks the execution of orders. Arithmetic in
 * {@code long} with {@code Math.*Exact} and fractions in {@code BigInteger}; no floating point. Its types are on the
 * closed REAL list of the rule NEVER_SUM_MODES. The U3 adds {@code botLines} (C5) additively.
 */
public interface RealBotPnlQuery {

    /**
     * The lines by bot, the line "Sem bot" (empty in the U1) and the REAL total of the mode in the window.
     *
     * @throws RealPnlUnavailable when a read failed (the REAL block becomes UNAVAILABLE)
     */
    RealModePnl modePnl(UUID tenantId, UUID userId, PnlWindow window);

    /** Empty when the bot is not a REAL bot of the person (the caller answers 404). */
    Optional<RealBotPnlLine> botPnl(UUID tenantId, UUID userId, UUID botId, PnlWindow window);

    /**
     * 261121-paper-real-unico U3 (C5, BR4.2, BR4.3, BR4.6): one page of the lines that make the P&L of a REAL bot since
     * the beginning, from the SAME pass as {@link #modePnl} (no calculation of its own), newest first in network order.
     * The page starts after the line of the {@code cursor} (by its identity, never by a position).
     *
     * @param limit 1 to 100
     * @return empty when the bot is not a REAL bot of the person (the caller answers 404)
     * @throws RealPnlLineCursorRejected when the cursor is malformed, of another bot, or its anchor is not found
     * @throws RealPnlUnavailable when the pass failed (the caller answers 503)
     */
    Optional<RealPnlLinePage> botLines(UUID tenantId, UUID userId, UUID botId, Optional<String> cursor, int limit);
}
