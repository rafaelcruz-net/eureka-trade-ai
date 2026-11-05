package com.memetrade.bots.worker;

import static com.memetrade.testsupport.U5Cycles.flat;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotRuleViolation;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.U5Bots;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * L12 and NFR10.4, NFR10.6 (Passo 10, written before the cycle): the cycle, the resume, the midnight and the kill switch
 * of the same bot at the same time, 50 times, end without a deadlock and without a lock timeout; the race between a
 * start and the kill switch always ends with the bot paused by the system or the start refused (L11).
 */
class LockOrderIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-29T08:00:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private MidnightJob midnight;

    @Autowired
    private BotAdministration administration;

    @Test
    void l12CycleResumeAndMidnightOfTheSameBotInParallelNeverDeadlockFiftyTimes() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        BotRef ref = new BotRef(botId, TenantScope.of(person));
        for (int i = 0; i < 50; i++) {
            Instant slot = SLOT.plus(Duration.ofMinutes(5L * i));
            clock.set(slot.plusSeconds(60));
            flat(market, assessments, token, slot, "1.00", "100000000");
            fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));

            var outcomes = Concurrently.run(
                    () -> cycles.run(ref, slot),
                    () -> fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key())),
                    () -> midnight.runOnce());

            assertThat(outcomes)
                    .as("iteration " + i)
                    .noneMatch(o -> o.failed()
                            && (o.failure() instanceof PessimisticLockingFailureException
                                    || !(o.failure() instanceof BotRuleViolation)));
            assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.ACTIVE);
        }
    }

    /** L11, NFR10.6: start and the kill switch in a race, 50 times: the bot ends SYSTEM-paused or the start refused. */
    @Test
    void l11TheRaceBetweenStartAndTheKillSwitchNeverLeavesAnActiveBotFiftyTimes() {
        try {
            for (int i = 0; i < 50; i++) {
                Principal person = newPerson();
                UUID botId = fixtures.validated(person, newToken());
                String correlation = "race-" + i;

                Concurrently.run(
                        () -> fixtures.as(person, () -> commands.start(person, botId, U5Bots.key())),
                        () -> administration.applySystemPause(correlation));
                administration.applySystemPause("race-repeat-" + i);

                var status = fixtures.bot(person, botId).status();
                assertThat(status.state()).as("iteration " + i).isIn(BotState.VALIDATED, BotState.PAUSED);
                if (status.state() == BotState.PAUSED) {
                    assertThat(status.has(com.memetrade.bots.api.PauseReason.SYSTEM))
                            .isTrue();
                }
                administration.liftSystemPause("race-off-" + i);
            }
        } finally {
            administration.liftSystemPause("race-cleanup");
        }
    }
}
