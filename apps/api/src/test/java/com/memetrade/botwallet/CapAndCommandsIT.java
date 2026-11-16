package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.application.CapService;
import com.memetrade.botwallet.application.TxSignerCommandService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 9 of the U5, written BEFORE the code (F7, BR4.1, S18, S19, U5-NFR1.2, U5-NFR4.1): the administrator lowers the cap
 * of a person; the barrier sees it at once (through the M1), and the command LOWER_CAP is sent to the TxSigner again
 * every minute with the SAME commandId until it confirms. A command carries only ids and values, never an address.
 */
@Tag("u5-real-operations")
class CapAndCommandsIT extends AbstractRealOpsIT {

    private static final long SOL = 1_000_000_000L;

    @Autowired
    private CapService caps;

    @Autowired
    private TxSignerCommandService commands;

    @Autowired
    private RealStateQuery state;

    private Map<String, Object> command(OpsPerson person) {
        return jdbc.sql("select command_id, kind, payload::text as payload, attempts, confirmed_at from"
                        + " real_pending_txsigner_command where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .query()
                .singleRow();
    }

    /** S18: 10 SOL to 3 SOL: the cap is 3 SOL at once and LOWER_CAP is sent again until the TxSigner confirms. */
    @Test
    void aLowerCapHoldsAtOnceAndIsSentAgainUntilTheSignerConfirmsWithTheSameCommand() throws Exception {
        OpsPerson person = activePerson();
        UUID admin = UUID.randomUUID();

        var lowered = transactions.execute(status -> caps.lower(admin, person.userId(), 3 * SOL));

        assertThat(lowered.capLamports()).isEqualTo(3 * SOL);
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .capLamports())
                .isEqualTo(3 * SOL);
        Map<String, Object> pending = command(person);
        UUID commandId = (UUID) pending.get("command_id");
        assertThat(pending.get("kind")).isEqualTo("LOWER_CAP");
        assertThat((String) pending.get("payload"))
                .contains(person.botWalletId().toString())
                .contains("3000000000")
                .doesNotContain(person.botAddress())
                .doesNotContain(person.mainAddress());

        signer.down = true;
        commands.resendPending();
        assertThat(command(person).get("attempts")).isEqualTo(1);
        assertThat(command(person).get("confirmed_at")).isNull();

        signer.down = false;
        commands.resendPending();
        assertThat(command(person).get("confirmed_at")).isNotNull();
        assertThat(signer.lowered).containsEntry(commandId, 3 * SOL);

        commands.resendPending();
        assertThat(command(person).get("attempts")).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from audit_outbox where payload ->> 'action' = 'REAL_CAP_LOWERED' and"
                                + " payload ->> 'actor' = :a")
                        .param("a", admin.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** S19: with a cap of 3 SOL, 5 SOL (or 3 SOL) is refused and nothing is sent. */
    @Test
    void theAdministratorNeverRaisesTheCap() throws Exception {
        OpsPerson person = activePerson();
        transactions.execute(status -> caps.lower(UUID.randomUUID(), person.userId(), 3 * SOL));

        Throwable higher = catchThrowable(
                () -> transactions.execute(status -> caps.lower(UUID.randomUUID(), person.userId(), 5 * SOL)));
        Throwable equal = catchThrowable(
                () -> transactions.execute(status -> caps.lower(UUID.randomUUID(), person.userId(), 3 * SOL)));

        assertThat(higher).isInstanceOfSatisfying(RealAdminRefused.class, r -> {
            assertThat(r.code()).isEqualTo("CAP_ONLY_LOWERED");
            assertThat(r.status()).isEqualTo(422);
        });
        assertThat(equal).isInstanceOf(RealAdminRefused.class);
        assertThat(jdbc.sql("select count(*) from real_pending_txsigner_command where bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aPersonWithoutBotWalletHasNoCapToLower() throws Exception {
        Person person = signIn(true, false);

        assertThat(catchThrowable(
                        () -> transactions.execute(status -> caps.lower(UUID.randomUUID(), person.userId(), SOL))))
                .isInstanceOfSatisfying(
                        RealAdminRefused.class, r -> assertThat(r.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void theOtherPausesOfThePersonStayWhenTheCapIsLowered() throws Exception {
        OpsPerson person = activePerson();
        jdbc.sql("update real_preferences set paused_reasons = array['DAILY_LOSS'] where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .update();

        transactions.execute(status -> caps.lower(UUID.randomUUID(), person.userId(), SOL));

        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .pausedReasons())
                .extracting(Enum::name)
                .containsExactly("DAILY_LOSS");
    }
}
