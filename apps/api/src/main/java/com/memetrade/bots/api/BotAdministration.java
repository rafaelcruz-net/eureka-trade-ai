package com.memetrade.bots.api;

import com.memetrade.identity.api.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * C16, U5 for U8 and U9: the system pause, the counts and the dashboard summary. No method exposes the ledger, the
 * trades or a wallet (AC7.4.2, BR7.3).
 *
 * <ul>
 *   <li>{@link #applySystemPause} first records the kill switch ON in its own transaction (from then on start and
 *       resume are refused and the cycle skips), then adds {@link PauseReason#SYSTEM} to every ACTIVE and PAUSED bot in
 *       repeatable batches, each bot in its own transaction (BR7.1). A failure is returned, never hidden; U8 repeats
 *       until nothing fails.
 *   <li>{@link #liftSystemPause} records the kill switch OFF and swaps {@code SYSTEM} for
 *       {@link PauseReason#ADMIN_RELEASED}; no bot is resumed (BR7.2).
 * </ul>
 */
public interface BotAdministration {

    SystemPauseResult applySystemPause(String correlationId);

    SystemPauseResult liftSystemPause(String correlationId);

    /** Bots per state across every tenant, without any identifier (BR7.3). */
    Map<BotState, Long> countByState();

    /** The bots of ONE person: active and paused counts and the last decision (BR7.3). */
    BotDashboardSummary summaryFor(Principal principal);

    /** Whether the kill switch is on, as U5 recorded it. */
    boolean systemPaused();

    /**
     * @param changed bots that got (or lost) the reason in this call; a repeated call changes nothing
     * @param failed bots whose own transaction failed; U8 calls again until this is empty
     */
    record SystemPauseResult(boolean systemPaused, int changed, List<UUID> failed) {
        public SystemPauseResult {
            failed = List.copyOf(failed);
        }
    }

    /** The dashboard summary of one person (C16). */
    record BotDashboardSummary(long active, long paused, long activeLimit, Optional<LastDecision> lastDecision) {}

    /** The last decision of any bot of the person: what was decided, about which token, and when. */
    record LastDecision(UUID botId, String action, Optional<String> tokenMint, String summary, Instant createdAt) {}
}
