package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.application.DivergenceService;
import com.memetrade.botwallet.application.ReconciliationService;
import com.memetrade.botwallet.application.RestoreService;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.DepositScanner;
import com.memetrade.botwallet.domain.ReconciliationResult;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.ReconciliationTrigger;
import com.memetrade.botwallet.domain.RestoreStore;
import com.memetrade.botwallet.domain.WithdrawalProcessor;
import com.memetrade.botwallet.infra.BotWalletJobs;
import com.memetrade.botwallet.infra.GlobalPauses;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealOpsMetrics;
import com.memetrade.botwallet.infra.RealOpsProperties;
import com.memetrade.botwallet.infra.ReconciliationReader;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 8 of the U5, written BEFORE the code (F10, BR2.5, S24, S24b, S24c, U5-NFR4.4, reliability-design.md
 * "Restauração"): at the start of the worker the marker RESTORE_CHECK_PENDING opens BEFORE any read and the deposit scan
 * waits; the history of every bot wallet newer than its cursor is read page by page until it ends; a signature of the
 * bot wallet the database does not know turns the marker into the global pause RESTORE until every wallet reconciled
 * CLEAN after it and the administrator confirmed; a deposit never counts; with the RPC down the check stays pending and
 * the withdrawal goes on.
 */
@Tag("u5-real-operations")
class RestoreIT extends AbstractRealOpsIT {

    @Autowired
    private RestoreStore store;

    @Autowired
    private RealExecutionPort execution;

    @Autowired
    private RealAudit audit;

    @Autowired
    private RealStateQuery state;

    @Autowired
    private GlobalPauses pauses;

    @Autowired
    private ReconciliationStore reconciliations;

    @Autowired
    private RealLedger ledger;

    @Autowired
    private DivergenceService divergences;

    @Autowired
    private RealOpsMetrics metrics;

    @Autowired
    private RealOpsProperties ops;

    @Autowired
    private BotWalletRepository repository;

    private final RecordedChain chain = new RecordedChain();

    private RestoreService restore() {
        return new RestoreService(store, chain, execution, audit, transactions, clock);
    }

    private ReconciliationService reconciliation() {
        return new ReconciliationService(
                reconciliations,
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

    private OpsPerson personWithCursor(String cursor) throws Exception {
        OpsPerson person = activePerson();
        jdbc.sql("update bot_wallet_account set deposit_cursor = :c where bot_wallet_id = :w")
                .param("c", cursor)
                .param("w", person.botWalletId())
                .update();
        return person;
    }

    private String phase() {
        return pauses.openRestoreMarker().map(GlobalPauses.OpenMarker::phase).orElse("NONE");
    }

    /** S24c: the RPC down at the start: the check stays pending, new orders are refused and the withdrawal goes on. */
    @Test
    void withTheRpcDownTheCheckStaysPendingAndTheWithdrawalGoesOn() throws Exception {
        OpsPerson person = personWithCursor(SyntheticIds.signature());
        chain.transaction(person.botAddress(), SyntheticIds.signature(), person.botAddress());
        deposit(person, 1_000_000_000L);
        chain.down = true;
        RestoreService service = restore();

        service.openCheck();
        assertThat(service.check()).isEqualTo(RestoreService.Outcome.PENDING);

        assertThat(phase()).isEqualTo("CHECK_PENDING");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .pausedReasons())
                .contains(RealPauseReason.RESTORE_CHECK_PENDING);
        assertThat(call(
                                person.person(),
                                post("/api/v1/real/bot-wallet/withdrawals")
                                        .content("{\"amount\":{\"lamports\":\"100000000\"}}"))
                        .getStatus())
                .isEqualTo(202);

        chain.down = false;
        chain.signers.clear();
        chain.history.clear();
        assertThat(service.check()).isEqualTo(RestoreService.Outcome.CLEAN);
        assertThat(phase()).isEqualTo("NONE");
    }

    /** S24b: an ordinary restart after a recent deposit: nothing unknown, nobody is paused. */
    @Test
    void aRecentDepositIsNotUnknownAndNobodyIsPaused() throws Exception {
        String cursor = SyntheticIds.signature();
        OpsPerson person = personWithCursor(cursor);
        chain.transaction(person.botAddress(), cursor, person.mainAddress());
        chain.transaction(person.botAddress(), SyntheticIds.signature(), person.mainAddress());
        RestoreService service = restore();

        service.openCheck();

        assertThat(service.check()).isEqualTo(RestoreService.Outcome.CLEAN);
        assertThat(phase()).isEqualTo("NONE");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .paused())
                .isFalse();
    }

    @Test
    void aSwapOfAKnownOrderAndAKnownWithdrawalAreNotUnknown() throws Exception {
        String cursor = SyntheticIds.signature();
        OpsPerson person = personWithCursor(cursor);
        chain.transaction(person.botAddress(), cursor, person.botAddress());
        String swap = SyntheticIds.signature();
        jdbc.sql("insert into real_order (real_order_id, tenant_id, user_id, bot_wallet_id, bot_id, origin,"
                        + " idempotency_key, side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps,"
                        + " reason, state, tx_signature, created_at, updated_at) values (gen_random_uuid(), :t, :u, :w,"
                        + " gen_random_uuid(), 'BOT', gen_random_uuid()::text, 'BUY', :mint, 1000, 'AUTO_FULL',"
                        + " 'AUTO_FULL', 200, 'sinal', 'CONFIRMED', :sig, now(), now())")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("w", person.botWalletId())
                .param("mint", SyntheticIds.mint())
                .param("sig", swap)
                .update();
        chain.transaction(person.botAddress(), swap, person.botAddress());
        RestoreService service = restore();
        service.openCheck();

        assertThat(service.check()).isEqualTo(RestoreService.Outcome.CLEAN);
    }

    /** S24: a backup older than swaps of the bot wallet: RESTORE, the complete reconciliation, then the confirmation. */
    @Test
    void anUnknownSwapOfTheBotWalletPausesEverybodyUntilEveryWalletIsCleanAndTheAdministratorConfirms()
            throws Exception {
        String cursor = SyntheticIds.signature();
        OpsPerson person = personWithCursor(cursor);
        deposit(person, 1_000_000_000L);
        chain.lamports.put(person.botAddress(), 1_000_000_000L);
        chain.transaction(person.botAddress(), cursor, person.mainAddress());
        String unknownSwap = SyntheticIds.signature();
        chain.transaction(person.botAddress(), unknownSwap, person.botAddress());
        // Every other ACTIVE wallet of the shared database is closed, so "every wallet" is this one.
        jdbc.sql("update bot_wallet_account set status = 'CLOSED', closed_at = now() where status = 'ACTIVE' and"
                        + " bot_wallet_id <> :w")
                .param("w", person.botWalletId())
                .update();
        RestoreService service = restore();
        service.openCheck();

        assertThat(service.check()).isEqualTo(RestoreService.Outcome.RESTORE);

        assertThat(phase()).isEqualTo("RESTORE");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .pausedReasons())
                .contains(RealPauseReason.RESTORE);
        assertThat(catchThrowable(() -> transactions.execute(status -> service.confirm(java.util.UUID.randomUUID()))))
                .isInstanceOfSatisfying(
                        RealAdminRefused.class, r -> assertThat(r.code()).isEqualTo("RESTORE_NOT_CLEAN"));
        assertThat(reconciliation().dueWallets())
                .contains(
                        new ReconciliationService.DueWallet(person.botWalletId(), ReconciliationTrigger.AFTER_RESTORE));

        // In the RESTORE phase the deposit scan runs again and its cursor passes the history (the swap moved no SOL).
        jdbc.sql("update bot_wallet_account set deposit_cursor = :c where bot_wallet_id = :w")
                .param("c", unknownSwap)
                .param("w", person.botWalletId())
                .update();
        assertThat(reconciliation()
                        .reconcile(person.botWalletId(), ReconciliationTrigger.AFTER_RESTORE)
                        .result())
                .isEqualTo(ReconciliationResult.CLEAN);
        transactions.execute(status -> service.confirm(java.util.UUID.randomUUID()));

        assertThat(phase()).isEqualTo("NONE");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .paused())
                .isFalse();
    }

    /** No fixed window: the history is read page by page until it ends; an old unknown swap is still found. */
    @Test
    void theHistoryIsReadPageByPageUntilItEnds() throws Exception {
        String cursor = SyntheticIds.signature();
        OpsPerson person = personWithCursor(cursor);
        chain.transaction(person.botAddress(), cursor, person.mainAddress());
        chain.transaction(person.botAddress(), SyntheticIds.signature(), person.botAddress());
        for (int i = 0; i < 1_200; i++) {
            chain.transaction(
                    person.botAddress(), "Dep" + i + SyntheticIds.signature().substring(0, 40), person.mainAddress());
        }
        RestoreService service = restore();
        service.openCheck();

        assertThat(service.check()).isEqualTo(RestoreService.Outcome.RESTORE);
        assertThat(chain.signerReads.get()).isEqualTo(1_201);
    }

    /** The deposit scan never runs while the check is pending, so its cursor never passes an unchecked signature. */
    @Test
    void theDepositScanWaitsWhileTheCheckIsPending() {
        DepositScanner scanner = mock(DepositScanner.class);
        BotWalletJobs jobs = new BotWalletJobs(scanner, mock(WithdrawalProcessor.class), repository, clock, pauses);
        restore().openCheck();

        jobs.scanDeposits();

        verify(scanner, never()).scan();
    }

    @Test
    void openingTheCheckTwiceKeepsOneMarker() {
        RestoreService service = restore();

        service.openCheck();
        service.openCheck();

        assertThat(jdbc.sql("select count(*) from real_restore_marker where cleared_at is null")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from real_restore_marker where cleared_at is null and opened_at = :at")
                        .param("at", Timestamp.from(clock.instant()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void withoutAnOpenCheckTheJobDoesNothing() {
        assertThat(restore().check()).isEqualTo(RestoreService.Outcome.NOTHING);
    }
}
