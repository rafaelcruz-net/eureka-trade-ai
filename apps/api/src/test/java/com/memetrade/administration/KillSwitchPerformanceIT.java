package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotRuleViolation;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.U5Bots;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 5 of U8, written BEFORE the kill switch code from the rules (NFR2.1, AC7.2.2, AC7.2.3, team.md "pausa do bot
 * impede novas operações"): with the REAL U5 and 60 ACTIVE bots (20 people with 3 bots each), the {@code PUT ON} that
 * pauses all of them answers with p95 of at most 5 s over 20 executions; while ON, a start is refused with
 * {@code PAUSED_BY_SYSTEM}.
 */
class KillSwitchPerformanceIT extends AbstractU8IT {

    private static final int PEOPLE = 20;
    private static final int BOTS_PER_PERSON = 3;
    private static final int EXECUTIONS = 20;
    private static final Duration P95_LIMIT = Duration.ofSeconds(5);

    @Test
    void switchingOnSixtyBotsAnswersWithinFiveSecondsAtP95() throws Exception {
        u5.useReal();
        List<Principal> owners = new ArrayList<>();
        List<UUID> bots = new ArrayList<>();
        for (int person = 0; person < PEOPLE; person++) {
            Principal owner = newPerson();
            owners.add(owner);
            for (int bot = 0; bot < BOTS_PER_PERSON; bot++) {
                bots.add(fixtures.active(owner, newToken()));
            }
        }
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        List<Duration> timings = new ArrayList<>();

        for (int execution = 0; execution < EXECUTIONS; execution++) {
            long started = System.nanoTime();
            MockHttpServletResponse on = putKillSwitch(admin, "ON", "Medição " + execution);
            timings.add(Duration.ofNanos(System.nanoTime() - started));
            assertThat(on.getStatus()).isEqualTo(200);
            JsonNode answer = body(on);
            assertThat(answer.get("pendingBots").asInt()).isZero();
            assertThat(answer.get("reconcileNeeded").asBoolean()).isFalse();
            for (int i = 0; i < bots.size(); i++) {
                assertThat(fixtures.bot(owners.get(i / BOTS_PER_PERSON), bots.get(i))
                                .status()
                                .has(PauseReason.SYSTEM))
                        .as("execution %d bot %d", execution, i)
                        .isTrue();
            }
            assertThat(putKillSwitch(admin, "OFF", "Fim da medição " + execution)
                            .getStatus())
                    .isEqualTo(200);
        }

        Duration p95 = timings.stream().sorted().toList().get((int) Math.ceil(EXECUTIONS * 0.95) - 1);
        org.slf4j.LoggerFactory.getLogger(KillSwitchPerformanceIT.class)
                .info(
                        "U8 PUT ON with 60 bots: p95Ms={}, maxMs={}",
                        p95.toMillis(),
                        timings.stream().max(Duration::compareTo).orElseThrow().toMillis());
        assertThat(p95).as("p95 of PUT ON with 60 bots: %s", timings).isLessThanOrEqualTo(P95_LIMIT);
    }

    /** AC7.2.3 (team.md, mandatory risk test): while the kill switch is ON, a start is refused. */
    @Test
    void whileOnAStartIsRefusedBySystemPause() throws Exception {
        u5.useReal();
        Principal owner = newPerson();
        UUID validated = fixtures.validated(owner, newToken());
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        assertThat(putKillSwitch(admin, "ON", "Bloqueio de inícios").getStatus())
                .isEqualTo(200);

        // The domain rule of U5 refuses it; the route maps it to 409 PAUSED_BY_SYSTEM (BotWebErrors).
        assertThatThrownBy(() -> fixtures.as(owner, () -> commands.start(owner, validated, U5Bots.key())))
                .isInstanceOfSatisfying(BotRuleViolation.class, refused -> assertThat(refused.code())
                        .isEqualTo(BotRuleViolation.Code.PAUSED_BY_SYSTEM));
        assertThat(ErrorCode.PAUSED_BY_SYSTEM.status()).isEqualTo(409);

        assertThat(putKillSwitch(admin, "OFF", "Fim do bloqueio").getStatus()).isEqualTo(200);
        assertThat(u5.real().systemPaused()).isFalse();
    }
}
