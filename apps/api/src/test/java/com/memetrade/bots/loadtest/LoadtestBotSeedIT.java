package com.memetrade.bots.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * U5 Passo 20 (cicd-pipeline.md, scenario u5): the bot seed of the loadtest profile writes the recorded decisions of
 * the first bot of each person, one completed cycle per past minute, and reports through its health component.
 */
class LoadtestBotSeedIT extends AbstractU5WorkerIT {

    @Autowired
    private JdbcCycleRepository cycles;

    @Autowired
    private JdbcDecisionRepository decisions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private LoadtestBotSeed seed(boolean enabled, int people, long decisionsPerBot) {
        return new LoadtestBotSeed(
                bots, cycles, decisions, jdbc, transactionManager, clock, enabled, people, 1, decisionsPerBot);
    }

    @Test
    void theDecisionsOfABotAreSeededAsCompletedCyclesOfPastMinutes() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());
        LoadtestBotSeed seed = seed(true, 1, 1_005);

        seed.seedDecisions(new BotRef(botId, TenantScope.of(owner)));

        assertThat(decisions.count(botId)).isEqualTo(1_005);
        var page = decisions.page(
                botId,
                TenantScope.of(owner).tenantId(),
                TenantScope.of(owner).ownerUserId(),
                java.util.Optional.empty(),
                2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).action()).isEqualTo("NO_TRADE");
        Instant newest = page.items().get(0).createdAt();
        assertThat(newest).isBefore(clock.instant());
        assertThat(Duration.between(page.items().get(1).createdAt(), newest)).isEqualTo(Duration.ofMinutes(1));
        Long completed = jdbc.sql("select count(*) from bot_cycle where bot_id = :bot and status = 'COMPLETED'")
                .param("bot", botId)
                .query(Long.class)
                .single();
        assertThat(completed).isEqualTo(1_005);
    }

    /**
     * Regression of Build and Test, round 1 (F3): the worker had already run a cycle of the bot in a minute the seed
     * would use; {@code tryStart} returned false and the seed wrote a decision pointing to a cycle that does not exist
     * (foreign key {@code decision.cycle_id}). The seed now only uses minutes before the oldest cycle of the bot.
     */
    @Test
    void aCycleTheWorkerAlreadyRanInASeededMinuteIsKeptAndNoDecisionPointsToAMissingCycle() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());
        BotRef ref = new BotRef(botId, TenantScope.of(owner));
        Instant workerSlot = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(3));
        Instant workerFinished = workerSlot.plusSeconds(2);
        UUID workerCycle = UUID.randomUUID();
        assertThat(cycles.tryStart(workerCycle, ref, workerSlot, workerSlot)).isTrue();
        cycles.finish(
                workerCycle,
                JdbcCycleRepository.Status.COMPLETED,
                Optional.of("NO_ORDER"),
                Optional.empty(),
                Optional.empty(),
                workerFinished);
        long cyclesBefore = cyclesOf(botId);

        seed(true, 1, 10).seedDecisions(ref);

        assertThat(decisions.count(botId)).isEqualTo(10);
        assertThat(cyclesOf(botId)).isEqualTo(cyclesBefore + 10);
        Long orphans = jdbc.sql("""
                        select count(*) from decision d
                        where d.bot_id = :bot
                          and not exists (select 1 from bot_cycle c
                                          where c.cycle_id = d.cycle_id and c.decision_id = d.decision_id)
                        """).param("bot", botId).query(Long.class).single();
        assertThat(orphans).isZero();
        Instant newestSeeded = jdbc.sql("select max(created_at) from decision where bot_id = :bot")
                .param("bot", botId)
                .query(Timestamp.class)
                .single()
                .toInstant();
        assertThat(newestSeeded).isBefore(workerSlot);
        Map<String, Object> worker = jdbc.sql(
                        "select status, decision_id, slot_start, finished_at from bot_cycle where cycle_id = :cycle")
                .param("cycle", workerCycle)
                .query()
                .singleRow();
        assertThat(worker.get("status")).isEqualTo("COMPLETED");
        assertThat(worker.get("decision_id")).isNull();
        assertThat(((Timestamp) worker.get("slot_start")).toInstant()).isEqualTo(workerSlot);
        assertThat(((Timestamp) worker.get("finished_at")).toInstant()).isEqualTo(workerFinished);
    }

    /**
     * Regression of Build and Test, round 1 (F3): a minute the seed chose that already has a cycle stops the seed with
     * a message naming the bot and the minute, and the batch of that minute is rolled back (nothing partial is kept).
     */
    @Test
    void aSlotTheSeedCannotClaimStopsTheSeedWithTheBotAndTheMinuteAndKeepsNothingOfThatBatch() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());
        BotRef ref = new BotRef(botId, TenantScope.of(owner));
        assertThat(cyclesOf(botId))
                .as("the bot has no cycle, so the seed starts at now - 1 min")
                .isZero();
        Instant refused = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(2));
        JdbcCycleRepository refusing = new JdbcCycleRepository(jdbc) {
            @Override
            public boolean tryStart(UUID cycleId, BotRef bot, Instant slotStart, Instant now) {
                return !slotStart.equals(refused) && super.tryStart(cycleId, bot, slotStart, now);
            }
        };
        LoadtestBotSeed seed =
                new LoadtestBotSeed(bots, refusing, decisions, jdbc, transactionManager, clock, true, 1, 1, 10);

        assertThatThrownBy(() -> seed.seedDecisions(ref))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(botId.toString())
                .hasMessageContaining(refused.toString());

        assertThat(decisions.count(botId)).isZero();
        assertThat(cyclesOf(botId)).isZero();
    }

    private long cyclesOf(UUID botId) {
        return jdbc.sql("select count(*) from bot_cycle where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    @Test
    void theFirstBotOfEachPersonIsTheOneWithTheLowestId() {
        TenantScope a = new TenantScope(UUID.randomUUID(), UUID.randomUUID());
        TenantScope b = new TenantScope(UUID.randomUUID(), UUID.randomUUID());
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 9);
        Map<TenantScope, BotRef> first = LoadtestBotSeed.firstBotOfEachPerson(
                List.of(new BotRef(high, a), new BotRef(low, a), new BotRef(high, b)));

        assertThat(first).hasSize(2);
        assertThat(first.get(a).botId()).isEqualTo(low);
        assertThat(first.get(b).botId()).isEqualTo(high);
    }

    @Test
    void theHealthComponentIsUpWhenDisabledAndWhenTheSeedIsReady() throws InterruptedException {
        LoadtestBotSeed disabled = seed(false, 1, 0);
        disabled.start();
        assertThat(disabled.health().getStatus()).isEqualTo(Status.UP);
        assertThat(disabled.health().getDetails()).containsEntry("state", "DISABLED");

        fixtures.active(newPerson(), newToken());
        LoadtestBotSeed enabled = seed(true, 1, 0);
        assertThat(enabled.health().getStatus()).isEqualTo(Status.UP);
        enabled.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (enabled.state() != LoadtestBotSeed.State.READY && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(enabled.state()).isEqualTo(LoadtestBotSeed.State.READY);
        assertThat(enabled.health().getStatus()).isEqualTo(Status.UP);
        assertThat(enabled.health().getDetails()).containsEntry("state", "READY");
    }

    @Test
    void invalidSizesStopTheStart() {
        assertThatThrownBy(() ->
                        new LoadtestBotSeed(bots, cycles, decisions, jdbc, transactionManager, clock, true, 1, 4, 0))
                .hasMessageContaining("bots-per-person");
        assertThatThrownBy(() -> seed(true, 0, 0)).hasMessageContaining("people");
    }
}
