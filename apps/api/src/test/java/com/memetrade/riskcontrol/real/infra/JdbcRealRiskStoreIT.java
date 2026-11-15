package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.api.BlockedToken;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 6 of the U3, written before the code (BR6.1, BR6.2, BR4.2, BR4.4, BR2.3, U3-NFR4.2, U3-NFR6.1): the records of
 * the barrier with {@code ON CONFLICT}, every read filtered by tenant, the count of the approved buys of a bot in one
 * UTC day, the open reservations of a wallet and the block list.
 */
class JdbcRealRiskStoreIT extends AbstractRealRiskIT {

    @Autowired
    private JdbcRealRiskStore store;

    private <T> T tx(java.util.function.Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OrderIntent buy(Wallet wallet, long lamports) {
        return OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), SyntheticIds.mint(), lamports);
    }

    private boolean record(OrderIntent intent, RealVerdict verdict, Optional<UUID> approvalId, Instant at) {
        Boolean inserted = tx(() -> store.insertEvaluation(
                new JdbcRealRiskStore.EvaluationRow(UUID.randomUUID(), intent, verdict, approvalId, at)));
        return Boolean.TRUE.equals(inserted);
    }

    private static RealVerdict approved() {
        return new RealVerdict(true, null, null, Map.of(), Optional.empty(), false, Optional.empty());
    }

    private static RealVerdict rejected() {
        return new RealVerdict(
                false,
                RealRejectionCode.STALE_DATA,
                RealRejectionDetail.POSITION_PRICE_UNKNOWN,
                Map.of("openPositions", 2L),
                Optional.of("PositionMint1111111111111111111111111111111"),
                false,
                Optional.empty());
    }

    @Test
    void aRecordedDecisionIsReadBackOnlyInItsTenantAndOnlyOnce() {
        Wallet wallet = releasedWallet(10 * SOL);
        Wallet other = releasedWallet(10 * SOL);
        OrderIntent intent = buy(wallet, SOL / 2);
        UUID approval = UUID.randomUUID();

        assertThat(record(intent, approved(), Optional.of(approval), clock.instant()))
                .isTrue();
        assertThat(record(intent, rejected(), Optional.empty(), clock.instant()))
                .as("a second row for the same decision is never written")
                .isFalse();

        GateDecision stored =
                store.decisionOf(wallet.tenantId(), intent.decisionId()).orElseThrow();
        assertThat(stored)
                .isInstanceOfSatisfying(GateDecision.Approved.class, decision -> assertThat(decision.approvalId())
                        .contains(approval));
        assertThat(store.decisionOf(other.tenantId(), intent.decisionId())).isEmpty();
    }

    @Test
    void aRejectionKeepsItsCodeDetailFiguresAndPositionMint() {
        Wallet wallet = releasedWallet(10 * SOL);
        OrderIntent intent = buy(wallet, SOL / 2);
        record(intent, rejected(), Optional.empty(), clock.instant());

        assertThat(store.decisionOf(wallet.tenantId(), intent.decisionId()).orElseThrow())
                .isInstanceOfSatisfying(GateDecision.Rejected.class, decision -> {
                    assertThat(decision.code()).isEqualTo(RealRejectionCode.STALE_DATA);
                    assertThat(decision.detail()).isEqualTo(RealRejectionDetail.POSITION_PRICE_UNKNOWN);
                    assertThat(decision.figures()).containsExactly(Map.entry("openPositions", 2L));
                    assertThat(decision.detailMint()).contains("PositionMint1111111111111111111111111111111");
                });
    }

    @Test
    void theApprovedBuysAreCountedPerBotPerUtcDayAndTenant() {
        Wallet wallet = releasedWallet(10 * SOL);
        Instant now = clock.instant();
        record(buy(wallet, 1), approved(), Optional.of(UUID.randomUUID()), now);
        record(buy(wallet, 1), approved(), Optional.of(UUID.randomUUID()), now);
        record(buy(wallet, 1), rejected(), Optional.empty(), now);
        record(buy(wallet, 1), approved(), Optional.of(UUID.randomUUID()), now.minus(Duration.ofDays(1)));
        record(
                OrderIntent.botSell(
                        wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), SyntheticIds.mint(), 10),
                approved(),
                Optional.empty(),
                now);

        assertThat(store.approvedBuys(wallet.tenantId(), wallet.botId(), today()))
                .isEqualTo(2);
        assertThat(store.approvedBuys(UUID.randomUUID(), wallet.botId(), today()))
                .isZero();
    }

    @Test
    void onlyTheOpenReservationsOfTheWalletCountAndAClosedOneNeverReopens() {
        Wallet wallet = releasedWallet(10 * SOL);
        Instant now = clock.instant();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            store.insertReservation(reservation(wallet, first, 300, now));
            store.insertReservation(reservation(wallet, second, 200, now));
        });

        assertThat(store.openReservedLamports(wallet.tenantId(), wallet.botWalletId()))
                .isEqualTo(500);
        assertThat(tx(() -> store.closeReservation(first, "HANDED_OVER", now))).isTrue();
        assertThat(tx(() -> store.closeReservation(first, "EXPIRED", now))).isFalse();
        assertThat(store.openReservedLamports(wallet.tenantId(), wallet.botWalletId()))
                .isEqualTo(200);
        assertThat(store.openReservedLamports(UUID.randomUUID(), wallet.botWalletId()))
                .isZero();
        assertThat(store.reservation(wallet.tenantId(), first).orElseThrow().state())
                .isEqualTo("HANDED_OVER");
        assertThat(store.reservation(UUID.randomUUID(), first)).isEmpty();
    }

    @Test
    void theExpiryClosesOnlyOpenReservationsPastTheirTime() {
        Wallet wallet = releasedWallet(10 * SOL);
        Instant now = clock.instant();
        UUID due = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            store.insertReservation(reservation(wallet, due, 300, now.minus(Duration.ofMinutes(6))));
            store.insertReservation(reservation(wallet, fresh, 200, now));
        });

        Integer expired = tx(() -> store.expireReservations(now));

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(store.reservation(wallet.tenantId(), due).orElseThrow().state())
                .isEqualTo("EXPIRED");
        assertThat(store.reservation(wallet.tenantId(), fresh).orElseThrow().state())
                .isEqualTo("OPEN");
    }

    @Test
    void thePhotoOfTheDayIsWrittenOnceAndTheFirstOneStays() {
        Wallet wallet = releasedWallet(10 * SOL);
        DaySnapshot first = new DaySnapshot(
                wallet.tenantId(),
                wallet.botWalletId(),
                today(),
                10 * SOL,
                10 * SOL,
                clock.instant(),
                DaySnapshot.TakenBy.DAY_JOB);
        DaySnapshot second = new DaySnapshot(
                wallet.tenantId(),
                wallet.botWalletId(),
                today(),
                SOL,
                SOL,
                clock.instant(),
                DaySnapshot.TakenBy.FIRST_EVALUATION);

        assertThat(tx(() -> store.insertSnapshot(first))).isTrue();
        assertThat(tx(() -> store.insertSnapshot(second))).isFalse();
        assertThat(store.snapshot(wallet.tenantId(), wallet.botWalletId(), today()))
                .contains(first);
        assertThat(store.snapshot(UUID.randomUUID(), wallet.botWalletId(), today()))
                .isEmpty();
    }

    @Test
    void theBlockListIsGlobalAndIdempotent() {
        String mint = SyntheticIds.mint();
        UUID admin = UUID.randomUUID();
        BlockedToken token = new BlockedToken("solana", mint, "rug pull", admin, clock.instant());

        assertThat(tx(() -> store.block(token))).isTrue();
        assertThat(tx(() -> store.block(new BlockedToken("solana", mint, "other", admin, clock.instant()))))
                .isFalse();
        assertThat(store.isBlocked("solana", mint)).isTrue();
        assertThat(store.blocked("solana", mint)).contains(token);
        assertThat(tx(() -> store.unblock("solana", mint))).contains(token);
        assertThat(tx(() -> store.unblock("solana", mint))).isEmpty();
        assertThat(store.isBlocked("solana", mint)).isFalse();
    }

    private static JdbcRealRiskStore.ReservationRow reservation(
            Wallet wallet, UUID approval, long lamports, Instant at) {
        return new JdbcRealRiskStore.ReservationRow(
                approval,
                wallet.tenantId(),
                wallet.userId(),
                wallet.botWalletId(),
                wallet.botId(),
                UUID.randomUUID(),
                SyntheticIds.mint(),
                lamports,
                at,
                at.plus(Duration.ofMinutes(5)));
    }
}
