package com.memetrade.identity.domain.realterms;

/**
 * Exception of the terms reacceptance for the REAL (contract-summary.md "Termos", team.md): without the acceptance of a
 * new version a person still reads the bot wallet ({@code GET /api/v1/real/bot-wallet}) and uses the withdrawal
 * routes, so they can always take their own money out. Exact method and path: no other REAL route is reachable.
 */
public final class RealTermsRoutes {

    static final String BOT_WALLET = "/api/v1/real/bot-wallet";
    static final String WITHDRAWALS = BOT_WALLET + "/withdrawals";

    private RealTermsRoutes() {}

    public static boolean isReachableWithoutNewAcceptance(String method, String path) {
        if (path == null) {
            return false;
        }
        if ("GET".equals(method) && path.equals(BOT_WALLET)) {
            return true;
        }
        return path.equals(WITHDRAWALS) || path.startsWith(WITHDRAWALS + "/");
    }
}
