package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 8 of the U3, written before the code (F3, F4, F5; BR4.3 to BR4.5, BR6.2, U3-NFR4.4 to U3-NFR4.6): the job of the
 * day records the photos once and resumes only the DAILY_LOSS pause of a previous UTC day; the job of the loss pauses a
 * wallet that reached 20%, also after a cycle that rolled back; the expiry closes the reservations past their time. Every
 * job is idempotent and publishes its heartbeat only after a successful run.
 */
class RealRiskJobsIT extends AbstractRealRiskIT {

    @Autowired
    private RealRiskJobs jobs;

    @Autowired
    private RealRiskGate gate;

    @Autowired
    private RealPauses pauses;

    @Autowired
    private RealStateQuery states;

    @Autowired
    private MeterRegistry meters;

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Map<String, Object> photo(Wallet wallet) {
        return jdbc.sql("select start_value_lamports, start_cap_lamports, taken_by, tenant_id from real_risk_day"
                        + " where bot_wallet_id = :wallet and day = :day")
                .param("wallet", wallet.botWalletId())
                .param("day", today())
                .query()
                .singleRow();
    }

    private long photos(Wallet wallet) {
        return jdbc.sql("select count(*) from real_risk_day where bot_wallet_id = :wallet")
                .param("wallet", wallet.botWalletId())
                .query(Long.class)
                .single();
    }

    private boolean pausedForLoss(Wallet wallet) {
        return states.readRealState(wallet.tenantId(), wallet.userId())
                .orElseThrow()
                .pausedReasons()
                .contains(RealPauseReason.DAILY_LOSS);
    }

    @Test
    void theJobOfTheDayRecordsThePhotoOfEveryWalletInForceOnceWithItsValueAndCap() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 7 * SOL);
        solAt("200", 5);
        position(wallet, token("0.2", 6, 5), 2 * SOL, 2 * SOL);

        assertThat(jobs.runDay().ran()).isTrue();
        assertThat(jobs.runDay().ran()).isTrue();

        assertThat(photos(wallet)).isEqualTo(1);
        assertThat(photo(wallet))
                .containsEntry("start_value_lamports", 7 * SOL)
                .containsEntry("start_cap_lamports", 10 * SOL)
                .containsEntry("taken_by", "DAY_JOB")
                .containsEntry("tenant_id", wallet.tenantId());
    }

    @Test
    void thePhotoOfTheFirstBuyOfTheDayStays() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 20 * SOL);
        String mint = token("0.01", 6, 5);
        assertThat(transactions
                        .execute(status -> gate.evaluate(OrderIntent.botBuy(
                                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), mint, SOL)))
                        .approved())
                .isTrue();

        jobs.runDay();

        assertThat(photo(wallet)).containsEntry("taken_by", "FIRST_EVALUATION");
    }

    @Test
    void aWalletWhoseReleaseIsNotInForceGetsNoPhoto() {
        Wallet wallet = releasedWallet(10 * SOL);
        jdbc.sql("update real_release set revoked_at = :at where user_id = :user")
                .param("at", java.sql.Timestamp.from(clock.instant().minusSeconds(1)))
                .param("user", wallet.userId())
                .update();

        jobs.runDay();

        assertThat(photos(wallet)).isZero();
    }

    /** BR4.5: the DAILY_LOSS pause of a previous UTC day ends; one of today and the other reasons stay. */
    @Test
    void theJobOfTheDayResumesOnlyTheDailyLossPauseOfAPreviousDay() {
        Wallet yesterday = releasedWallet(10 * SOL);
        Wallet today = releasedWallet(10 * SOL);
        clock.set(Instant.parse("2026-11-13T23:50:00Z"));
        transactions.executeWithoutResult(status -> {
            pauses.pauseUser(yesterday.tenantId(), yesterday.userId(), RealPauseReason.DAILY_LOSS);
            pauses.pauseUser(yesterday.tenantId(), yesterday.userId(), RealPauseReason.ADMIN);
        });
        clock.set(Instant.parse("2026-11-14T00:01:00Z"));
        transactions.executeWithoutResult(
                status -> pauses.pauseUser(today.tenantId(), today.userId(), RealPauseReason.DAILY_LOSS));
        clock.set(Instant.parse("2026-11-14T00:05:00Z"));

        jobs.runDay();
        DayRoundCheck second = new DayRoundCheck(jobs.runDay());

        assertThat(states.readRealState(yesterday.tenantId(), yesterday.userId())
                        .orElseThrow()
                        .pausedReasons())
                .containsExactly(RealPauseReason.ADMIN);
        assertThat(pausedForLoss(today)).isTrue();
        assertThat(second.round().resumed()).isZero();
    }

    private record DayRoundCheck(RealRiskJobs.DayRound round) {}

    /** BR4.3, F4: the wallet that reached 20% of loss is paused; one below is not. */
    @Test
    void theJobOfTheLossPausesAWalletThatReached20Percent() {
        Wallet losing = releasedWallet(10 * SOL);
        Wallet fine = releasedWallet(10 * SOL);
        deposit(losing, 10 * SOL);
        deposit(fine, 10 * SOL);
        jobs.runDay();
        clock.advance(Duration.ofSeconds(1));
        solAt("200", 5);
        // 0.4 USD -> 2 lamports per base unit: bought for 4 SOL, worth 2 SOL: 2 SOL of loss on 10 SOL.
        position(losing, token("0.4", 6, 5), SOL, 4 * SOL);
        // 1.4 USD -> 7 lamports per base unit: bought for 1 SOL... worth 0.7 SOL: 0.3 SOL of loss, below 2 SOL.
        position(fine, token("1.4", 6, 5), 100_000_000L, SOL);
        double before = meters.counter("real_risk_daily_loss_pauses").count();

        assertThat(jobs.runDailyLoss().paused()).isGreaterThanOrEqualTo(1);

        assertThat(pausedForLoss(losing)).isTrue();
        assertThat(pausedForLoss(fine)).isFalse();
        assertThat(meters.counter("real_risk_daily_loss_pauses").count()).isGreaterThan(before);
        assertThat(jobs.runDailyLoss().ran()).isTrue();
        assertThat(pausedForLoss(losing)).isTrue();
    }

    @Test
    void aWalletWithoutThePhotoOfTodayIsNotJudged() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 10 * SOL);
        solAt("200", 5);
        position(wallet, token("0.4", 6, 5), SOL, 4 * SOL);

        jobs.runDailyLoss();

        assertThat(pausedForLoss(wallet)).isFalse();
    }

    /** U3-NFR4.6: the pause lost with a cycle that rolled back is redone by the job of the loss. */
    @Test
    void thePauseLostWithARolledBackCycleIsRedoneByTheJob() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 10 * SOL);
        jobs.runDay();
        clock.advance(Duration.ofSeconds(1));
        solAt("200", 5);
        position(wallet, token("0.4", 6, 5), SOL, 4 * SOL);
        String mint = token("0.01", 6, 5);
        transactions.executeWithoutResult(status -> {
            GateDecision refused = gate.evaluate(OrderIntent.botBuy(
                    wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), mint, SOL / 2));
            assertThat(refused.approved()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(pausedForLoss(wallet)).isFalse();

        jobs.runDailyLoss();

        assertThat(pausedForLoss(wallet)).isTrue();
    }

    /** BR6.2, F5: an OPEN reservation past its 5 minutes expires once and is counted; a fresh one stays OPEN. */
    @Test
    void theExpiryClosesTheReservationsPastTheirTimeOnce() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 20 * SOL);
        String mint = token("0.01", 6, 5);
        GateDecision.Approved old = (GateDecision.Approved) transactions.execute(status -> gate.evaluate(
                OrderIntent.botBuy(wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), mint, SOL)));
        clock.advance(Duration.ofMinutes(4));
        observe(mint, "0.01", 6, 5);
        GateDecision.Approved fresh = (GateDecision.Approved) transactions.execute(status -> gate.evaluate(
                OrderIntent.botBuy(wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), mint, SOL)));
        clock.advance(Duration.ofMinutes(2));
        double before = meters.counter("real_risk_reservations_expired").count();

        assertThat(jobs.runReservationExpiry().expired()).isGreaterThanOrEqualTo(1);
        assertThat(jobs.runReservationExpiry().expired()).isZero();

        assertThat(state(old.approvalId().orElseThrow())).isEqualTo("EXPIRED");
        assertThat(state(fresh.approvalId().orElseThrow())).isEqualTo("OPEN");
        assertThat(meters.counter("real_risk_reservations_expired").count()).isGreaterThan(before);
    }

    /** U3-NFR5.8, monitoring-design.md: each successful run counts and moves the heartbeat of its job. */
    @Test
    void aSuccessfulRunCountsAndMovesTheHeartbeatOfItsJob() {
        double before = meters.counter("real_risk_job_runs", "job", "reservation-expiry")
                .count();

        jobs.runReservationExpiry();

        assertThat(meters.counter("real_risk_job_runs", "job", "reservation-expiry")
                        .count())
                .isEqualTo(before + 1);
        assertThat(meters.get("real_risk_job_last_success_seconds")
                        .tag("job", "reservation-expiry")
                        .gauge()
                        .value())
                .isEqualTo((double) clock.instant().getEpochSecond());
        assertThat(meters.get("real_risk_job")
                        .tag("job", "reservation-expiry")
                        .timer()
                        .count())
                .isPositive();
    }

    private String state(UUID approval) {
        return jdbc.sql("select state from real_risk_reservation where approval_id = :id")
                .param("id", approval)
                .query(String.class)
                .single();
    }
}
