package com.memetrade.administration.infra;

import com.memetrade.shared.config.ProcessRole;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code adminKillSwitch} (observability-design.md, NFR9.4): {@code DOWN} with the code and the
 * duration while {@code reconcileNeeded} lasts more than 60 s or {@code pendingBots > 0} more than 2 minutes, as seen by
 * the reconciliation; {@code UP} otherwise. Outside the liveness and readiness groups, so the alert never takes the api
 * down. Details carry codes, durations and a flag only, never a person or the reason.
 */
@Component("adminKillSwitchHealthIndicator")
@ProcessRole(ProcessRole.Kind.API)
public class AdminKillSwitchHealthIndicator implements HealthIndicator {

    private final KillSwitchReconciler reconciler;

    public AdminKillSwitchHealthIndicator(KillSwitchReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public Health health() {
        return evaluate(reconciler.activeAlerts(), reconciler.lastRoundDiverged());
    }

    static Health evaluate(List<KillSwitchReconciler.Alert> alerts, boolean lastRoundDiverged) {
        Health.Builder builder = alerts.isEmpty() ? Health.up() : Health.down();
        if (!alerts.isEmpty()) {
            KillSwitchReconciler.Alert first = alerts.getFirst();
            builder.withDetail("code", first.code())
                    .withDetail("lastingSeconds", first.lasting().toSeconds());
            if (alerts.size() > 1) {
                builder.withDetail(
                        "otherCodes",
                        alerts.stream()
                                .skip(1)
                                .map(KillSwitchReconciler.Alert::code)
                                .toList());
            }
        }
        return builder.withDetail("lastRoundDiverged", lastRoundDiverged).build();
    }
}
