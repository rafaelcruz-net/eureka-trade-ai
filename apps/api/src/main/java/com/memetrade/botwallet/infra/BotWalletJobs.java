package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.DepositScanner;
import com.memetrade.botwallet.domain.WithdrawalProcessor;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jobs of the worker (reliability-design.md "Trabalhos"): the deposit scan every 15 s and the stuck challenges every
 * 10 s, each once across instances (ShedLock); the withdrawal follow-up every second WITHOUT ShedLock, since each
 * withdrawal is claimed with SKIP LOCKED. Each cycle has its own correlation id.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BotWalletJobs {

    private static final Logger log = LoggerFactory.getLogger(BotWalletJobs.class);
    static final Duration STUCK_AFTER = Duration.ofSeconds(30);

    private final DepositScanner deposits;
    private final WithdrawalProcessor withdrawals;
    private final BotWalletRepository repository;
    private final Clock clock;
    private final GlobalPauses pauses;

    public BotWalletJobs(
            DepositScanner deposits,
            WithdrawalProcessor withdrawals,
            BotWalletRepository repository,
            Clock clock,
            GlobalPauses pauses) {
        this.deposits = deposits;
        this.withdrawals = withdrawals;
        this.repository = repository;
        this.clock = clock;
        this.pauses = pauses;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.jobs.deposit-scan-interval}")
    @SchedulerLock(name = "realDepositScan", lockAtMostFor = "2m", lockAtLeastFor = "5s")
    public void scanDeposits() {
        // 261113-trading-real-bot U5 (BR2.5, reliability-design.md "Restauração"): while the restore check is pending
        // the
        // scan waits, so its cursor never passes a signature the check did not see yet.
        if (pauses.openRestoreMarker().filter(marker -> !marker.restore()).isPresent()) {
            return;
        }
        CorrelationId.runWithNew(() -> {
            int recorded = deposits.scan();
            if (recorded > 0) {
                log.info("REAL deposit scan recorded {} deposits", recorded);
            }
        });
    }

    @Scheduled(fixedDelayString = "${memetrade.real.jobs.withdrawal-interval}")
    public void followWithdrawals() {
        CorrelationId.runWithNew(withdrawals::processReady);
    }

    /** NFR4.7: a challenge IN_USE for more than 30 s goes back to PENDING (the TxSigner did not answer). */
    @Scheduled(fixedDelayString = "${memetrade.real.jobs.stuck-challenge-interval}")
    @SchedulerLock(name = "realStuckChallenges", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    @Transactional
    public void releaseStuckChallenges() {
        int released = repository.releaseStuckChallenges(clock.instant().minus(STUCK_AFTER));
        if (released > 0) {
            log.warn("Released {} consent challenges stuck IN_USE", released);
        }
    }
}
