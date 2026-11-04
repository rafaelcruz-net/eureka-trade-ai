package com.memetrade.pnl.application;

import com.memetrade.ledger.api.FillRecorded;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.MarkingSnapshot;
import com.memetrade.ledger.api.PaperAccountFunded;
import com.memetrade.pnl.domain.EquityCalculator;
import com.memetrade.pnl.infra.JdbcEquityPeakStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The equity peak (BR7.3, Q10). Listens SYNCHRONOUSLY, in the same transaction, to {@link PaperAccountFunded} (the
 * initial peak) and to {@link FillRecorded} (the account is already locked by the fill, so balance and positions are
 * of the same instant), and is used by the marking job, which locks the account first through
 * {@link LedgerReadModel#lockAccountForMarking}. An unknown price empties the last equity and keeps the peak; the peak
 * rises only by a conditional update. A failure here rolls the whole transaction back.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EquityPeakUpdater {

    private final LedgerReadModel ledger;
    private final PortfolioMarks marks;
    private final JdbcEquityPeakStore store;
    private final Clock clock;
    private final Duration jobLockTimeout;

    public EquityPeakUpdater(
            LedgerReadModel ledger,
            PortfolioMarks marks,
            JdbcEquityPeakStore store,
            Clock clock,
            @Value("${memetrade.ledger.job-lock-timeout}") Duration jobLockTimeout) {
        this.ledger = ledger;
        this.marks = marks;
        this.store = store;
        this.clock = clock;
        this.jobLockTimeout = jobLockTimeout;
    }

    @EventListener
    public void onFunded(PaperAccountFunded funded) {
        store.start(funded.tenantId(), funded.ownerUserId(), funded.botId(), funded.amount(), funded.fundedAt());
    }

    /** F2.11, F3.5, F4: recomputed with the account already locked by the fill or the close. */
    @EventListener
    public void onFill(FillRecorded fill) {
        store.record(fill.botId(), equityNow(fill.botId()), clock.instant());
    }

    /**
     * F5: locks the account ({@code lock_timeout} of the jobs), computes the equity from the balance and positions
     * read under the lock and records it.
     *
     * @return the equity, or empty when a price is not reliable
     */
    public Optional<BigDecimal> mark(UUID botId) {
        MarkingSnapshot snapshot = ledger.lockAccountForMarking(botId, jobLockTimeout);
        Optional<BigDecimal> equity =
                EquityCalculator.equity(snapshot.balance(), marks.openMarks(snapshot.positions()));
        store.record(botId, equity, clock.instant());
        return equity;
    }

    /** The equity now (balance plus net exit values), without writing; empty when a price is not reliable. */
    public Optional<BigDecimal> equityNow(UUID botId) {
        return EquityCalculator.equity(ledger.balance(botId), marks.openMarks(ledger.positions(botId)));
    }

    /** The recorded peak and last equity of a bot, if it was funded. */
    public Optional<JdbcEquityPeakStore.Peak> peak(UUID botId) {
        return store.find(botId);
    }
}
