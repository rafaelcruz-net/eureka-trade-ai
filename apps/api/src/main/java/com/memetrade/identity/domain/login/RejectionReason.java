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
    UNKNOWN_ACCOUNT
}
