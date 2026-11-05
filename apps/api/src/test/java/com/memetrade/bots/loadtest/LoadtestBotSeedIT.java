package com.memetrade.bots.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                bots, cycles, decisions, transactionManager, clock, enabled, people, 1, decisionsPerBot);
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
        assertThatThrownBy(() -> new LoadtestBotSeed(bots, cycles, decisions, transactionManager, clock, true, 1, 4, 0))
                .hasMessageContaining("bots-per-person");
        assertThatThrownBy(() -> seed(true, 0, 0)).hasMessageContaining("people");
    }
}
