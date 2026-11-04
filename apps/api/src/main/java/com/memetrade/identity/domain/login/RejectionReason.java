package com.memetrade.identity.domain.login;

/** Why a login verification was refused (BR1.2 to BR1.5, BR1.13); recorded in LOGIN_REJECTED details. */
public enum RejectionReason {
    MALFORMED_MESSAGE,
    UNKNOWN_CHALLENGE,
    NONCE_ALREADY_USED,
    EXPIRED,
    MESSAGE_MISMATCH,
    WRONG_SIGNER,
    ADMIN_WALLET,
    UNKNOWN_ACCOUNT,
    // U3 verification with an invitation (BR3.2, in this order of the checks; BR3.7 also at the write).
    /** Unknown or malformed invitation code (BR3.2 check 1, NFR6.7). */
    INVITE_NOT_FOUND,
    /** The invitation reads EXPIRED (BR3.2 check 2). */
    INVITE_EXPIRED,
    /** The invitation is REVOKED (BR3.2 check 2, AC7.1.3). */
    INVITE_REVOKED,
    /** The invitation is USED (BR3.2 check 2, BR3.4). */
    INVITE_USED,
    /** The wallet already has an account (BR3.2 check 4, BR3.7). */
    WALLET_ALREADY_LINKED,
    /** The tenant of the invitation is not ACTIVE (BR3.2 check 5). */
    TENANT_INACTIVE,
    /** The current version of the terms was not accepted (BR3.1, BR3.2 check 6). */
    TERMS_NOT_ACCEPTED
}
