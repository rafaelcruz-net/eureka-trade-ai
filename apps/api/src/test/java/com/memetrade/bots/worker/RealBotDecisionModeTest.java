package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.decision.domain.StrategyId;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 11 of the U5 of 261113-trading-real-bot, written BEFORE the code (BR7.1, S25, pending item of the U4): the
 * decision of a REAL bot is recorded with {@code decision.mode = 'REAL'} (the CHECK of the migration 0021 accepts it);
 * a PAPER bot keeps {@code PAPER}; the old rows do not change and another value is still refused.
 */
@Tag("u5-real-operations")
class RealBotDecisionModeTest extends AbstractPostgresIT {

    @Autowired
    private JdbcDecisionRepository decisions;

    private record Cycle(UUID tenantId, UUID userId, UUID botId, UUID cycleId) {}

    private Cycle runningCycle(String market) {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now()) on conflict do"
                        + " nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,"
                        + " created_at, updated_at, market) values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1', 'DRAFT',"
                        + " now(), now(), :market)")
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("market", market)
                .update();
        jdbc.sql("insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status, started_at)"
                        + " values (:c, :b, :t, :u, :slot, 'RUNNING', :slot)")
                .param("c", cycleId)
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("slot", Timestamp.from(clock.instant()))
                .update();
        return new Cycle(person.tenantId(), person.userId(), botId, cycleId);
    }

    private UUID decide(Cycle cycle, String mode) {
        UUID decisionId = UUID.randomUUID();
        DecisionOutcome outcome = new DecisionOutcome(
                DecisionAction.NO_TRADE,
                Optional.empty(),
                Optional.of(NoTradeReason.NO_TOKENS),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                "Sem tokens na seleção.",
                clock.instant().plusSeconds(300));
        decisions.insert(new JdbcDecisionRepository.NewDecision(
                decisionId,
                cycle.cycleId(),
                cycle.botId(),
                cycle.tenantId(),
                cycle.userId(),
                StrategyId.MOMENTUM_VOLUME,
                "1",
                1,
                outcome,
                new DecisionJson.Recorded("{}", "{}", "{}", "[]"),
                clock.instant(),
                mode));
        return decisionId;
    }

    private String modeOf(UUID decisionId) {
        return jdbc.sql("select mode from decision where decision_id = :d")
                .param("d", decisionId)
                .query(String.class)
                .single();
    }

    /** S25: the cycle of a REAL bot records REAL. */
    @Test
    void theDecisionOfARealBotIsRecordedReal() {
        assertThat(modeOf(decide(runningCycle("REAL"), "REAL"))).isEqualTo("REAL");
    }

    @Test
    void theDecisionOfAPaperBotStaysPaper() {
        assertThat(modeOf(decide(runningCycle("PAPER"), "PAPER"))).isEqualTo("PAPER");
    }

    @Test
    void theRecordWithoutAModeIsPaperAsBeforeTheU5() {
        Cycle cycle = runningCycle("PAPER");
        UUID decisionId = UUID.randomUUID();

        decisions.insert(new JdbcDecisionRepository.NewDecision(
                decisionId,
                cycle.cycleId(),
                cycle.botId(),
                cycle.tenantId(),
                cycle.userId(),
                StrategyId.MOMENTUM_VOLUME,
                "1",
                1,
                new DecisionOutcome(
                        DecisionAction.NO_TRADE,
                        Optional.empty(),
                        Optional.of(NoTradeReason.NO_TOKENS),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        "Sem tokens.",
                        clock.instant().plusSeconds(300)),
                new DecisionJson.Recorded("{}", "{}", "{}", "[]"),
                clock.instant()));

        assertThat(modeOf(decisionId)).isEqualTo("PAPER");
    }

    @Test
    void anyOtherModeIsStillRefused() {
        assertThatThrownBy(() -> decide(runningCycle("PAPER"), "SIMULATED"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theModeOfTheCycleComesFromTheMarketOfTheBot() {
        assertThat(CycleRunner.decisionMode(true)).isEqualTo("REAL");
        assertThat(CycleRunner.decisionMode(false)).isEqualTo("PAPER");
    }
}
