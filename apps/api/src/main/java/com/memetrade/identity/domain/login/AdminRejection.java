package com.memetrade.identity.domain.login;

/**
 * Why the administrator entry refused (BR1.1, BR1.2, BR1.4; security-design.md Q4), recorded only in the
 * {@code LOGIN_REJECTED} event. The answer never tells them apart beyond 401, 403 and 429 (R-05 of the NFR Design).
 */
public enum AdminRejection {
    /** Unknown, malformed, reused, expired or out-of-scope challenge, or a message that is not the issued one: 401. */
    NONCE_INVALID,
    /** The signature does not verify for the wallet of the challenge: 401. */
    SIGNATURE_INVALID,
    /** The wallet is not (or no longer) in ADMIN_WALLET_ADDRESSES: the generic 403. */
    NOT_ADMIN_WALLET,
    /** The wallet already belongs to a guest account: the same generic 403; the account is never changed. */
    WALLET_IS_GUEST,
    /** Over 5 requests per minute per IP or per (address, IP): 429. */
    RATE_LIMITED;

    /** The two refusals answered with the same generic {@code 403 FORBIDDEN_ROLE}. */
    public boolean forbidden() {
        return this == NOT_ADMIN_WALLET || this == WALLET_IS_GUEST;
    }
}
