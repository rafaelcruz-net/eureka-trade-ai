package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.OutboxEvents;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 of U5, written BEFORE the midnight job (US4.7; BR2.6; NFR10.5): at 00:00:00 UTC only the daily loss reason
 * leaves; the bot returns to ACTIVE when nothing else holds it and there is a slot; at 23:59:59 nothing has run yet. The
 * person lives in UTC-3 (21:00 in Brasilia is midnight UTC), which changes nothing: the rule is UTC.
 */
class MidnightIT extends AbstractU5WorkerIT {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    @Autowired
    private MidnightJob midnight;

    /** L3: paused only by DAILY_LOSS; at 23:59:59 UTC (20:59:59 in UTC-3) still paused; at 00:00:00 UTC ACTIVE. */
    @Test
    void l3ABotPausedOnlyByTheDailyLossReturnsAtMidnightUtc() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        forceReasons(botId, "DAILY_LOSS");
        Instant justBefore = Instant.parse("2026-10-28T23:59:59Z");
        clock.set(justBefore);
        assertThat(justBefore.atZone(BRASILIA).getHour()).isEqualTo(20);

        assertThat(midnight.isMidnight(justBefore)).isFalse();
        assertThat(fixtures.bot(person, botId).status())
                .isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.DAILY_LOSS));

        clock.set(Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(midnight.isMidnight(clock.instant())).isTrue();
        MidnightJob.Round round = midnight.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.ACTIVE));
        assertThat(OutboxEvents.count(jdbc, "BOT_DAILY_LOSS_CLEARED", "bot:" + botId))
                .isEqualTo(1);
        assertThat(OutboxEvents.count(jdbc, "BOT_RESUMED", "bot:" + botId)).isEqualTo(1);
    }

    /** L4: DAILY_LOSS and PERSON: only DAILY_LOSS leaves; the bot stays PAUSED by the person. */
    @Test
    void l4OnlyTheDailyLossLeaves() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        forceReasons(botId, "PERSON,DAILY_LOSS");

        midnight.runOnce();

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.PERSON));
        assertThat(OutboxEvents.count(jdbc, "BOT_RESUMED", "bot:" + botId)).isZero();
    }

    /** L5: with 3 other active bots, DAILY_LOSS leaves, WAITING_ACTIVE_SLOT enters and the bot stays PAUSED. */
    @Test
    void l5WithThreeActiveBotsTheBotWaitsForASlot() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        forceReasons(botId, "DAILY_LOSS");
        for (int i = 0; i < 3; i++) {
            fixtures.active(person, newToken());
        }

        midnight.runOnce();

        assertThat(fixtures.bot(person, botId).status())
                .isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.WAITING_ACTIVE_SLOT));
    }

    /** NFR10.5: a repeated round changes nothing and writes no second event. */
    @Test
    void aRepeatedRoundHasNoDoubleEffect() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        forceReasons(botId, "DAILY_LOSS,DRAWDOWN");

        midnight.runOnce();
        midnight.runOnce();

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.DRAWDOWN));
        assertThat(OutboxEvents.count(jdbc, "BOT_DAILY_LOSS_CLEARED", "bot:" + botId))
                .isEqualTo(1);
    }

    /** NFR4.3: two bots of a person with 2 active bots, both resumed at midnight: never 4 active. */
    @Test
    void theMidnightNeverMakesMoreThanThreeActiveBots() {
        Principal person = newPerson();
        UUID first = fixtures.active(person, newToken());
        UUID second = fixtures.active(person, newToken());
        forceReasons(first, "DAILY_LOSS");
        forceReasons(second, "DAILY_LOSS");
        fixtures.active(person, newToken());
        fixtures.active(person, newToken());

        midnight.runOnce();

        assertThat(bots.countActive(com.memetrade.bots.domain.TenantScope.of(person)))
                .isEqualTo(3);
    }

    private void forceReasons(UUID botId, String reasons) {
        jdbc.sql("update bot set state = 'PAUSED', pause_reasons = :reasons where bot_id = :bot")
                .param("reasons", reasons)
                .param("bot", botId)
                .update();
    }
}
