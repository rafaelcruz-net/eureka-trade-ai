package com.memetrade.identity.api;

import java.util.EnumMap;
import java.util.Map;

/**
 * Identity counts of the system status (C17, BR1.6): numbers only, never an address or a person identifier.
 * {@code inviteCounts} always has the four keys PENDING, USED, EXPIRED and REVOKED, zero included.
 */
public record IdentityCounts(long guestCount, Map<InviteStatus, Long> inviteCounts) {

    public IdentityCounts {
        Map<InviteStatus, Long> complete = new EnumMap<>(InviteStatus.class);
        for (InviteStatus status : InviteStatus.values()) {
            complete.put(status, inviteCounts == null ? 0L : inviteCounts.getOrDefault(status, 0L));
        }
        inviteCounts = Map.copyOf(complete);
    }
}
