package com.memetrade.administration.domain;

import com.memetrade.bots.api.BotState;
import com.memetrade.identity.api.InviteStatus;
import com.memetrade.marketdata.api.SourceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The system status of the administrator (BR3.1 to BR3.3): counts and the state of the sources only, never an
 * identifier of a tenant, a person, a bot, a wallet, an order or an entry. Each part is a {@link Part}: a known value or
 * {@code UNKNOWN} with its reason; a missing count NEVER becomes zero.
 */
public record SystemStatus(
        Instant generatedAt,
        Part<Long> guestCount,
        Part<Map<InviteStatus, Long>> inviteCounts,
        Part<Map<BotState, Long>> botCountsByState,
        Part<KillSwitchSnapshot> killSwitch,
        Part<List<SourceStatus>> sources,
        Part<Long> auditOutboxPending,
        Part<Long> rejectedOrdersToday) {

    public SystemStatus {
        Objects.requireNonNull(generatedAt, "generatedAt");
    }

    /** Why a part is unknown (NFR10.4). */
    public enum UnknownReason {
        /** The part did not answer within its 500 ms. */
        TIMEOUT,
        /** The part failed. */
        UNAVAILABLE
    }

    /** A known value or an explicit unknown, never a default number. */
    public sealed interface Part<T> {

        record Known<T>(T value) implements Part<T> {
            public Known {
                Objects.requireNonNull(value, "value");
            }
        }

        record Unknown<T>(UnknownReason reason) implements Part<T> {
            public Unknown {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
