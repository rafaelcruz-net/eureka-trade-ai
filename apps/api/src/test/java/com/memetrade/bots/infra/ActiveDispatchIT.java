package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U4, Etapa 14 (after the layer; BR2.5, NFR1.10, NFR3.1; alignment A15): the dispatch of the
 * slot hands each ACTIVE bot with its market and the scope of its own row; the checks P1 and P2 read the row of the bot
 * of its owner only (with its mark) and whether its slot is claimed.
 */
@Tag("u4-own-quantity-cycle")
class ActiveDispatchIT extends AbstractPostgresIT {

    @Autowired
    private JdbcBotRepository bots;

    @Test
    void theDispatchCarriesTheMarketAndTheScopeOfEachActiveBot() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID real = bot(person, "REAL", "ACTIVE");
        UUID paper = bot(person, "PAPER", "ACTIVE");
        UUID paused = bot(person, "REAL", "PAUSED");
        TenantScope scope = new TenantScope(person.tenantId(), person.userId());

        assertThat(bots.activeDispatch())
                .contains(
                        new JdbcBotRepository.ActiveBot(new BotRef(real, scope), BotMarket.REAL),
                        new JdbcBotRepository.ActiveBot(new BotRef(paper, scope), BotMarket.PAPER))
                .noneMatch(bot -> bot.ref().botId().equals(paused));
        assertThat(bots.activeDispatch().stream()
                        .map(JdbcBotRepository.ActiveBot::ref)
                        .toList())
                .containsExactlyElementsOf(bots.activeBots());
    }

    @Test
    void theCheckP1ReadsTheRowOfTheOwnerWithItsMarkAndNothingOfAnybodyElse() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        TestAccounts.Seeded other = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID real = bot(person, "REAL", "ACTIVE");
        long mark = jdbc.sql("select change_seq from bot where bot_id = :b")
                .param("b", real)
                .query(Long.class)
                .single();

        assertThat(bots.precheckRow(new BotRef(real, new TenantScope(person.tenantId(), person.userId()))))
                .contains(new JdbcBotRepository.PrecheckRow(BotState.ACTIVE, java.util.Set.of(), BotMarket.REAL, mark));
        assertThat(bots.precheckRow(new BotRef(real, new TenantScope(other.tenantId(), person.userId()))))
                .isEmpty();
        assertThat(bots.precheckRow(new BotRef(real, new TenantScope(person.tenantId(), other.userId()))))
                .isEmpty();
    }

    @Test
    void theCheckP2SeesAClaimedSlot() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID real = bot(person, "REAL", "ACTIVE");
        Instant slot = Instant.parse("2026-11-24T12:00:00Z");
        assertThat(bots.slotClaimed(real, slot)).isFalse();

        jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                          started_at)
                        values (gen_random_uuid(), :b, :t, :u, :slot, 'RUNNING', now())""")
                .param("b", real)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("slot", Timestamp.from(slot))
                .update();

        assertThat(bots.slotClaimed(real, slot)).isTrue();
        assertThat(bots.slotClaimed(real, slot.plusSeconds(300))).isFalse();
    }

    private UUID bot(TestAccounts.Seeded person, String market, String state) {
        UUID bot = UUID.randomUUID();
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          pause_reasons, created_at, updated_at, market)
                        values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1', :state, :reasons, now(), now(), :market)""")
                .param("b", bot)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("state", state)
                .param("reasons", "PAUSED".equals(state) ? "PERSON" : "")
                .param("market", market)
                .update();
        return bot;
    }
}
