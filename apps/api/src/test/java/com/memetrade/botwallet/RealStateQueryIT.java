package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealPauses.PauseOutcome;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 3 of the U3, written before the code (M1 on PostgreSQL, BR4.5, BR6.3): the pauses and their start survive in
 * {@code real_preferences}, a pause of the person keeps its start, the wallet of another tenant is never read, and the
 * worker lists the wallets in force and the wallets paused for one reason.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
@org.springframework.test.context.ActiveProfiles("local")
class RealStateQueryIT extends AbstractRealIT {

    @Autowired
    private RealStateQuery query;

    @Autowired
    private RealPauses pauses;

    @Autowired
    private com.memetrade.botwallet.domain.BotWalletRepository repository;

    /**
     * Review R-06, written before the change: the person pauses (or resumes) from preferences read BEFORE the barrier
     * paused the wallet for DAILY_LOSS. Saving those preferences changes only the USER reason: the DAILY_LOSS pause
     * and its start stay.
     */
    @Test
    void savingThePreferencesOfThePersonNeverDropsAPauseOfTheBarrier() {
        Wallet wallet = wallet(true);
        com.memetrade.botwallet.domain.Preferences readBefore = repository.preferences(wallet.botWalletId());
        Instant lossAt = clock.instant();
        assertThat(pause(wallet, RealPauseReason.DAILY_LOSS)).isEqualTo(PauseOutcome.PAUSED);
        clock.advance(Duration.ofMinutes(1));

        transactions.executeWithoutResult(status -> repository.savePreferences(
                wallet.botWalletId(),
                readBefore.pause(com.memetrade.botwallet.domain.PauseReason.USER, clock.instant())));
        RealStateView paused =
                query.readRealState(wallet.tenantId(), wallet.userId()).orElseThrow();
        assertThat(paused.pausedReasons()).containsExactlyInAnyOrder(RealPauseReason.USER, RealPauseReason.DAILY_LOSS);
        assertThat(paused.pausedSince())
                .containsEntry(RealPauseReason.DAILY_LOSS, lossAt)
                .containsEntry(RealPauseReason.USER, clock.instant());

        com.memetrade.botwallet.domain.Preferences withUserOnly =
                readBefore.pause(com.memetrade.botwallet.domain.PauseReason.USER, clock.instant());
        transactions.executeWithoutResult(
                status -> repository.savePreferences(wallet.botWalletId(), withUserOnly.resumeByUser(clock.instant())));
        assertThat(query.readRealState(wallet.tenantId(), wallet.userId())
                        .orElseThrow()
                        .pausedReasons())
                .containsExactly(RealPauseReason.DAILY_LOSS);
    }

    private record Wallet(UUID tenantId, UUID userId, UUID botWalletId) {}

    private Wallet wallet(boolean released) {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botWalletId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', 5000000000, 30000000, 100000, :at)""").param("id", botWalletId).param("at", Timestamp.from(now)).update();
        if (released) {
            Instant requested = now.minus(Duration.ofHours(25));
            jdbc.sql("""
                            insert into real_release (release_id, user_id, network, requested_by, requested_at,
                              effective_at, state) values (:id, :user, 'LOCALNET', :user, :requested, :effective,
                              'ACTIVE')""")
                    .param("id", UUID.randomUUID())
                    .param("user", person.userId())
                    .param("requested", Timestamp.from(requested))
                    .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                    .update();
        }
        return new Wallet(person.tenantId(), person.userId(), botWalletId);
    }

    private PauseOutcome resume(Wallet wallet, RealPauseReason reason) {
        PauseOutcome outcome =
                transactions.execute(status -> pauses.resumeUser(wallet.tenantId(), wallet.userId(), reason));
        return outcome;
    }

    private PauseOutcome pauseAs(UUID tenantId, UUID userId, RealPauseReason reason) {
        PauseOutcome outcome = transactions.execute(status -> pauses.pauseUser(tenantId, userId, reason));
        return outcome;
    }

    private PauseOutcome pause(Wallet wallet, RealPauseReason reason) {
        return transactions.execute(status -> pauses.pauseUser(wallet.tenantId(), wallet.userId(), reason));
    }

    @Test
    void aPauseAndItsStartAreKeptAndAResumeRemovesOnlyItsReason() {
        Wallet wallet = wallet(true);
        Instant pausedAt = clock.instant();
        assertThat(pause(wallet, RealPauseReason.DAILY_LOSS)).isEqualTo(PauseOutcome.PAUSED);
        clock.advance(Duration.ofMinutes(10));
        assertThat(pause(wallet, RealPauseReason.ADMIN)).isEqualTo(PauseOutcome.PAUSED);
        assertThat(pause(wallet, RealPauseReason.DAILY_LOSS)).isEqualTo(PauseOutcome.ALREADY_PAUSED);

        RealStateView paused =
                query.readRealState(wallet.tenantId(), wallet.userId()).orElseThrow();
        assertThat(paused.pausedReasons()).containsExactlyInAnyOrder(RealPauseReason.DAILY_LOSS, RealPauseReason.ADMIN);
        assertThat(paused.pausedSince()).containsEntry(RealPauseReason.DAILY_LOSS, pausedAt);

        assertThat(resume(wallet, RealPauseReason.DAILY_LOSS)).isEqualTo(PauseOutcome.RESUMED);
        RealStateView resumed =
                query.readRealState(wallet.tenantId(), wallet.userId()).orElseThrow();
        assertThat(resumed.pausedReasons()).containsExactly(RealPauseReason.ADMIN);
        assertThat(resumed.pausedSince()).doesNotContainKey(RealPauseReason.DAILY_LOSS);
        assertThat(resume(wallet, RealPauseReason.DAILY_LOSS)).isEqualTo(PauseOutcome.NOT_PAUSED);
    }

    @Test
    void theWalletOfAnotherTenantIsNeverReadNorPaused() {
        Wallet mine = wallet(true);
        Wallet other = wallet(true);

        assertThat(query.readRealState(other.tenantId(), mine.userId())).isEmpty();
        assertThat(pauseAs(other.tenantId(), mine.userId(), RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.NO_WALLET);
        assertThat(query.readRealState(mine.tenantId(), mine.userId())
                        .orElseThrow()
                        .pausedReasons())
                .isEmpty();
    }

    @Test
    void theWorkerListsTheWalletsInForceAndThePausedOnes() {
        Wallet released = wallet(true);
        Wallet quarantined = wallet(false);
        pause(released, RealPauseReason.DAILY_LOSS);

        assertThat(query.walletsInForce())
                .extracting(ref -> ref.botWalletId())
                .contains(released.botWalletId())
                .doesNotContain(quarantined.botWalletId());
        assertThat(query.pausedWallets(RealPauseReason.DAILY_LOSS))
                .filteredOn(paused -> paused.wallet().botWalletId().equals(released.botWalletId()))
                .singleElement()
                .satisfies(paused -> assertThat(paused.since()).isEqualTo(clock.instant()));
        assertThat(query.pausedWallets(RealPauseReason.DAILY_LOSS))
                .noneMatch(paused -> paused.wallet().botWalletId().equals(quarantined.botWalletId()));
    }
}
