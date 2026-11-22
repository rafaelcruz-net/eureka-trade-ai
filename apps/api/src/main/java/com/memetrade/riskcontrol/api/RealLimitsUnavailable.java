package com.memetrade.riskcontrol.api;

/**
 * 261121-paper-real-unico U1, C9 (BR3.3): a read behind the REAL limits of the account failed. The route C3 answers
 * {@code 503 SERVICE_UNAVAILABLE}; the cycle of a REAL bot sizes its buy at zero (no buy in that slot). The message
 * carries no data.
 */
public class RealLimitsUnavailable extends RuntimeException {

    public RealLimitsUnavailable(Throwable cause) {
        super("The REAL limits of the account could not be read", cause);
    }
}
