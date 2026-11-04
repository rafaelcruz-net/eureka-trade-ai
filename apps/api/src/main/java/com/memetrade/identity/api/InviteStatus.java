package com.memetrade.identity.api;

/**
 * State of an invitation as shown to the administrator and to the guest (BR1.2). {@code PENDING}, {@code USED} and
 * {@code REVOKED} are stored; {@code EXPIRED} is never stored: it is how a pending invitation reads after its
 * {@code expiresAt}.
 */
public enum InviteStatus {
    PENDING,
    USED,
    EXPIRED,
    REVOKED
}
