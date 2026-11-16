package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.application.DivergenceService;
import com.memetrade.botwallet.application.ReconciliationService;
import com.memetrade.botwallet.domain.Compensation;
import com.memetrade.botwallet.domain.ReconciliationResult;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.ReconciliationTrigger;
import com.memetrade.botwallet.infra.RealOpsMetrics;
import com.memetrade.botwallet.infra.RealOpsProperties;
import com.memetrade.botwallet.infra.ReconciliationReader;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 6 of the U5, written BEFORE the code (F3, F4, BR2.1 to BR2.3, S10 to S14, U5-NFR2.1, U5-NFR4.3, U5-NFR4.6): the
 * reconciliation of a bot wallet against a recorded network, the divergence that pauses the person, and the compensation
 * that only appends entries equal to the measure and is confirmed by a new clean reconciliation.
 */
@Tag("u5-real-operations")
class ReconciliationIT extends AbstractRealOpsIT {

    private static final long SOL = 1_000_000_000L;
    private static final String MINT = SyntheticIds.mint();

    @Autowired
    private ReconciliationStore store;

    @Autowired
    private RealLedger ledger;

    @Autowired
    private RealExecutionPort execution;

    @Autowired
    private DivergenceService divergences;

    @Autowired
    private RealOpsMetrics metrics;

    @Autowired
    private RealOpsProperties ops;

    @Autowired
    private RealStateQuery state;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private com.memetrade.botwallet.infra.GlobalPauses pauses;

    private final RecordedChain chain = new RecordedChain();

    private ReconciliationService reconciliation() {
        return new ReconciliationService(
                store,
                new ReconciliationReader(chain),
                ledger,
                execution,
                divergences,
                metrics,
                ops,
                pauses,
                transactions,
                clock);
    }

    /** A person with {@code lamports} in the ledger, every deposit read (the cursor is the newest signature). */
    private OpsPerson funded(long lamports) throws Exception {
        OpsPerson person = activePerson();
        deposit(person, lamports);
        String signature = SyntheticIds.signature();
        chain.transaction(person.botAddress(), signature, person.mainAddress());
        jdbc.sql("update bot_wallet_account set deposit_cursor = :c where bot_wallet_id = :w")
                .param("c", signature)
                .param("w", person.botWalletId())
                .update();
        chain.lamports.put(person.botAddress(), lamports);
        return person;
    }

    private ReconciliationResult reconcile(OpsPerson person, ReconciliationTrigger trigger) {
        return reconciliation().reconcile(person.botWalletId(), trigger).result();
    }

    private Map<String, Object> divergenceOf(OpsPerson person) {
        return jdbc.sql("select divergence_id, state, lamports_diff, token_diffs::text as tokens, run_id, last_run_id"
                        + " from real_divergence where bot_wallet_id = :w order by opened_at desc limit 1")
                .param("w", person.botWalletId())
                .query()
                .singleRow();
    }

    private long divergencesOf(OpsPerson person) {
        return jdbc.sql("select count(*) from real_divergence where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .query(Long.class)
                .single();
    }

    private boolean pausedForDivergence(OpsPerson person) {
        return state.readRealState(person.tenantId(), person.userId())
                .orElseThrow()
                .pausedReasons()
                .contains(RealPauseReason.DIVERGENCE);
    }

    private double count(String result) {
        return meters.get("real_reconciliations")
                .tag("result", result)
                .counter()
                .count();
    }

    // ----- F3: the reconciliation (BR2.1, BR2.2) -----

    /** S10: after a confirmed order, the network and the ledger agree. */
    @Test
    void equalBalancesAreCleanAndOpenNothing() throws Exception {
        OpsPerson person = funded(2 * SOL);

        assertThat(reconcile(person, ReconciliationTrigger.ORDER_CONFIRMED)).isEqualTo(ReconciliationResult.CLEAN);
        assertThat(divergencesOf(person)).isZero();
        assertThat(jdbc.sql(
                                "select trigger || ':' || result || ':' || chain_lamports from real_reconciliation_run where"
                                        + " bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(String.class)
                        .single())
                .isEqualTo("ORDER_CONFIRMED:CLEAN:" + 2 * SOL);
    }

    /** S11: 1.000 lamports more on the network: DIVERGENT, the person is paused DIVERGENCE and the alert counts. */
    @Test
    void moreLamportsOnTheNetworkOpenADivergenceAndPauseThePerson() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        double before = count("DIVERGENT");

        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.DIVERGENT);

        Map<String, Object> divergence = divergenceOf(person);
        assertThat(divergence.get("state")).isEqualTo("OPEN");
        assertThat(divergence.get("lamports_diff")).isEqualTo(1_000L);
        assertThat(pausedForDivergence(person)).isTrue();
        assertThat(count("DIVERGENT")).isEqualTo(before + 1);
        assertThat(jdbc.sql("select count(*) from audit_outbox where payload ->> 'action' = 'REAL_DIVERGENCE_OPENED'"
                                + " and payload ->> 'targetRef' = :t")
                        .param("t", "divergence:" + divergence.get("divergence_id"))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aSecondDivergentRunUpdatesTheOpenDivergenceAndNeverDuplicatesIt() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID firstRun = (UUID) divergenceOf(person).get("last_run_id");
        chain.lamports.put(person.botAddress(), SOL + 1_500);
        clock.advance(Duration.ofMinutes(15));

        reconcile(person, ReconciliationTrigger.SCHEDULE);

        assertThat(divergencesOf(person)).isEqualTo(1);
        assertThat(divergenceOf(person).get("lamports_diff")).isEqualTo(1_500L);
        assertThat(divergenceOf(person).get("last_run_id")).isNotEqualTo(firstRun);
        assertThat(divergenceOf(person).get("run_id")).isEqualTo(firstRun);
    }

    /** S12: an order SENT: DEFERRED without reading the network; no divergence until it ends. */
    @Test
    void anOrderInTransitDefersWithoutReadingTheNetwork() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL - 50_000_000L);
        order(person, "SENT");
        int reads = chain.reads.get();

        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.DEFERRED);
        assertThat(chain.reads.get()).isEqualTo(reads);
        assertThat(divergencesOf(person)).isZero();
    }

    @Test
    void aWithdrawalInTransitDefersToo() throws Exception {
        OpsPerson person = funded(SOL);
        jdbc.sql("insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports, state,"
                        + " idempotency_key, signing_request_id, signed_transaction, message_digest, tx_signature,"
                        + " next_attempt_at, requested_at, updated_at) values (gen_random_uuid(), :t, :w, 'PARTIAL',"
                        + " 1000, 5000, 'SENT', gen_random_uuid(), gen_random_uuid(), 'c2ln', :digest, :sig, now(),"
                        + " now(), now())")
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("digest", "0".repeat(64))
                .param("sig", SyntheticIds.signature())
                .update();

        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.DEFERRED);
    }

    /** S12b: a deposit finalized on the network and not yet read: DEFERRED; once read, CLEAN. */
    @Test
    void anEntryNotYetReadDefersAndOnceReadIsClean() throws Exception {
        OpsPerson person = funded(SOL);
        String deposit = SyntheticIds.signature();
        chain.transaction(person.botAddress(), deposit, person.mainAddress());
        chain.lamports.put(person.botAddress(), 2 * SOL);

        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.DEFERRED);
        assertThat(divergencesOf(person)).isZero();

        deposit(person, SOL);
        jdbc.sql("update bot_wallet_account set deposit_cursor = :c where bot_wallet_id = :w")
                .param("c", deposit)
                .param("w", person.botWalletId())
                .update();
        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.CLEAN);
    }

    /** U5-NFR4.3: the network down: UNAVAILABLE, no divergence and no new pause. */
    @Test
    void aNetworkThatDoesNotAnswerIsUnavailableWithoutDivergenceNorPause() throws Exception {
        OpsPerson person = funded(SOL);
        chain.down = true;

        assertThat(reconcile(person, ReconciliationTrigger.SCHEDULE)).isEqualTo(ReconciliationResult.UNAVAILABLE);
        assertThat(divergencesOf(person)).isZero();
        assertThat(pausedForDivergence(person)).isFalse();
    }

    // ----- F4: the compensation (BR2.3) -----

    /** S13: compensate +1.000: COMPENSATED; the new reconciliation is CLEAN: RESOLVED and the pause leaves. */
    @Test
    void theCompensationEqualToTheMeasureAndACleanRunResolveAndResume() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");
        UUID admin = UUID.randomUUID();

        var compensated = transactions.execute(status -> divergences.compensate(
                admin, divergence, List.of(new Compensation(Compensation.SOL, 1_000L)), "depósito sem registro"));

        assertThat(compensated.state()).isEqualTo("COMPENSATED");
        assertThat(ledger.balance(person.tenantId(), person.botWalletId()).totalLamports())
                .isEqualTo(SOL + 1_000);
        assertThat(jdbc.sql("select count(*) from real_ledger_entry where bot_wallet_id = :w and entry_kind ="
                                + " 'COMPENSATION' and lamports = 1000")
                        .param("w", person.botWalletId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(pausedForDivergence(person)).isTrue();

        assertThat(reconcile(person, ReconciliationTrigger.AFTER_COMPENSATION)).isEqualTo(ReconciliationResult.CLEAN);

        assertThat(divergenceOf(person).get("state")).isEqualTo("RESOLVED");
        assertThat(pausedForDivergence(person)).isFalse();
    }

    /** S14: a value different from the measure is refused and the divergence stays OPEN with the pause. */
    @Test
    void aCompensationWithTheWrongValueIsRefusedAndTheDivergenceStaysOpen() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");

        Throwable refused = catchThrowable(() -> transactions.execute(status -> divergences.compensate(
                UUID.randomUUID(), divergence, List.of(new Compensation(Compensation.SOL, 900L)), "motivo")));

        assertThat(refused).isInstanceOfSatisfying(RealAdminRefused.class, r -> {
            assertThat(r.code()).isEqualTo("COMPENSATION_MISMATCH");
            assertThat(r.status()).isEqualTo(422);
        });
        assertThat(divergenceOf(person).get("state")).isEqualTo("OPEN");
        assertThat(pausedForDivergence(person)).isTrue();
    }

    /** S14 (the measure moved): the new reconciliation still divergent puts the divergence back to OPEN. */
    @Test
    void aStillDivergentRunAfterTheCompensationReopensWithTheNewValues() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");
        transactions.execute(status -> divergences.compensate(
                UUID.randomUUID(), divergence, List.of(new Compensation(Compensation.SOL, 1_000L)), "motivo"));
        chain.lamports.put(person.botAddress(), SOL + 1_500);

        assertThat(reconcile(person, ReconciliationTrigger.AFTER_COMPENSATION))
                .isEqualTo(ReconciliationResult.DIVERGENT);

        assertThat(divergenceOf(person).get("state")).isEqualTo("OPEN");
        assertThat(divergenceOf(person).get("lamports_diff")).isEqualTo(500L);
        assertThat(divergencesOf(person)).isEqualTo(1);
        assertThat(pausedForDivergence(person)).isTrue();
    }

    @Test
    void aTokenDivergenceIsCompensatedInBaseUnitsWithoutLamports() throws Exception {
        OpsPerson person = funded(SOL);
        chain.tokens.put(person.botAddress(), Map.of(MINT, 750L));
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");
        assertThat((String) divergenceOf(person).get("tokens")).contains(MINT).contains("750");

        transactions.execute(status -> divergences.compensate(
                UUID.randomUUID(), divergence, List.of(new Compensation(MINT, 750L)), "airdrop"));

        assertThat(jdbc.sql("select lamports || ':' || token_base_units from real_ledger_entry where bot_wallet_id = :w"
                                + " and entry_kind = 'COMPENSATION'")
                        .param("w", person.botWalletId())
                        .query(String.class)
                        .single())
                .isEqualTo("0:750");
        assertThat(reconcile(person, ReconciliationTrigger.AFTER_COMPENSATION)).isEqualTo(ReconciliationResult.CLEAN);
        assertThat(divergenceOf(person).get("state")).isEqualTo("RESOLVED");
    }

    @Test
    void aResolvedDivergenceIsNotCompensatedAgainAndTheReasonIsRequired() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");

        assertThat(catchThrowable(() -> transactions.execute(status -> divergences.compensate(
                        UUID.randomUUID(), divergence, List.of(new Compensation(Compensation.SOL, 1_000L)), " "))))
                .isInstanceOfSatisfying(
                        RealAdminRefused.class, r -> assertThat(r.code()).isEqualTo("VALIDATION_FAILED"));
        transactions.execute(status -> divergences.compensate(
                UUID.randomUUID(), divergence, List.of(new Compensation(Compensation.SOL, 1_000L)), "motivo"));
        reconcile(person, ReconciliationTrigger.AFTER_COMPENSATION);

        assertThat(catchThrowable(() -> transactions.execute(status -> divergences.compensate(
                        UUID.randomUUID(),
                        divergence,
                        List.of(new Compensation(Compensation.SOL, 1_000L)),
                        "de novo"))))
                .isInstanceOfSatisfying(
                        RealAdminRefused.class, r -> assertThat(r.code()).isEqualTo("DIVERGENCE_NOT_OPEN"));
        assertThat(catchThrowable(() -> transactions.execute(status -> divergences.compensate(
                        UUID.randomUUID(), UUID.randomUUID(), List.of(new Compensation(Compensation.SOL, 1L)), "x"))))
                .isInstanceOfSatisfying(
                        RealAdminRefused.class, r -> assertThat(r.code()).isEqualTo("NOT_FOUND"));
    }

    // ----- what is due (the job of 15 minutes and after a confirmed order) -----

    @Test
    void aWalletIsDueWithoutARunAfterTheIntervalAfterACompensationAndAfterAConfirmedWithdrawal() throws Exception {
        OpsPerson person = funded(SOL);
        ReconciliationService service = reconciliation();

        assertThat(dueTrigger(service, person)).contains(ReconciliationTrigger.SCHEDULE);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        assertThat(dueTrigger(service, person)).isEmpty();

        clock.advance(Duration.ofMinutes(16));
        assertThat(dueTrigger(service, person)).contains(ReconciliationTrigger.SCHEDULE);
        reconcile(person, ReconciliationTrigger.SCHEDULE);

        clock.advance(Duration.ofMinutes(1));
        jdbc.sql("insert into reconciliation_mark (mark_id, bot_wallet_id, reason, created_at) values"
                        + " (gen_random_uuid(), :w, 'WITHDRAWAL_CONFIRMED', :at)")
                .param("w", person.botWalletId())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        assertThat(dueTrigger(service, person)).contains(ReconciliationTrigger.ORDER_CONFIRMED);
    }

    @Test
    void aCompensatedDivergenceMakesTheWalletDueAfterCompensation() throws Exception {
        OpsPerson person = funded(SOL);
        chain.lamports.put(person.botAddress(), SOL + 1_000);
        reconcile(person, ReconciliationTrigger.SCHEDULE);
        UUID divergence = (UUID) divergenceOf(person).get("divergence_id");
        clock.advance(Duration.ofMinutes(1));
        transactions.execute(status -> divergences.compensate(
                UUID.randomUUID(), divergence, List.of(new Compensation(Compensation.SOL, 1_000L)), "motivo"));

        assertThat(dueTrigger(reconciliation(), person)).contains(ReconciliationTrigger.AFTER_COMPENSATION);
    }

    private java.util.Optional<ReconciliationTrigger> dueTrigger(ReconciliationService service, OpsPerson person) {
        return service.dueWallets().stream()
                .filter(due -> due.botWalletId().equals(person.botWalletId()))
                .map(ReconciliationService.DueWallet::trigger)
                .findFirst();
    }
}
