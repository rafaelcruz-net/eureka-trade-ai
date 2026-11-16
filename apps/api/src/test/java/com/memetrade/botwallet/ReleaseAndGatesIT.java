package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.application.GatesService;
import com.memetrade.botwallet.application.ReleaseService;
import com.memetrade.botwallet.domain.CiResult;
import com.memetrade.botwallet.domain.CiStatus;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealOpsConfig;
import com.memetrade.botwallet.infra.RealOpsMetrics;
import com.memetrade.botwallet.infra.RealOpsProperties;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.identity.api.RealIdentity;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 4 of the U5, written BEFORE the code (BR1.1 to BR1.6, S1 to S7, S9, S27, S29, U5-NFR4.1, U5-NFR4.3): the release
 * of a person with its quarantine of 24 hours, the founder first and at most 20 guests on the MAINNET under one lock,
 * the revocation that cancels the unsent orders and leaves the withdrawal free, and the gates job with the CI of the
 * exact commit (a recorded fake here; the GitHub adapter has its own WireMock test).
 */
@Tag("u5-real-operations")
class ReleaseAndGatesIT extends AbstractRealOpsIT {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    private ReleaseService releases;

    @Autowired
    private ReleaseStore store;

    @Autowired
    private RealIdentity identity;

    @Autowired
    private RealExecutionPort execution;

    @Autowired
    private RealAudit audit;

    @Autowired
    private RealOpsMetrics metrics;

    @Autowired
    private RealProperties properties;

    @Autowired
    private RealOpsProperties ops;

    @Autowired
    private RealKillSwitchState killSwitch;

    @Autowired
    private RealStateQuery state;

    /** The recorded CI of the gates: answers {@link #ci} and counts its calls. */
    private final AtomicInteger ciCalls = new AtomicInteger();

    private CiResult ci = CiResult.green("9100000001");

    private GatesService gates() {
        CiStatus recorded = sha -> {
            ciCalls.incrementAndGet();
            return sha.equals(SHA) ? ci : CiResult.red();
        };
        return new GatesService(
                store,
                recorded,
                killSwitch,
                audit,
                metrics,
                properties,
                new RealOpsConfig.CheckedOps(ops, SHA),
                transactions,
                clock);
    }

    private ReleaseService mainnet(UUID founder) {
        RealProperties mainnet = mock(RealProperties.class);
        when(mainnet.network()).thenReturn(Network.MAINNET);
        when(mainnet.founder()).thenReturn(Optional.of(founder));
        return new ReleaseService(store, identity, execution, audit, mainnet, clock);
    }

    private static String refusal(Throwable failure) {
        return failure instanceof RealAdminRefused refused ? refused.code() : String.valueOf(failure);
    }

    // ----- F1: the release (BR1.2, BR1.3) -----

    /** S1, S2: a new release waits its 24 hours in QUARANTINE and the REAL is not in force meanwhile. */
    @Test
    void aReleaseStartsInQuarantineForTwentyFourHoursAndIsAudited() throws Exception {
        OpsPerson person = activePerson();
        UUID admin = UUID.randomUUID();

        var view = transactions.execute(status -> releases.release(admin, person.userId(), Network.LOCALNET));

        assertThat(view.state()).isEqualTo("QUARANTINE");
        assertThat(view.effectiveAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(releaseState(view.releaseId())).isEqualTo("QUARANTINE");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .released())
                .isFalse();
        assertThat(jdbc.sql("select count(*) from audit_outbox where payload ->> 'action' = 'REAL_RELEASE_REQUESTED'"
                                + " and payload ->> 'actor' = :admin and payload ->> 'targetRef' = :target")
                        .param("admin", admin.toString())
                        .param("target", "user:" + person.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aSecondReleaseOfTheSamePersonAndNetworkIsRefusedReleaseExists() throws Exception {
        OpsPerson person = activePerson();
        transactions.execute(status -> releases.release(UUID.randomUUID(), person.userId(), Network.LOCALNET));

        Throwable second = catchThrowable(() ->
                transactions.execute(status -> releases.release(UUID.randomUUID(), person.userId(), Network.LOCALNET)));

        assertThat(refusal(second)).isEqualTo("RELEASE_EXISTS");
        assertThat(((RealAdminRefused) second).status()).isEqualTo(409);
    }

    @Test
    void aPersonWithoutABotWalletOrWithoutTheTermsV3IsRefused() throws Exception {
        Person withoutWallet = signIn(true, false);
        TestAccounts.Seeded withoutTerms =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        jdbc.sql("insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,"
                        + " main_wallet_address, network, created_at) values (gen_random_uuid(), :t, :u, :a, :m,"
                        + " 'LOCALNET', now())")
                .param("t", withoutTerms.tenantId())
                .param("u", withoutTerms.userId())
                .param("a", TestWallet.generate().address())
                .param("m", TestWallet.generate().address())
                .update();

        assertThat(refusal(catchThrowable(() -> transactions.execute(
                        status -> releases.release(UUID.randomUUID(), withoutWallet.userId(), Network.LOCALNET)))))
                .isEqualTo("NO_BOT_WALLET");
        assertThat(refusal(catchThrowable(() -> transactions.execute(
                        status -> releases.release(UUID.randomUUID(), withoutTerms.userId(), Network.LOCALNET)))))
                .isEqualTo("TERMS_V3_MISSING");
    }

    @Test
    void aReleaseOnAnotherNetworkThanTheEnvironmentIsRefused() throws Exception {
        OpsPerson person = activePerson();

        Throwable devnet = catchThrowable(() ->
                transactions.execute(status -> releases.release(UUID.randomUUID(), person.userId(), Network.DEVNET)));

        assertThat(refusal(devnet)).isEqualTo("VALIDATION_FAILED");
    }

    /** S5: on the MAINNET a guest needs the founder active first. */
    @Test
    void onTheMainnetAGuestBeforeTheFounderIsRefusedFounderFirst() throws Exception {
        revokeEveryOpenRelease("MAINNET");
        OpsPerson founder = activePerson();
        OpsPerson guest = activePerson();
        ReleaseService service = mainnet(founder.userId());

        Throwable refused = catchThrowable(() ->
                transactions.execute(status -> service.release(UUID.randomUUID(), guest.userId(), Network.MAINNET)));

        assertThat(refusal(refused)).isEqualTo("FOUNDER_FIRST");
        assertThat(transactions
                        .execute(status -> service.release(UUID.randomUUID(), founder.userId(), Network.MAINNET))
                        .state())
                .isEqualTo("QUARANTINE");
    }

    /** S6: the founder active for one hour is enough; there is no wait of 7 days. */
    @Test
    void onTheMainnetAGuestIsAcceptedOnceTheFounderIsActiveWithoutAnyWait() throws Exception {
        revokeEveryOpenRelease("MAINNET");
        OpsPerson founder = activePerson();
        OpsPerson guest = activePerson();
        releaseOf(founder.userId(), "MAINNET", "ACTIVE", Duration.ofHours(25));
        ReleaseService service = mainnet(founder.userId());

        assertThat(transactions
                        .execute(status -> service.release(UUID.randomUUID(), guest.userId(), Network.MAINNET))
                        .state())
                .isEqualTo("QUARANTINE");
    }

    /** S7, S27: with 19 guests, two requests at the same time: one accepted, the other PILOT_FULL. */
    @Test
    void twoGuestsAtTheSameTimeWithNineteenReleasedNeverPassTheLimitOfTwenty() throws Exception {
        revokeEveryOpenRelease("MAINNET");
        OpsPerson founder = activePerson();
        releaseOf(founder.userId(), "MAINNET", "ACTIVE", Duration.ofHours(25));
        for (int i = 0; i < 19; i++) {
            // Accounts without a login (the limiter of the login allows 10 per minute and none is needed here).
            UUID guest =
                    TestAccounts.betaUser(jdbc, TestWallet.generate().address()).userId();
            releaseOf(guest, "MAINNET", i % 2 == 0 ? "ACTIVE" : "QUARANTINE", Duration.ofHours(1));
        }
        OpsPerson first = activePerson();
        OpsPerson second = activePerson();
        ReleaseService service = mainnet(founder.userId());

        List<Concurrently.Outcome<String>> outcomes = Concurrently.run(
                () -> transactions
                        .execute(status -> service.release(UUID.randomUUID(), first.userId(), Network.MAINNET))
                        .state(),
                () -> transactions
                        .execute(status -> service.release(UUID.randomUUID(), second.userId(), Network.MAINNET))
                        .state());

        List<String> results = new ArrayList<>();
        outcomes.forEach(o -> results.add(o.failed() ? refusal(o.failure()) : o.value()));
        assertThat(results).containsExactlyInAnyOrder("QUARANTINE", "PILOT_FULL");
        revokeEveryOpenRelease("MAINNET");
    }

    // ----- F2: the revocation (BR1.6) -----

    /** S9: revoked at once; the unsent order is cancelled, the sent one goes on, and the withdrawal stays free. */
    @Test
    void aRevocationCancelsTheUnsentOrdersAndLeavesTheSentOnesAndTheWithdrawalFree() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));
        UUID quoted = order(person, "QUOTED");
        UUID sent = order(person, "SENT");
        deposit(person, 2_000_000_000L);

        var revoked =
                transactions.execute(status -> releases.revoke(UUID.randomUUID(), person.userId(), "compliance check"));

        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.cancelledOrders()).isEqualTo(1);
        assertThat(releaseState(release)).isEqualTo("REVOKED");
        assertThat(orderState(quoted)).isEqualTo("CANCELLED:REAL_DISABLED");
        assertThat(orderState(sent)).isEqualTo("SENT");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .released())
                .isFalse();
        var withdrawal = call(
                person.person(),
                post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"lamports\":\"100000000\"}}"));
        assertThat(withdrawal.getStatus()).isEqualTo(202);
    }

    @Test
    void revokingAnAlreadyRevokedPersonChangesNothing() throws Exception {
        OpsPerson person = activePerson();
        releaseOf(person.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));
        transactions.execute(status -> releases.revoke(UUID.randomUUID(), person.userId(), "first"));

        var again = transactions.execute(status -> releases.revoke(UUID.randomUUID(), person.userId(), "second"));

        assertThat(again.revoked()).isFalse();
        assertThat(again.cancelledOrders()).isZero();
    }

    // ----- F1 steps 3 to 5: the gates (BR1.4) -----

    /** S2, S3: the gates wait the 24 hours; then, all green, the release is ACTIVE and the REAL is in force. */
    @Test
    void afterTheQuarantineWithEveryGateGreenTheReleaseBecomesActive() throws Exception {
        OpsPerson person = activePerson();
        UUID release = transactions
                .execute(status -> releases.release(UUID.randomUUID(), person.userId(), Network.LOCALNET))
                .releaseId();

        clock.advance(Duration.ofHours(23));
        gates().run();
        assertThat(releaseState(release)).isEqualTo("QUARANTINE");

        clock.advance(Duration.ofHours(1));
        gates().run();

        assertThat(releaseState(release)).isEqualTo("ACTIVE");
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .released())
                .isTrue();
        assertThat(jdbc.sql("select payload -> 'details' ->> 'ciRunRef' from audit_outbox where payload ->> 'action'"
                                + " = 'REAL_RELEASE_GATES_CHECKED' and payload ->> 'targetRef' = :target")
                        .param("target", "release:" + release)
                        .query(String.class)
                        .single())
                .isEqualTo("9100000001");
    }

    /** S4: a red CI blocks CI_RED; when the CI turns green the next run makes it ACTIVE. */
    @Test
    void aRedCiBlocksAndTheNextGreenRunReleases() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(24));
        ci = CiResult.red();

        gates().run();
        assertThat(releaseState(release)).isEqualTo("BLOCKED");
        assertThat(blockedReason(release)).isEqualTo("CI_RED");

        ci = CiResult.green("9100000009");
        clock.advance(Duration.ofMinutes(5));
        gates().run();

        assertThat(releaseState(release)).isEqualTo("ACTIVE");
    }

    /** U5-NFR4.3: a CI that does not answer never releases. */
    @Test
    void aCiThatDoesNotAnswerBlocksCiUnreachable() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(25));
        ci = CiResult.unreachable();

        gates().run();

        assertThat(blockedReason(release)).isEqualTo("CI_UNREACHABLE");
    }

    /** S29: the image of another commit (another SHA) has no green run of its own: red. */
    @Test
    void theCiOfAnotherCommitDoesNotCount() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(25));
        CiStatus onlyAnOlderCommit = sha -> "an-older-commit".equals(sha) ? CiResult.green("1") : CiResult.red();
        GatesService other = new GatesService(
                store,
                onlyAnOlderCommit,
                killSwitch,
                audit,
                metrics,
                properties,
                new RealOpsConfig.CheckedOps(ops, SHA),
                transactions,
                clock);

        other.run();

        assertThat(blockedReason(release)).isEqualTo("CI_RED");
    }

    @Test
    void theKillSwitchOnBlocksKillSwitchOn() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(25));
        jdbc.sql("update real_kill_switch set active = true, activated_at = now(), activated_by = :a, version ="
                        + " version + 1, updated_at = now()")
                .param("a", UUID.randomUUID())
                .update();

        gates().run();

        assertThat(blockedReason(release)).isEqualTo("KILL_SWITCH_ON");
    }

    @Test
    void anOpenDivergenceOfThePersonBlocksDivergenceOpen() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(25));
        UUID run = UUID.randomUUID();
        jdbc.sql("insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot, chain_lamports,"
                        + " ledger_lamports, result, started_at, finished_at) values (:r, :t, :w, 'SCHEDULE', 1, 2, 1,"
                        + " 'DIVERGENT', now(), now())")
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .update();
        jdbc.sql("insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,"
                        + " lamports_diff, opened_at, updated_at) values (gen_random_uuid(), :r, :r, :t, :w, 1, now(),"
                        + " now())")
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .update();

        gates().run();

        assertThat(blockedReason(release)).isEqualTo("DIVERGENCE_OPEN");
    }

    @Test
    void aReleaseOfAnotherNetworkBlocksNetworkMismatch() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "DEVNET", "QUARANTINE", Duration.ofHours(25));

        gates().run();

        assertThat(blockedReason(release)).isEqualTo("NETWORK_MISMATCH");
    }

    /** U5-NFR3.3: one read of the CI per run, whatever the number of due releases. */
    @Test
    void oneRunReadsTheCiOnceForEveryDueRelease() throws Exception {
        UUID first = releaseOf(activePerson().userId(), "LOCALNET", "QUARANTINE", Duration.ofHours(25));
        UUID second = releaseOf(activePerson().userId(), "LOCALNET", "BLOCKED", Duration.ofHours(26));

        gates().run();

        assertThat(ciCalls.get()).isEqualTo(1);
        assertThat(releaseState(first)).isEqualTo("ACTIVE");
        assertThat(releaseState(second)).isEqualTo("ACTIVE");
    }

    private String blockedReason(UUID release) {
        return jdbc.sql("select blocked_reason from real_release where release_id = :id")
                .param("id", release)
                .query(String.class)
                .single();
    }
}
