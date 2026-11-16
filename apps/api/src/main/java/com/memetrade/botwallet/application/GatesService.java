package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.domain.CiResult;
import com.memetrade.botwallet.domain.CiStatus;
import com.memetrade.botwallet.domain.GateEvaluator;
import com.memetrade.botwallet.domain.GateInputs;
import com.memetrade.botwallet.domain.GateOutcome;
import com.memetrade.botwallet.domain.RealRelease;
import com.memetrade.botwallet.domain.ReleaseState;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealOpsConfig;
import com.memetrade.botwallet.infra.RealOpsMetrics;
import com.memetrade.botwallet.infra.RealProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261113-trading-real-bot U5 (F1 steps 3 to 5, BR1.4, U5-NFR3.3, U5-NFR4.3): one run of the gates. Every release whose
 * 24 hours passed (QUARANTINE) and every BLOCKED one are checked against the same read of the CI of the commit of the
 * image (once per run, even for many releases), the kill switch, an open divergence of the person and the network.
 * Each change is a compare-and-set on the state with its audit event (the {@link com.memetrade.botwallet.domain
 * .GateSnapshot}) in one transaction; a release changed meanwhile (revoked) is left as it is.
 */
@Service
public class GatesService {

    private static final Logger log = LoggerFactory.getLogger(GatesService.class);

    private final ReleaseStore store;
    private final CiStatus ci;
    private final RealKillSwitchState killSwitch;
    private final RealAudit audit;
    private final RealOpsMetrics metrics;
    private final RealProperties properties;
    private final String buildSha;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GatesService(
            ReleaseStore store,
            CiStatus ci,
            RealKillSwitchState killSwitch,
            RealAudit audit,
            RealOpsMetrics metrics,
            RealProperties properties,
            RealOpsConfig.CheckedOps ops,
            TransactionTemplate transactions,
            Clock clock) {
        this.store = store;
        this.ci = ci;
        this.killSwitch = killSwitch;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
        this.buildSha = ops.buildSha();
        this.transactions = transactions;
        this.clock = clock;
    }

    /** One run; returns how many releases were checked. */
    public int run() {
        Instant now = clock.instant();
        List<RealRelease> due = store.awaitingGates(now);
        if (due.isEmpty()) {
            return 0;
        }
        CiResult ciResult = buildSha.isBlank() ? CiResult.unreachable() : ci.check(buildSha);
        boolean killSwitchOn = killSwitch.current().active();
        for (RealRelease release : due) {
            GateOutcome outcome = GateEvaluator.evaluate(new GateInputs(
                    release.network(),
                    properties.network(),
                    killSwitchOn,
                    store.hasOpenDivergence(release.userId()),
                    ciResult));
            transactions.executeWithoutResult(status -> apply(release, outcome, now));
        }
        return due.size();
    }

    private void apply(RealRelease release, GateOutcome outcome, Instant now) {
        boolean moved = store.moveGates(
                release.releaseId(),
                release.state(),
                outcome.state(),
                outcome.blockedReason().orElse(null),
                now);
        if (!moved) {
            return;
        }
        boolean newBlock = outcome.state() == ReleaseState.BLOCKED
                && (release.state() != ReleaseState.BLOCKED
                        || !Objects.equals(
                                release.blockedReason(), outcome.blockedReason().orElseThrow()));
        if (newBlock) {
            metrics.blocked(outcome.blockedReason().orElseThrow());
        }
        if (outcome.state() == release.state() && !newBlock) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>(outcome.snapshot().details());
        details.put("state", outcome.state().name());
        outcome.blockedReason().ifPresent(reason -> details.put("blockedReason", reason.name()));
        audit.global("REAL_RELEASE_GATES_CHECKED", "release:" + release.releaseId(), details);
        log.info(
                "REAL release gates: releaseId={}, state={}, blockedReason={}",
                release.releaseId(),
                outcome.state(),
                outcome.blockedReason().map(Enum::name).orElse("NONE"));
    }
}
