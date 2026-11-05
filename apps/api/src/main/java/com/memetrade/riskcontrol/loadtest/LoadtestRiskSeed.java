package com.memetrade.riskcontrol.loadtest;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.riskcontrol.domain.DetailUnit;
import com.memetrade.riskcontrol.domain.EvaluationRecord;
import com.memetrade.riskcontrol.domain.EvaluationRecord.Outcome;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import com.memetrade.riskcontrol.domain.MarketInputs;
import com.memetrade.riskcontrol.domain.ReasonDetail;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import com.memetrade.riskcontrol.domain.RiskFigure;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the preparation volume of the scenario {@code u6} (cicd-pipeline.md),
 * written in batches of 1000 rows per transaction through the repositories of U6, in the same format the gate writes.
 * It is never measured; only the cycles go through the gate.
 *
 * <ul>
 *   <li>the first bot of every person receives {@code rejections-per-bot} rejected evaluations with their rejections,
 *       except the first person, whose first bot receives {@code heavy-bot-rejections} (19 x 10000 + 80000);
 *   <li>{@code old-approved} approved evaluations 32 to 40 days old, round-robin over every bot, for the purge.
 * </ul>
 *
 * Only synthetic identifiers and values; one or two reasons per rejection (about 1.5 details on average).
 */
@Component
@Profile("loadtest")
public class LoadtestRiskSeed {

    static final int BATCH = 1000;

    private static final SourceRef SOURCE = new SourceRef("sim-loadtest", "Carga simulada", true);

    private final EvaluationRepository evaluations;
    private final RejectionRepository rejections;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong rejectionsSeeded = new AtomicLong();
    private final AtomicLong oldApprovedSeeded = new AtomicLong();

    public LoadtestRiskSeed(
            EvaluationRepository evaluations,
            RejectionRepository rejections,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.evaluations = evaluations;
        this.rejections = rejections;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /** Seeds the whole preparation volume over the prepared bots of the ledger load. */
    public void seed(List<Bot> bots, long rejectionsPerBot, long heavyBotRejections, long oldApproved) {
        Map<Principal, Bot> firstBotOfEachPerson = new LinkedHashMap<>();
        bots.forEach(bot -> firstBotOfEachPerson.putIfAbsent(bot.owner(), bot));
        boolean heavy = true;
        for (Bot bot : firstBotOfEachPerson.values()) {
            seedRejections(bot, heavy ? heavyBotRejections : rejectionsPerBot);
            heavy = false;
        }
        seedOldApproved(bots, oldApproved);
    }

    public long rejectionsSeeded() {
        return rejectionsSeeded.get();
    }

    public long oldApprovedSeeded() {
        return oldApprovedSeeded.get();
    }

    private void seedRejections(Bot bot, long count) {
        Instant now = clock.instant();
        for (long done = 0; done < count; done += BATCH) {
            long size = Math.min(BATCH, count - done);
            List<EvaluationRecord> evaluationBatch = new ArrayList<>();
            List<RejectionRecord> rejectionBatch = new ArrayList<>();
            for (long i = 0; i < size; i++) {
                long index = done + i;
                Instant at = now.minus(Duration.ofSeconds(30 * (index + 1)));
                EvaluationRecord evaluation = evaluation(bot, Outcome.REJECTED, at);
                evaluationBatch.add(evaluation);
                rejectionBatch.add(rejection(evaluation, index % 2 == 0));
            }
            transactions.executeWithoutResult(status -> {
                evaluations.insertAll(evaluationBatch);
                rejections.insertAll(rejectionBatch);
            });
            rejectionsSeeded.addAndGet(size);
        }
    }

    private void seedOldApproved(List<Bot> bots, long count) {
        Instant now = clock.instant();
        for (long done = 0; done < count; done += BATCH) {
            long size = Math.min(BATCH, count - done);
            List<EvaluationRecord> batch = new ArrayList<>();
            for (long i = 0; i < size; i++) {
                long index = done + i;
                Bot bot = bots.get((int) (index % bots.size()));
                Duration age = Duration.ofDays(32 + index % 9).plusSeconds(index % 86_400);
                batch.add(evaluation(bot, Outcome.APPROVED, now.minus(age)));
            }
            transactions.executeWithoutResult(status -> evaluations.insertAll(batch));
            oldApprovedSeeded.addAndGet(size);
        }
    }

    private static EvaluationRecord evaluation(Bot bot, Outcome outcome, Instant at) {
        TokenId token = bot.tokens().getFirst();
        return new EvaluationRecord(
                UUID.randomUUID(),
                bot.owner().tenantId(),
                bot.owner().userId(),
                bot.botId(),
                UUID.randomUUID(),
                OrderSide.BUY,
                token.chain(),
                token.mint(),
                new BigDecimal("10.000000"),
                1,
                outcome,
                at);
    }

    private static RejectionRecord rejection(EvaluationRecord evaluation, boolean twoReasons) {
        List<ReasonDetail> details = new ArrayList<>();
        details.add(new ReasonDetail(RejectionReason.ORDER_SIZE, "10.000000", "0.000001", DetailUnit.USDC));
        if (twoReasons) {
            details.add(new ReasonDetail(RejectionReason.DAILY_LOSS, "50.000000", "50.000000", DetailUnit.USDC));
        }
        MarketInputs market = new MarketInputs(
                new MarketValue.Known(new BigDecimal("1.000000000000"), SOURCE, evaluation.evaluatedAt(), false),
                new MarketValue.Known(new BigDecimal("100000.000000"), SOURCE, evaluation.evaluatedAt(), false),
                RiskFigure.known(new BigDecimal("0.5100")),
                RiskFigure.known(new BigDecimal("10.000000")));
        return new RejectionRecord(
                UUID.randomUUID(),
                evaluation.tenantId(),
                evaluation.ownerUserId(),
                evaluation.botId(),
                evaluation.decisionId(),
                evaluation.evaluationId(),
                evaluation.side(),
                evaluation.chain(),
                evaluation.tokenMint(),
                evaluation.requestedQuantity(),
                details,
                twoReasons ? Set.of(PauseTrigger.DAILY_LOSS) : Set.of(),
                1,
                market,
                evaluation.evaluatedAt());
    }
}
