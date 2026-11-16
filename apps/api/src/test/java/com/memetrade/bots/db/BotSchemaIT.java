package com.memetrade.bots.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.bots.domain.ParameterSpec;
import com.memetrade.bots.domain.StrategyTemplate;
import com.memetrade.bots.domain.StrategyTemplates;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5IT;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Passo 4 of U5, written BEFORE the migration {@code 0010-bots-decision} (US4.1, US4.4; BR1.1, BR3.2; NFR5.3): only
 * PAPER rows, the unique keys that make a slot run once and a version be recorded once, the v1 templates seeded with
 * the ranges of BR4.3 to BR4.6, the single row of the system pause and the global change sequence.
 */
class BotSchemaIT extends AbstractU5IT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aBotRowAcceptsOnlyThePaperMode() {
        Principal person = newPerson();
        insertOwner(person);

        assertThatThrownBy(() -> insertBot(person, UUID.randomUUID(), "REAL", "DRAFT", ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bot_mode_ck");
        UUID paper = UUID.randomUUID();
        insertBot(person, paper, "PAPER", "DRAFT", "");
        assertThat(jdbc.sql("select mode from bot where bot_id = :id")
                        .param("id", paper)
                        .query(String.class)
                        .single())
                .isEqualTo("PAPER");
    }

    @Test
    void aBotIsPausedIfAndOnlyIfItHasAPauseReason() {
        Principal person = newPerson();
        insertOwner(person);

        assertThatThrownBy(() -> insertBot(person, UUID.randomUUID(), "PAPER", "PAUSED", ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bot_paused_ck");
        assertThatThrownBy(() -> insertBot(person, UUID.randomUUID(), "PAPER", "ACTIVE", "PERSON"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bot_paused_ck");
        assertThatThrownBy(() -> insertBot(person, UUID.randomUUID(), "PAPER", "RUNNING", ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bot_state_ck");
        insertBot(person, UUID.randomUUID(), "PAPER", "PAUSED", "PERSON,DAILY_LOSS");
    }

    @Test
    void theOwnerRowIsUniquePerPersonAndRequiredByEveryBot() {
        Principal person = newPerson();
        insertOwner(person);

        assertThatThrownBy(() -> insertOwner(person)).isInstanceOf(DataIntegrityViolationException.class);
        Principal withoutOwnerRow = newPerson();
        assertThatThrownBy(() -> insertBot(withoutOwnerRow, UUID.randomUUID(), "PAPER", "DRAFT", ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSlotIsRecordedOncePerBot() {
        UUID bot = activeBot();
        Instant slot = Instant.parse("2026-10-28T12:00:00Z");
        insertCycle(bot, slot);

        assertThatThrownBy(() -> insertCycle(bot, slot)).isInstanceOf(DataIntegrityViolationException.class);
        insertCycle(bot, slot.plusSeconds(300));
    }

    @Test
    void aLimitsVersionIsUniquePerBotAndNeverChangesAfterItIsRecorded() {
        UUID bot = activeBot();
        insertLimits(bot, 1, "100.000000", "200.000000", "500.000000");

        assertThatThrownBy(() -> insertLimits(bot, 1, "100.000000", "200.000000", "500.000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update bot_limits_version set max_order_size = 1 where bot_id = :bot")
                        .param("bot", bot)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> insertLimits(bot, 2, "300.000000", "200.000000", "500.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bot_limits_coherent_ck");
    }

    @Test
    void theOpeningOfAPositionIsUniquePerBotAndToken() {
        UUID bot = activeBot();
        insertHolding(bot, "MintA");

        assertThatThrownBy(() -> insertHolding(bot, "MintA")).isInstanceOf(DataIntegrityViolationException.class);
        insertHolding(bot, "MintB");
    }

    @Test
    void theTwoTemplatesV1AreSeededWithTheRangesOfTheBusinessRules() throws Exception {
        for (StrategyTemplate expected : StrategyTemplates.ALL) {
            var row = jdbc.sql("select version, name, parameters::text as parameters from strategy_template"
                            + " where template_id = :id")
                    .param("id", expected.templateId().name())
                    .query()
                    .singleRow();
            assertThat(row.get("version")).isEqualTo("v1");
            assertThat(row.get("name")).isEqualTo(expected.name());
            JsonNode parameters = JSON.readTree((String) row.get("parameters"));
            assertThat(parameters).hasSize(expected.parameters().size());
            for (int i = 0; i < expected.parameters().size(); i++) {
                ParameterSpec spec = expected.parameters().get(i);
                JsonNode node = parameters.get(i);
                assertThat(node.get("key").asText()).isEqualTo(spec.key());
                assertThat(new BigDecimal(node.get("default").asText())).isEqualByComparingTo(spec.defaultValue());
                assertThat(new BigDecimal(node.get("min").asText())).isEqualByComparingTo(spec.min());
                assertThat(new BigDecimal(node.get("max").asText())).isEqualByComparingTo(spec.max());
                assertThat(new BigDecimal(node.get("step").asText())).isEqualByComparingTo(spec.step());
            }
        }
        assertThat(jdbc.sql("select count(*) from strategy_template")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    void theSystemPauseStateIsASingleRowOffByDefault() {
        assertThat(jdbc.sql("select count(*) from system_pause_state")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.sql("insert into system_pause_state (id, active, changed_at, correlation_id)"
                                + " values (2, false, now(), 'x')")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theChangeSequenceOnlyGrows() {
        long first =
                jdbc.sql("select nextval('bot_change_seq')").query(Long.class).single();
        long second =
                jdbc.sql("select nextval('bot_change_seq')").query(Long.class).single();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void aDecisionIsPaperOnePerCycleAndCarriesItsReason() {
        UUID bot = activeBot();
        UUID cycle = insertCycle(bot, Instant.parse("2026-10-28T12:05:00Z"));
        insertDecision(bot, cycle, "PAPER", "NO_TRADE", "NO_SIGNAL");

        assertThatThrownBy(() -> insertDecision(bot, cycle, "PAPER", "NO_TRADE", "NO_SIGNAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID other = insertCycle(bot, Instant.parse("2026-10-28T12:10:00Z"));
        // 261113-trading-real-bot U5 (BR7.1, migration 0021): a REAL bot records its decision as REAL; any other
        // mode is still refused.
        insertDecision(bot, other, "REAL", "NO_TRADE", "NO_SIGNAL");
        UUID third = insertCycle(bot, Instant.parse("2026-10-28T12:15:00Z"));
        assertThatThrownBy(() -> insertDecision(bot, third, "LIVE", "NO_TRADE", "NO_SIGNAL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("decision_mode_ck");
        UUID other2 = insertCycle(bot, Instant.parse("2026-10-28T12:20:00Z"));
        assertThatThrownBy(() -> insertDecision(bot, other2, "PAPER", "NO_TRADE", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("decision_reason_ck");
    }

    // ----- helpers (synthetic rows only; the application login, like the api and the worker) -----

    private UUID activeBot() {
        Principal person = newPerson();
        insertOwner(person);
        UUID bot = UUID.randomUUID();
        insertBot(person, bot, "PAPER", "ACTIVE", "");
        return bot;
    }

    private void insertOwner(Principal person) {
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
    }

    private void insertBot(Principal person, UUID bot, String mode, String state, String reasons) {
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, mode, name, template_id, template_version,
                                         state, pause_reasons, draft_step, created_at, updated_at)
                        values (:bot, :t, :u, :mode, 'Bot de teste', 'MOMENTUM_VOLUME', 'v1', :state, :reasons, 1,
                                now(), now())
                        """)
                .param("bot", bot)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("mode", mode)
                .param("state", state)
                .param("reasons", reasons)
                .update();
    }

    private UUID insertCycle(UUID bot, Instant slot) {
        UUID cycle = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               started_at, finished_at)
                        select :cycle, bot_id, tenant_id, owner_user_id, :slot, 'COMPLETED', :slot, :slot
                        from bot where bot_id = :bot
                        """)
                .param("cycle", cycle)
                .param("bot", bot)
                .param("slot", Timestamp.from(slot))
                .update();
        return cycle;
    }

    private void insertLimits(UUID bot, int version, String order, String token, String total) {
        jdbc.sql("""
                        insert into bot_limits_version (bot_id, version, max_order_size, max_token_exposure,
                            max_total_exposure, max_daily_loss, max_drawdown_pct, max_slippage_pct, min_liquidity,
                            min_interval_seconds, created_at, created_by)
                        select :bot, :version, :order, :token, :total, 50, 10, 2, 10000, 240, now(), owner_user_id
                        from bot where bot_id = :bot
                        """)
                .param("bot", bot)
                .param("version", version)
                .param("order", new BigDecimal(order))
                .param("token", new BigDecimal(token))
                .param("total", new BigDecimal(total))
                .update();
    }

    private void insertHolding(UUID bot, String mint) {
        jdbc.sql("insert into bot_holding (bot_id, chain, token_mint, opened_at) values (:bot, 'solana', :mint, now())")
                .param("bot", bot)
                .param("mint", mint)
                .update();
    }

    private void insertDecision(UUID bot, UUID cycle, String mode, String action, String reason) {
        jdbc.sql("""
                        insert into decision (decision_id, bot_id, cycle_id, tenant_id, owner_user_id, mode, action,
                            no_trade_reason, strategy_template_id, strategy_version, parameters, limits_version,
                            factors, inputs, context, summary, valid_until, created_at)
                        select :id, bot_id, :cycle, tenant_id, owner_user_id, :mode, :action, :reason,
                               'MOMENTUM_VOLUME', 'v1', '{}'::jsonb, 1, '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                               'Sem sinal', now(), now()
                        from bot where bot_id = :bot
                        """)
                .param("id", UUID.randomUUID())
                .param("bot", bot)
                .param("cycle", cycle)
                .param("mode", mode)
                .param("action", action)
                .param("reason", reason)
                .update();
    }
}
