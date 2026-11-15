package com.memetrade.realexecution.domain;

import static com.memetrade.realexecution.api.RealOrderState.AMBIGUOUS;
import static com.memetrade.realexecution.api.RealOrderState.CANCELLED;
import static com.memetrade.realexecution.api.RealOrderState.CONFIRMED;
import static com.memetrade.realexecution.api.RealOrderState.FAILED;
import static com.memetrade.realexecution.api.RealOrderState.PROPOSED;
import static com.memetrade.realexecution.api.RealOrderState.QUOTED;
import static com.memetrade.realexecution.api.RealOrderState.SENT;
import static com.memetrade.realexecution.api.RealOrderState.SIGNED;

import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The state machine of a REAL order (functional-spec.md; BR2.2, BR3.1, BR3.2, BR4.2, BR5.1, BR5.2, BR7.1). Pure: the
 * caller passes the instants, the answers of the network and the settings. Every transition of the database is a
 * compare-and-set on the expected state (U4-NFR4.2); this class says which transitions exist and what the facts mean.
 *
 * <ul>
 *   <li>PROPOSED goes to QUOTED, FAILED or CANCELLED;
 *   <li>QUOTED quotes again once (AUTO_FULL only) and goes to SIGNED, FAILED or CANCELLED;
 *   <li>SIGNED goes to SENT or CANCELLED (the recheck before the send);
 *   <li>SENT goes to CONFIRMED, FAILED or AMBIGUOUS; it is never cancelled, because it may be on the network;
 *   <li>AMBIGUOUS leaves only through the network, to CONFIRMED or FAILED; it is never sent nor signed again;
 *   <li>CONFIRMED, FAILED and CANCELLED are final.
 * </ul>
 */
public final class RealOrderStateMachine {

    /** M6 {@code cancelUnsent} and the lowering of the mode cancel only these (BR7.1, F10). */
    public static final Set<RealOrderState> CANCEL_UNSENT = EnumSet.of(PROPOSED, QUOTED);

    /** The recheck before signing and before sending may cancel these (BR3.3). */
    public static final Set<RealOrderState> RECHECK_CANCELLABLE = EnumSet.of(PROPOSED, QUOTED, SIGNED);

    private static final Map<RealOrderState, Set<RealOrderState>> EDGES = new EnumMap<>(RealOrderState.class);

    static {
        EDGES.put(PROPOSED, EnumSet.of(QUOTED, FAILED, CANCELLED));
        EDGES.put(QUOTED, EnumSet.of(QUOTED, SIGNED, FAILED, CANCELLED));
        EDGES.put(SIGNED, EnumSet.of(SENT, CANCELLED));
        EDGES.put(SENT, EnumSet.of(CONFIRMED, FAILED, AMBIGUOUS));
        EDGES.put(AMBIGUOUS, EnumSet.of(CONFIRMED, FAILED));
        EDGES.put(CONFIRMED, EnumSet.noneOf(RealOrderState.class));
        EDGES.put(FAILED, EnumSet.noneOf(RealOrderState.class));
        EDGES.put(CANCELLED, EnumSet.noneOf(RealOrderState.class));
    }

    private RealOrderStateMachine() {}

    public static boolean canMove(RealOrderState from, RealOrderState to) {
        return EDGES.get(from).contains(to);
    }

    /** @throws IllegalStateException for a transition outside the state machine */
    public static void requireMove(RealOrderState from, RealOrderState to) {
        if (!canMove(from, to)) {
            throw new IllegalStateException("A REAL order never moves from " + from + " to " + to);
        }
    }

    /** BR5.3, BR6.1: the RESERVE of a buy is released only when the order reaches a final state. */
    public static boolean releasesReserve(RealSide side, RealOrderState to) {
        return side == RealSide.BUY && to.isFinal();
    }

    /** U4-NFR4.1: the TxSigner is asked only for a QUOTED order (the same request is repeated, never a new one). */
    public static boolean mayAskSignature(RealOrderState state) {
        return state == QUOTED;
    }

    /**
     * U4-NFR4.1: the recorded message changes only in the one re-quote of AUTO_FULL, and only while the TxSigner was
     * never asked to sign it.
     */
    public static boolean mayRebuildMessage(RealOrderState state, Optional<Instant> signingRequestedAt) {
        return state == QUOTED && signingRequestedAt.isEmpty();
    }

    /** BR3.1, BR5.1: a SENT order is resent with the SAME transaction only while its blockhash is valid. */
    public static boolean mayResend(RealOrderState state, long blockHeight, long lastValidBlockHeight) {
        return state == SENT && blockHeight <= lastValidBlockHeight;
    }

    /** What the driver does with a quote before asking the TxSigner (BR3.2, S2). */
    public enum SigningStep {
        SIGN,
        REQUOTE,
        FAIL_QUOTE_EXPIRED
    }

    /** BR3.2: older than {@code maxAge}: quote once more; stale again: FAILED QUOTE_EXPIRED. */
    public static SigningStep beforeSigning(Instant quoteAt, Instant now, boolean requoted, Duration maxAge) {
        if (QuoteMath.fresh(quoteAt, now, maxAge)) {
            return SigningStep.SIGN;
        }
        return requoted ? SigningStep.FAIL_QUOTE_EXPIRED : SigningStep.REQUOTE;
    }

    /** What the signing deadline job does with a QUOTED order of the SIGNED mode (BR4.2). */
    public enum DeadlineVerdict {
        NONE,
        CANCEL_DEADLINE,
        RETRY_SIGNING,
        FAIL_QUOTE_EXPIRED
    }

    /**
     * BR4.2: an unclaimed session past its deadline is cancelled (S8); a claimed one is never cancelled by the deadline
     * (S25). A claim still QUOTED {@code claimStuck} after it is repeated with the same request, or fails when the quote
     * is older than {@code maxQuoteAge} (S29).
     */
    public static DeadlineVerdict onSigningDeadline(
            Instant now,
            Instant deadline,
            Optional<Instant> claimedAt,
            Instant quoteAt,
            Duration claimStuck,
            Duration maxQuoteAge) {
        if (claimedAt.isEmpty()) {
            return now.isBefore(deadline) ? DeadlineVerdict.NONE : DeadlineVerdict.CANCEL_DEADLINE;
        }
        if (Duration.between(claimedAt.get(), now).compareTo(claimStuck) < 0) {
            return DeadlineVerdict.NONE;
        }
        return QuoteMath.fresh(quoteAt, now, maxQuoteAge)
                ? DeadlineVerdict.RETRY_SIGNING
                : DeadlineVerdict.FAIL_QUOTE_EXPIRED;
    }

    /** What the network says of a signature, for the state machine. */
    public enum ChainStatus {
        FINALIZED,
        FAILED,
        FAILED_SLIPPAGE,
        PENDING,
        NOT_FOUND,
        UNAVAILABLE
    }

    /** The verdict on an order on the network: stay, resend the same transaction, or move with a code. */
    public record NetworkVerdict(Kind kind, RealOrderState to, RealFailureCode code) {

        public enum Kind {
            STAY,
            RESEND,
            MOVE
        }

        public static NetworkVerdict stay() {
            return new NetworkVerdict(Kind.STAY, null, null);
        }

        public static NetworkVerdict resend() {
            return new NetworkVerdict(Kind.RESEND, null, null);
        }

        public static NetworkVerdict move(RealOrderState to, RealFailureCode code) {
            return new NetworkVerdict(Kind.MOVE, to, code);
        }
    }

    /**
     * BR5.1 (SENT) and BR5.2 (AMBIGUOUS). SENT: finalized is CONFIRMED; failed is FAILED (SLIPPAGE_EXCEEDED or
     * CHAIN_REJECTED); absent past its blockhash is FAILED BLOCKHASH_EXPIRED (S24); {@code timeout} after the send
     * without a final answer is AMBIGUOUS (S11); absent with a valid blockhash is resent with the same transaction.
     * AMBIGUOUS: only the network decides, and with the RPC down it stays (S13); it is never resent (S14).
     */
    public static NetworkVerdict onNetwork(
            RealOrderState state,
            ChainStatus status,
            Duration sinceSend,
            long blockHeight,
            long lastValidBlockHeight,
            Duration timeout) {
        if (state != SENT && state != AMBIGUOUS) {
            throw new IllegalArgumentException("Only a SENT or AMBIGUOUS order is on the network, not " + state);
        }
        switch (status) {
            case FINALIZED -> {
                return NetworkVerdict.move(CONFIRMED, null);
            }
            case FAILED_SLIPPAGE -> {
                return NetworkVerdict.move(FAILED, RealFailureCode.SLIPPAGE_EXCEEDED);
            }
            case FAILED -> {
                return NetworkVerdict.move(FAILED, RealFailureCode.CHAIN_REJECTED);
            }
            default -> {
                // decided below
            }
        }
        boolean expired = status == ChainStatus.NOT_FOUND && blockHeight > lastValidBlockHeight;
        if (expired) {
            return NetworkVerdict.move(FAILED, RealFailureCode.BLOCKHASH_EXPIRED);
        }
        if (state == AMBIGUOUS) {
            return NetworkVerdict.stay();
        }
        if (sinceSend.compareTo(timeout) >= 0) {
            return NetworkVerdict.move(AMBIGUOUS, null);
        }
        return status == ChainStatus.NOT_FOUND ? NetworkVerdict.resend() : NetworkVerdict.stay();
    }
}
