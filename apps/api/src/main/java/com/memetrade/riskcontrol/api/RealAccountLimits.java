package com.memetrade.riskcontrol.api;

import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1, C9 (BR3.1 to BR3.3, ADR-008): the four REAL limits of the account of one person, read
 * only, from the SAME inputs the REAL barrier applies (the configuration {@code memetrade.real.risk.*} and the cap of
 * the ACTIVE bot wallet). Consumers: the route {@code GET /api/v1/real/account-limits} (C3) and the REAL branch of the
 * cycle, which sizes a buy by the order limit of the account (a REAL bot has no limits of its own, BR1.9).
 *
 * <p>Read only: it never reserves, changes a limit nor writes an audit event. It takes part in the transaction of the
 * caller. Tenant and person always come from the caller's authentication (or the bot row in the cycle).
 */
public interface RealAccountLimits {

    /**
     * @return empty when the person has no ACTIVE bot wallet (C3 answers {@code 409 REAL_NOT_ELIGIBLE} with
     *     {@code WALLET_INACTIVE})
     * @throws RealLimitsUnavailable when a read failed (C3 answers 503; the cycle buys nothing in that slot)
     */
    Optional<RealAccountLimitsView> read(UUID tenantId, UUID userId);
}
