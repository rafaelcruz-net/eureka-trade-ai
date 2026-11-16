package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.infra.GlobalPauses;
import com.memetrade.botwallet.infra.KillSwitchRows;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealFailureCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M7 (261113-trading-real-bot U5, F6, BR3.1, BR3.2, U5-NFR3.5, U5-NFR4.1): the kill switch REAL, owned by
 * {@code botwallet}. Switching it on is one short compare-and-set on the version with its audit event; from that commit
 * on, the live read of the M1 pauses every wallet. The unsent orders of everybody are cancelled afterwards by
 * {@link #cancelUnsentWhileActive()} (the job of every minute, and once right after the request), which converges: a
 * partial failure is done again in the next round. Sent orders go on, the withdrawal and the positions stay as they are,
 * and switching off restarts nothing.
 */
@Service
public class RealKillSwitchService implements RealKillSwitchState {

    private static final Logger log = LoggerFactory.getLogger(RealKillSwitchService.class);
    private static final int MAX_REASON = 500;
    private static final int ATTEMPTS = 3;

    private final GlobalPauses pauses;
    private final KillSwitchRows rows;
    private final RealAudit audit;
    private final RealExecutionPort execution;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RealKillSwitchService(
            GlobalPauses pauses,
            KillSwitchRows rows,
            RealAudit audit,
            RealExecutionPort execution,
            TransactionTemplate transactions,
            Clock clock) {
        this.pauses = pauses;
        this.rows = rows;
        this.audit = audit;
        this.execution = execution;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public KillSwitchView current() {
        return pauses.killSwitch();
    }

    @Override
    @Transactional
    public KillSwitchChange activate(UUID adminId, String reason) {
        String checked = reason(reason);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            KillSwitchView before = pauses.killSwitch();
            if (before.active()) {
                return new KillSwitchChange(before, false);
            }
            Instant now = clock.instant();
            if (rows.activate(before.version(), adminId, checked, now)) {
                audit.admin(
                        adminId,
                        Optional.empty(),
                        "REAL_KILL_SWITCH_ACTIVATED",
                        "real-kill-switch",
                        Map.of("reason", checked, "version", Long.toString(before.version() + 1)));
                log.info("REAL kill switch activated: version={}", before.version() + 1);
                return new KillSwitchChange(pauses.killSwitch(), true);
            }
        }
        return new KillSwitchChange(pauses.killSwitch(), false);
    }

    @Override
    @Transactional
    public KillSwitchChange deactivate(UUID adminId, String reason) {
        String checked = reason(reason);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            KillSwitchView before = pauses.killSwitch();
            if (!before.active()) {
                return new KillSwitchChange(before, false);
            }
            if (rows.deactivate(before.version(), adminId, checked, clock.instant())) {
                audit.admin(
                        adminId,
                        Optional.empty(),
                        "REAL_KILL_SWITCH_DEACTIVATED",
                        "real-kill-switch",
                        Map.of("reason", checked, "version", Long.toString(before.version() + 1)));
                log.info("REAL kill switch deactivated: version={}", before.version() + 1);
                return new KillSwitchChange(pauses.killSwitch(), true);
            }
        }
        return new KillSwitchChange(pauses.killSwitch(), false);
    }

    /**
     * BR3.1: while the switch is on, every PROPOSED and QUOTED order of everybody becomes CANCELLED (KILL_SWITCH) in one
     * transaction; SIGNED ones are cancelled by the recheck before the send. Returns how many were cancelled (zero with
     * the switch off).
     */
    @Override
    public int cancelUnsentWhileActive() {
        if (!pauses.killSwitch().active()) {
            return 0;
        }
        Integer cancelled =
                transactions.execute(status -> execution.cancelUnsent(CancelScope.all(), RealFailureCode.KILL_SWITCH));
        int count = cancelled == null ? 0 : cancelled;
        if (count > 0) {
            log.info("REAL kill switch cancelled {} unsent orders", count);
        }
        return count;
    }

    private static String reason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON) {
            throw RealAdminRefused.validation();
        }
        return trimmed;
    }
}
