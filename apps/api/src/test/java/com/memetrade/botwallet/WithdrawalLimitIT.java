package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 7 of the U5, written BEFORE the code (BR2.4, S15, U5-NFR4.5): the withdrawal route of the U1 with a divergence
 * open: the amount is limited by the SOL measured on the network, a larger one is refused with
 * {@code WITHDRAWAL_LIMITED_BY_DIVERGENCE}, and the withdrawal stays available with the kill switch on and without any
 * release (the REAL switched off keeps it too, RealOffByDefaultRoutesIT of the U1).
 */
@Tag("u5-real-operations")
class WithdrawalLimitIT extends AbstractRealOpsIT {

    private static final long SOL = 1_000_000_000L;

    /** A divergence OPEN measured by a run that read {@code chainLamports} on the network. */
    private void divergence(OpsPerson person, long chainLamports, long ledgerLamports) {
        UUID run = UUID.randomUUID();
        jdbc.sql("insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot, chain_lamports,"
                        + " ledger_lamports, result, started_at, finished_at) values (:r, :t, :w, 'SCHEDULE', 10, :chain,"
                        + " :ledger, 'DIVERGENT', now(), now())")
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("chain", chainLamports)
                .param("ledger", ledgerLamports)
                .update();
        jdbc.sql("insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,"
                        + " lamports_diff, opened_at, updated_at) values (gen_random_uuid(), :r, :r, :t, :w, :diff, now(),"
                        + " now())")
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("diff", chainLamports - ledgerLamports)
                .update();
    }

    /** S15: ledger 2 SOL and network 1.5 SOL: the total withdraws up to 1.5 SOL minus the reserve. */
    @Test
    void withADivergenceTheTotalWithdrawalUsesTheSmallerBalance() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 2 * SOL);
        divergence(person, 3 * SOL / 2, 2 * SOL);

        var response = call(
                person.person(), post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"all\":true}}"));

        assertThat(response.getStatus()).isEqualTo(202);
        long lamports = jdbc.sql("select lamports from withdrawal where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .query(Long.class)
                .single();
        assertThat(lamports).isLessThanOrEqualTo(3 * SOL / 2 - 30_000_000L);
        assertThat(lamports).isGreaterThan(3 * SOL / 2 - 31_000_000L);
    }

    @Test
    void aPartialAboveTheLimitIsRefusedLimitedByDivergenceAndTheRestCanBeWithdrawn() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 2 * SOL);
        divergence(person, 3 * SOL / 2, 2 * SOL);

        var above = call(
                person.person(),
                post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"lamports\":\"1800000000\"}}"));
        var within = call(
                person.person(),
                post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"lamports\":\"1000000000\"}}"));

        assertThat(above.getStatus()).isEqualTo(422);
        assertThat(body(above).get("code").asText()).isEqualTo("WITHDRAWAL_LIMITED_BY_DIVERGENCE");
        assertThat(body(above).get("detail").asText()).contains("Maximum withdrawable");
        assertThat(within.getStatus()).isEqualTo(202);
    }

    /** U5-NFR4.5, project.md: the kill switch on, no release and a divergence: the withdrawal stays available. */
    @Test
    void theWithdrawalStaysAvailableWithTheKillSwitchOnWithoutReleaseAndWithADivergence() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 2 * SOL);
        divergence(person, 2 * SOL, 2 * SOL - 1_000);
        jdbc.sql("update real_kill_switch set active = true, activated_at = now(), activated_by = :a, version = version"
                        + " + 1, updated_at = now()")
                .param("a", UUID.randomUUID())
                .update();

        var response = call(
                person.person(),
                post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"lamports\":\"500000000\"}}"));

        assertThat(response.getStatus()).isEqualTo(202);
    }
}
