package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.Concurrently;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 5 of U8, written BEFORE the kill switch code from the rules (BR2.8, NFR10.2, unit-test-instructions.md "Corrida
 * ON e OFF"): ON and OFF sent at the same time, 50 times, with the REAL U5. Each time, whatever order the two requests
 * take, U5 ends with the state U8 ends with, and the real bots follow it, without any reconciliation round.
 */
class KillSwitchRaceIT extends AbstractU8IT {

    private static final int REPETITIONS = 50;

    @Autowired
    private KillSwitchStore store;

    @Test
    void onAndOffAtTheSameTimeAlwaysLeaveU5AndU8Equal() {
        u5.useReal();
        List<UUID> bots = new ArrayList<>();
        List<Principal> owners = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Principal owner = newPerson();
            owners.add(owner);
            bots.add(fixtures.active(owner, newToken()));
        }
        AdminSessions.AdminSession first = sessionOf(newAdministrator());
        AdminSessions.AdminSession second = sessionOf(newAdministrator());

        for (int round = 0; round < REPETITIONS; round++) {
            String label = "corrida " + round;
            List<Concurrently.Outcome<MockHttpServletResponse>> answers = Concurrently.run(
                    () -> putKillSwitch(first, "ON", label), () -> putKillSwitch(second, "OFF", label));

            assertThat(answers).as("round %d", round).noneMatch(Concurrently.Outcome::failed);
            assertThat(answers)
                    .as("round %d", round)
                    .allMatch(answer -> answer.value().getStatus() == 200);
            KillSwitchSnapshot state = store.read();
            assertThat(u5.real().systemPaused())
                    .as("round %d", round)
                    .isEqualTo(state.state().paused());
            assertThat(state.reconcileNeeded()).as("round %d", round).isFalse();
            for (int i = 0; i < bots.size(); i++) {
                boolean system = fixtures.bot(owners.get(i), bots.get(i))
                        .status()
                        .reasons()
                        .contains(PauseReason.SYSTEM);
                assertThat(system)
                        .as("round %d bot %d", round, i)
                        .isEqualTo(state.state().paused());
            }
        }
    }
}
