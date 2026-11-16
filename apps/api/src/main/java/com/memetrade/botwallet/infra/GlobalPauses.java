package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.api.RealKillSwitchState.KillSwitchView;
import com.memetrade.botwallet.domain.PauseReason;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (logical-components.md GlobalPauses, BR2.5, BR3.1, U5-NFR3.5): the live read of the global
 * REAL pauses, without cache: {@code KILL_SWITCH} from the one row of {@code real_kill_switch} (since its
 * {@code activated_at}), {@code RESTORE} and {@code RESTORE_CHECK_PENDING} from the open restore marker (since its
 * {@code detected_at} or {@code opened_at}). No row per person: a wallet created or released while a global pause is on
 * is paused at once.
 */
@Component
public class GlobalPauses {

    private final JdbcClient jdbc;

    public GlobalPauses(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The one row of the kill switch REAL (one read, about 1 ms). */
    public KillSwitchView killSwitch() {
        return jdbc.sql("select active, activated_at, activated_by, reason, version from real_kill_switch where"
                        + " switch_id = 1")
                .query((rs, row) -> new KillSwitchView(
                        rs.getBoolean("active"),
                        JdbcBotWalletRepository.instant(rs, "activated_at"),
                        rs.getObject("activated_by", UUID.class),
                        rs.getString("reason"),
                        rs.getLong("version")))
                .optional()
                .orElse(KillSwitchView.off(0));
    }

    /** The open restore marker, if any. */
    public Optional<OpenMarker> openRestoreMarker() {
        return jdbc.sql("select marker_id, phase, opened_at, detected_at, unknown_signatures from real_restore_marker"
                        + " where cleared_at is null")
                .query((rs, row) -> new OpenMarker(
                        rs.getObject("marker_id", UUID.class),
                        rs.getString("phase"),
                        JdbcBotWalletRepository.instant(rs, "opened_at"),
                        JdbcBotWalletRepository.instant(rs, "detected_at"),
                        rs.getInt("unknown_signatures")))
                .optional();
    }

    /** Every global pause active now, with its start. */
    public Map<PauseReason, Instant> active() {
        Map<PauseReason, Instant> pauses = new EnumMap<>(PauseReason.class);
        KillSwitchView killSwitch = killSwitch();
        if (killSwitch.active()) {
            pauses.put(PauseReason.KILL_SWITCH, killSwitch.activatedAt());
        }
        openRestoreMarker().ifPresent(marker -> {
            if (marker.restore()) {
                pauses.put(PauseReason.RESTORE, marker.detectedAt());
            } else {
                pauses.put(PauseReason.RESTORE_CHECK_PENDING, marker.openedAt());
            }
        });
        return pauses;
    }

    /** The restore marker still open: the check is pending, or a restore was found and waits for the administrator. */
    public record OpenMarker(UUID markerId, String phase, Instant openedAt, Instant detectedAt, int unknownSignatures) {

        public boolean restore() {
            return "RESTORE".equals(phase);
        }
    }
}
