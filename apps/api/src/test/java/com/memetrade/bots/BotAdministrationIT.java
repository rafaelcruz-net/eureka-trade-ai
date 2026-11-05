package com.memetrade.bots;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotChangeMarks;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.U5Bots;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 12 of U5, written BEFORE BotAdministration and BotChangeMarks (US7.2; BR7.1 to BR7.3, BR8.1; C16, C18): the
 * system pause and its lifting are repeatable, the counts expose no identifier, the summary is only the person's, and
 * every change renews the person's mark.
 */
class BotAdministrationIT extends AbstractU5WorkerIT {

    @Autowired
    private BotAdministration administration;

    @Autowired
    private BotChangeMarks marks;

    @AfterEach
    void switchTheKillSwitchOff() {
        administration.liftSystemPause("after-each");
    }

    /** BR7.1, AC7.2.2: every ACTIVE and PAUSED bot gets SYSTEM; a repetition changes nothing. */
    @Test
    void theSystemPauseAddsSystemToEveryActiveAndPausedBotAndIsRepeatable() {
        Principal person = newPerson();
        UUID active = fixtures.active(person, newToken());
        UUID paused = fixtures.active(person, newToken());
        fixtures.as(person, () -> commands.pause(person, paused, U5Bots.key()));
        UUID draft = fixtures.draft(person);

        BotAdministration.SystemPauseResult first = administration.applySystemPause("kill-on");
        BotAdministration.SystemPauseResult second = administration.applySystemPause("kill-on-again");

        assertThat(first.systemPaused()).isTrue();
        assertThat(first.failed()).isEmpty();
        assertThat(administration.systemPaused()).isTrue();
        assertThat(fixtures.bot(person, active).status()).isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.SYSTEM));
        assertThat(fixtures.bot(person, paused).status())
                .isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.PERSON, PauseReason.SYSTEM));
        assertThat(fixtures.bot(person, draft).status().state()).isEqualTo(BotState.DRAFT);
        assertThat(second.changed()).isZero();
        assertThat(OutboxEvents.count(jdbc, "BOT_PAUSED", "bot:" + active)).isEqualTo(1);
    }

    /** BR7.2, AC7.2.4: lifting swaps SYSTEM for ADMIN_RELEASED and resumes nobody; repeated, nothing changes. */
    @Test
    void liftingTheSystemPauseReleasesWithoutResuming() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        administration.applySystemPause("kill-on");

        BotAdministration.SystemPauseResult lifted = administration.liftSystemPause("kill-off");
        BotAdministration.SystemPauseResult again = administration.liftSystemPause("kill-off-again");

        assertThat(lifted.systemPaused()).isFalse();
        assertThat(fixtures.bot(person, botId).status())
                .isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.ADMIN_RELEASED));
        assertThat(again.changed()).isZero();
        assertThat(OutboxEvents.count(jdbc, "BOT_SYSTEM_PAUSE_LIFTED", "bot:" + botId))
                .isEqualTo(1);
    }

    /** BR7.3: the counts are per state for every tenant, without any identifier. */
    @Test
    void theCountsArePerStateWithoutIdentifiers() {
        Principal person = newPerson();
        fixtures.active(person, newToken());
        fixtures.draft(person);

        Map<BotState, Long> counts = administration.countByState();

        assertThat(counts).containsKeys(BotState.values());
        assertThat(counts.get(BotState.ACTIVE)).isPositive();
        assertThat(counts.get(BotState.DRAFT)).isPositive();
    }

    /** BR7.3, C16: the summary of one person counts only that person's bots. */
    @Test
    void theSummaryIsOnlyThePersons() {
        Principal person = newPerson();
        fixtures.active(person, newToken());
        UUID paused = fixtures.active(person, newToken());
        fixtures.as(person, () -> commands.pause(person, paused, U5Bots.key()));
        fixtures.active(newPerson(), newToken());

        BotAdministration.BotDashboardSummary summary = administration.summaryFor(person);

        assertThat(summary.active()).isEqualTo(1);
        assertThat(summary.paused()).isEqualTo(1);
        assertThat(summary.activeLimit()).isEqualTo(3);
        assertThat(administration.summaryFor(newPerson()).active()).isZero();
    }

    /** BR8.1, C18: each change of state, reason or limits renews the mark of the person; it only grows. */
    @Test
    void everyChangeRenewsTheMarkOfThePerson() {
        Principal person = newPerson();
        long start = marks.markFor(person);
        UUID botId = fixtures.active(person, newToken());
        long afterStart = marks.markFor(person);
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        long afterPause = marks.markFor(person);
        fixtures.as(person, () -> commands.changeLimits(person, botId, U5Bots.wideLimits(), U5Bots.key()));
        long afterLimits = marks.markFor(person);

        assertThat(afterStart).isGreaterThan(start);
        assertThat(afterPause).isGreaterThan(afterStart);
        assertThat(afterLimits).isGreaterThan(afterPause);
        assertThat(marks.markFor(newPerson())).isZero();
    }
}
