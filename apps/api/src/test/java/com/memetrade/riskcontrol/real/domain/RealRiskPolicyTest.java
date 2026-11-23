package com.memetrade.riskcontrol.real.domain;

import static com.memetrade.riskcontrol.real.domain.PolicyFixture.NOW;
import static com.memetrade.riskcontrol.real.domain.PolicyFixture.SOL;
import static com.memetrade.riskcontrol.real.domain.PolicyFixture.known;
import static com.memetrade.riskcontrol.real.domain.PolicyFixture.lamports;
import static com.memetrade.riskcontrol.real.domain.PolicyFixture.unknown;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Step 4 of the U3, written before the code from the business rules (rules.md BR1.1 to BR5.3, BR7.2; functional-spec.md
 * scenarios S1 to S17, S4b, S4c, S5b and S5c; team.md mandatory tests "violação de limites", "pausa do bot impede novas
 * operações", "dado de mercado ausente vira unknown, nunca zero" and "separação entre REAL e PAPER"). The policy is pure:
 * every input, the instant included, comes in {@link RealGateInputs}.
 */
class RealRiskPolicyTest {

    private final RealRiskPolicy policy = new RealRiskPolicy();

    private static void assertRejected(RealVerdict verdict, RealRejectionCode code, RealRejectionDetail detail) {
        assertThat(verdict.approved()).as("approved").isFalse();
        assertThat(verdict.code()).isEqualTo(code);
        assertThat(verdict.detail()).isEqualTo(detail);
    }

    @Test
    void theDefaultBuyOfTheFixtureIsApproved() {
        PolicyFixture f = new PolicyFixture();

        RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

        assertThat(verdict.approved()).isTrue();
        assertThat(verdict.pauseForDailyLoss()).isFalse();
        assertThat(verdict.snapshotToRecord()).isEmpty();
    }

    @Nested
    class StateBR11 {

        @Test
        void withoutABotWalletOfThatTenantEveryOrderIsRefused() {
            PolicyFixture f = new PolicyFixture();
            f.hasWallet = false;

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.NO_BOT_WALLET);
            assertRejected(
                    policy.evaluate(f.inputs(f.liquidation())),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.NO_BOT_WALLET);
        }

        @Test
        void theRealSwitchedOffRefusesEvenTheLiquidation() {
            PolicyFixture f = new PolicyFixture();
            f.realEnabled = false;

            assertRejected(
                    policy.evaluate(f.inputs(f.liquidation())),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.REAL_DISABLED);
        }

        @Test
        void aReleaseNotInForceRefusesTheOrder() {
            PolicyFixture f = new PolicyFixture();
            f.released = false;

            assertRejected(
                    policy.evaluate(f.inputs(f.botSell())),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.NOT_RELEASED);
        }

        @ParameterizedTest
        @EnumSource(RealPauseReason.class)
        void everyPauseStopsTheBuysAndTheSellsOfTheBot(RealPauseReason reason) {
            PolicyFixture f = new PolicyFixture();
            f.pauses.add(reason);
            RealRejectionDetail expected = RealRejectionDetail.valueOf("PAUSED_" + reason.name());

            assertRejected(policy.evaluate(f.inputs(f.buy(SOL / 2))), RealRejectionCode.REAL_PAUSED, expected);
            assertRejected(policy.evaluate(f.inputs(f.botSell())), RealRejectionCode.REAL_PAUSED, expected);
        }

        /** S17: a USER pause stops the sell of the bot; the liquidation asked by the person passes. */
        @ParameterizedTest
        @EnumSource(
                value = RealPauseReason.class,
                names = {"USER", "DAILY_LOSS", "ADMIN", "UPSTREAM"})
        void theLiquidationOfThePersonPassesThePausesItMayPass(RealPauseReason reason) {
            PolicyFixture f = new PolicyFixture();
            f.pauses.add(reason);

            assertThat(policy.evaluate(f.inputs(f.liquidation())).approved()).isTrue();
        }

        /** S16: the kill switch stops even "vender tudo e sacar"; the withdrawal goes on through the U1. */
        @ParameterizedTest
        @EnumSource(
                value = RealPauseReason.class,
                names = {"KILL_SWITCH", "DIVERGENCE"})
        void theKillSwitchAndADivergenceStopTheLiquidation(RealPauseReason reason) {
            PolicyFixture f = new PolicyFixture();
            f.pauses.add(reason);

            assertRejected(
                    policy.evaluate(f.inputs(f.liquidation())),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.valueOf("PAUSED_" + reason.name()));
        }

        @Test
        void withSeveralPausesTheMostSevereIsReported() {
            PolicyFixture f = new PolicyFixture();
            f.pauses.addAll(EnumSet.of(RealPauseReason.USER, RealPauseReason.KILL_SWITCH, RealPauseReason.ADMIN));

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.PAUSED_KILL_SWITCH);
            f.pauses.remove(RealPauseReason.KILL_SWITCH);
            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.PAUSED_ADMIN);
        }
    }

    @Nested
    class SourceBR12AndOrderBR13 {

        /** S11. */
        @Test
        void aSimulatedSourceRefusesBuysAndSells() {
            PolicyFixture f = new PolicyFixture();
            f.source = SourceKind.SIMULATED;

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.SIMULATED_SOURCE_ACTIVE,
                    RealRejectionDetail.NONE);
            assertRejected(
                    policy.evaluate(f.inputs(f.liquidation())),
                    RealRejectionCode.SIMULATED_SOURCE_ACTIVE,
                    RealRejectionDetail.NONE);
        }

        @Test
        void theFirstFailedCheckInTheFixedOrderIsTheAnswer() {
            PolicyFixture f = new PolicyFixture();
            f.pauses.add(RealPauseReason.USER);
            f.source = SourceKind.SIMULATED;
            f.assessment = new AssessmentResult.Unavailable(UnavailableReason.INSUFFICIENT_COVERAGE);
            f.blocked = true;
            f.price = unknown();
            f.capLamports = 0;
            f.approvedBuysToday = 99;
            f.totalLamports = 0;

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL))),
                    RealRejectionCode.REAL_PAUSED,
                    RealRejectionDetail.PAUSED_USER);
            f.pauses.clear();
            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).code())
                    .isEqualTo(RealRejectionCode.SIMULATED_SOURCE_ACTIVE);
            f.source = SourceKind.REAL;
            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).detail()).isEqualTo(RealRejectionDetail.NO_ASSESSMENT);
            f.assessment = new AssessmentResult.Available(10, com.memetrade.riskassessment.api.RiskBand.LOW, 1);
            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).detail()).isEqualTo(RealRejectionDetail.BLOCKED);
            f.blocked = false;
            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).detail()).isEqualTo(RealRejectionDetail.SIGNAL_UNKNOWN);
            f.price = known("0.0001", 1);
            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).detail()).isEqualTo(RealRejectionDetail.ORDER_SIZE);
        }

        @Test
        void aSellThatPassesStateAndSourceIsApprovedWithoutTheOtherChecks() {
            PolicyFixture f = new PolicyFixture();
            f.blocked = true;
            f.assessment = new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE);
            f.price = unknown();
            f.liquidity = known("1", 3600);
            f.capLamports = 0;
            f.approvedBuysToday = 99;

            RealVerdict verdict = policy.evaluate(f.inputs(f.botSell()));

            assertThat(verdict.approved()).isTrue();
            assertThat(verdict.figures()).isEmpty();
        }
    }

    @Nested
    class TokensBR21AndBR22 {

        /** S8; a technical failure of the assessment arrives here as Unavailable and is never approved. */
        @Test
        void aTokenWithoutAssessmentIsNotBought() {
            PolicyFixture f = new PolicyFixture();
            f.assessment = new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE);

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.TOKEN_NOT_ELIGIBLE,
                    RealRejectionDetail.NO_ASSESSMENT);
        }

        /** S10. */
        @Test
        void aBlockedTokenIsNotBoughtButItsSaleIsApproved() {
            PolicyFixture f = new PolicyFixture();
            f.blocked = true;

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.TOKEN_NOT_ELIGIBLE,
                    RealRejectionDetail.BLOCKED);
            assertThat(policy.evaluate(f.inputs(f.botSell())).approved()).isTrue();
        }
    }

    @Nested
    class FreshnessBR31AndBR24 {

        /** S12. */
        @Test
        void aPriceOf61SecondsIsStale() {
            PolicyFixture f = new PolicyFixture();
            f.price = known("0.0001", 61);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

            assertRejected(verdict, RealRejectionCode.STALE_DATA, RealRejectionDetail.SIGNAL_STALE);
            assertThat(verdict.figures()).containsEntry("ageSeconds", 61L).containsEntry("maxAgeSeconds", 60L);
        }

        @Test
        void exactly60SecondsIsStillFresh() {
            PolicyFixture f = new PolicyFixture();
            f.price = known("0.0001", 60);
            f.liquidity = known("1", 60);
            f.volume = known("1", 60);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();
        }

        @Test
        void anUnknownLiquidityOrVolumeIsNeverTakenAsZero() {
            PolicyFixture f = new PolicyFixture();
            f.liquidity = unknown();
            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.STALE_DATA,
                    RealRejectionDetail.SIGNAL_UNKNOWN);

            f.liquidity = known("1", 1);
            f.volume = unknown();
            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.STALE_DATA,
                    RealRejectionDetail.SIGNAL_UNKNOWN);
        }

        @Test
        void anUnknownSignalIsReportedBeforeAStaleOne() {
            PolicyFixture f = new PolicyFixture();
            f.price = known("0.0001", 3600);
            f.volume = unknown();

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).detail())
                    .isEqualTo(RealRejectionDetail.SIGNAL_UNKNOWN);
        }

        /** S9: the unknown price of an open position blocks the buy of ANOTHER token and names that position. */
        @Test
        void anOpenPositionWithAnUnknownPriceBlocksEveryBuy() {
            PolicyFixture f = new PolicyFixture();
            String held = SyntheticIds.mint();
            f.position(held, 1_000, SOL, new LamportPrice.Unknown());

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

            assertRejected(verdict, RealRejectionCode.STALE_DATA, RealRejectionDetail.POSITION_PRICE_UNKNOWN);
            assertThat(verdict.detailMint()).contains(held);
        }

        @Test
        void anOpenPositionWithAStalePriceBlocksTheBuyButNotTheSell() {
            PolicyFixture f = new PolicyFixture();
            String held = SyntheticIds.mint();
            f.position(held, 1_000, SOL, lamports("1000", 61));

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(SOL / 2))),
                    RealRejectionCode.STALE_DATA,
                    RealRejectionDetail.POSITION_PRICE_UNKNOWN);
            assertThat(policy.evaluate(f.inputs(f.botSell())).approved()).isTrue();
        }
    }

    @Nested
    class LimitsBR41AndBR42 {

        /** S1. */
        @Test
        void aBuyAbove10PercentOfTheCapIsRefused() {
            PolicyFixture f = new PolicyFixture();

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(1_100_000_000L)));

            assertRejected(verdict, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.ORDER_SIZE);
            assertThat(verdict.figures())
                    .containsEntry("inputLamports", 1_100_000_000L)
                    .containsEntry("limitLamports", SOL)
                    .containsEntry("capLamports", 10 * SOL);
        }

        @Test
        void aBuyOfExactly10PercentPasses() {
            PolicyFixture f = new PolicyFixture();

            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).approved()).isTrue();
        }

        @Test
        void theLimitIsRoundedDownAgainstTheBuy() {
            PolicyFixture f = new PolicyFixture();
            f.capLamports = 9_999;
            f.snapshotStartCap = 9_999;

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(1_000)));

            assertRejected(verdict, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.ORDER_SIZE);
            assertThat(verdict.figures()).containsEntry("limitLamports", 999L);
        }

        /** S4c: with a cap of zero the limit per order is zero; the loss of the day does not apply and nothing pauses. */
        @Test
        void aCapOfZeroRefusesEveryBuyWithoutPausing() {
            PolicyFixture f = new PolicyFixture();
            f.capLamports = 0;
            f.totalLamports = 0;

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(1)));

            assertRejected(verdict, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.ORDER_SIZE);
            assertThat(verdict.figures()).containsEntry("limitLamports", 0L);
            assertThat(verdict.pauseForDailyLoss()).isFalse();
        }

        /** S2. */
        @Test
        void the21stBuyOfTheDayIsRefusedAndThe20thPasses() {
            PolicyFixture f = new PolicyFixture();
            f.approvedBuysToday = 19;
            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();

            f.approvedBuysToday = 20;
            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));
            assertRejected(verdict, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.DAILY_ORDER_COUNT);
            assertThat(verdict.figures())
                    .containsEntry("approvedBuysToday", 20L)
                    .containsEntry("dailyBuysMax", 20L);
        }

        @Test
        void sellsNeverCountNorAreLimited() {
            PolicyFixture f = new PolicyFixture();
            f.approvedBuysToday = 20;

            assertThat(policy.evaluate(f.inputs(f.botSell())).approved()).isTrue();
        }
    }

    @Nested
    class DailyLossBR43AndBR44 {

        /** S3. */
        @Test
        void aLossOf20PercentRefusesAndPauses() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.balance(8 * SOL);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

            assertRejected(verdict, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.DAILY_LOSS);
            assertThat(verdict.pauseForDailyLoss()).isTrue();
            assertThat(verdict.figures())
                    .containsEntry("lossLamports", 2 * SOL)
                    .containsEntry("baseLamports", 10 * SOL)
                    .containsEntry("dailyLossMaxBps", 2000L);
        }

        /** S4: a deposit after the photo is not a gain: 10 + 2 - 10.5 = 1.5 SOL, below 2 SOL. */
        @Test
        void aDepositAfterThePhotoIsDiscountedFromTheLoss() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.flows = new RealFlows(2 * SOL, 0);
            f.balance(10_500_000_000L);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();
        }

        /**
         * S4b: the photo of 09:00 already includes the deposit of 08:00, and the flows come from after the photo only,
         * so the deposit is not counted twice: 12 - 11 = 1 SOL of loss on a base of 10 SOL, below 2 SOL.
         */
        @Test
        void aDepositBeforeThePhotoIsAlreadyInsideIt() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(12 * SOL);
            f.flows = RealFlows.NONE;
            f.balance(11 * SOL);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();
        }

        @Test
        void aWithdrawalIsNotALoss() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.flows = new RealFlows(0, 3 * SOL);
            f.balance(7 * SOL);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();
        }

        @Test
        void theThresholdIsInclusiveAndOneLamportLessPasses() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.balance(8 * SOL + 1);
            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();

            f.balance(8 * SOL);
            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).pauseForDailyLoss())
                    .isTrue();
        }

        @Test
        void aLowerCapDuringTheDayIsTheBaseAtOnceAndAHigherOneOnlyTomorrow() {
            PolicyFixture lowered = new PolicyFixture();
            lowered.snapshotStartValue = java.util.Optional.of(10 * SOL);
            lowered.capLamports = 5 * SOL;
            lowered.balance(9 * SOL);
            RealVerdict refused = policy.evaluate(lowered.inputs(lowered.buy(SOL / 4)));
            assertRejected(refused, RealRejectionCode.REAL_LIMIT_EXCEEDED, RealRejectionDetail.DAILY_LOSS);
            assertThat(refused.figures()).containsEntry("baseLamports", 5 * SOL);

            PolicyFixture raised = new PolicyFixture();
            raised.snapshotStartValue = java.util.Optional.of(10 * SOL);
            raised.snapshotStartCap = 5 * SOL;
            raised.capLamports = 10 * SOL;
            raised.balance(9 * SOL);
            assertThat(policy.evaluate(raised.inputs(raised.buy(SOL / 4))).detail())
                    .isEqualTo(RealRejectionDetail.DAILY_LOSS);
        }

        @Test
        void aGainOfTheDayNeverPauses() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.balance(15 * SOL);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).approved()).isTrue();
        }

        /** BR4.4: the first buy of the day without a photo records it with the value and the cap of that moment. */
        @Test
        void theFirstBuyWithoutAPhotoAsksToRecordItAndHasNoLossYet() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.empty();
            f.balance(7 * SOL);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

            assertThat(verdict.approved()).isTrue();
            DaySnapshot photo = verdict.snapshotToRecord().orElseThrow();
            assertThat(photo.startValueLamports()).isEqualTo(7 * SOL);
            assertThat(photo.startCapLamports()).isEqualTo(10 * SOL);
            assertThat(photo.day()).isEqualTo(LocalDate.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(photo.takenAt()).isEqualTo(NOW);
            assertThat(photo.takenBy()).isEqualTo(DaySnapshot.TakenBy.FIRST_EVALUATION);
            assertThat(photo.botWalletId()).isEqualTo(f.botWalletId);
            assertThat(photo.tenantId()).isEqualTo(f.tenantId);
        }

        @Test
        void aBuyRefusedBeforeTheLossCheckRecordsNoPhoto() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.empty();
            f.approvedBuysToday = 20;

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).snapshotToRecord())
                    .isEmpty();
        }

        @Test
        void thePositionsAtTheirQuotedValueArePartOfTheCurrentValue() {
            PolicyFixture f = new PolicyFixture();
            f.snapshotStartValue = java.util.Optional.of(10 * SOL);
            f.balance(5 * SOL);
            // 4 SOL in a token, valued at the quote: 9 SOL of value, 1 SOL of loss, below 2 SOL.
            f.position(SyntheticIds.mint(), 4_000, 6 * SOL, lamports("1000000", 1));

            assertThat(policy.evaluate(f.inputs(f.buy(SOL / 4))).approved()).isTrue();
        }
    }

    @Nested
    class CapBR51ToBR53 {

        /** S5b: an ambiguous order without a fill still counts in the cap through its RESERVE. */
        @Test
        void anAmbiguousOrderCountsInTheCap() {
            PolicyFixture f = new PolicyFixture();
            f.totalLamports = 20 * SOL;
            f.orderReservedLamports = SOL;
            f.position(SyntheticIds.mint(), 8_500, 8 * SOL, lamports("1000000", 1));
            f.snapshotStartValue = java.util.Optional.of(28_500_000_000L);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(900_000_000L)));

            assertRejected(verdict, RealRejectionCode.REAL_CAP_EXCEEDED, RealRejectionDetail.CAP);
            assertThat(verdict.figures())
                    .containsEntry("managedLamports", 9_500_000_000L)
                    .containsEntry("inputLamports", 900_000_000L)
                    .containsEntry("capLamports", 10 * SOL);
        }

        /** S5 at the level of the policy: the reservation of the first approved buy holds the margin of the second. */
        @Test
        void anOpenReservationOfTheBarrierHoldsItsShareOfTheCap() {
            PolicyFixture f = new PolicyFixture();
            f.position(SyntheticIds.mint(), 9_000, 9 * SOL, lamports("1000000", 1));
            f.snapshotStartValue = java.util.Optional.of(29 * SOL);
            assertThat(policy.evaluate(f.inputs(f.buy(600_000_000L))).approved())
                    .isTrue();

            f.openGateReservedLamports = 600_000_000L;
            assertRejected(
                    policy.evaluate(f.inputs(f.buy(600_000_000L))),
                    RealRejectionCode.REAL_CAP_EXCEEDED,
                    RealRejectionDetail.CAP);
        }

        /** S7: only the REAL ledger counts; a PAPER balance never reaches the barrier. */
        @Test
        void withoutRealBalanceTheBuyIsRefusedForFreeBalance() {
            PolicyFixture f = new PolicyFixture();
            f.totalLamports = 0;
            f.snapshotStartValue = java.util.Optional.of(0L);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(SOL / 2)));

            assertRejected(verdict, RealRejectionCode.REAL_CAP_EXCEEDED, RealRejectionDetail.FREE_BALANCE);
            assertThat(verdict.figures()).containsEntry("availableLamports", -30_000_000L);
        }

        /** S6: the RESERVE of an order already left the free balance; with the fee reserve the buy no longer fits. */
        @Test
        void theFeeReserveTheOrderReservesAndTheOpenReservationsLeaveTheFreeBalance() {
            PolicyFixture f = new PolicyFixture();
            f.totalLamports = 2 * SOL;
            f.orderReservedLamports = SOL;
            f.openGateReservedLamports = 400_000_000L;
            f.snapshotStartValue = java.util.Optional.of(2 * SOL);

            RealVerdict verdict = policy.evaluate(f.inputs(f.buy(600_000_000L)));

            assertRejected(verdict, RealRejectionCode.REAL_CAP_EXCEEDED, RealRejectionDetail.FREE_BALANCE);
            assertThat(verdict.figures()).containsEntry("availableLamports", 570_000_000L);
        }

        /** S13: a cap lowered under the exposure refuses buys only; nothing is sold by force and sells pass. */
        @Test
        void aCapBelowTheExposureBlocksOnlyBuys() {
            PolicyFixture f = new PolicyFixture();
            f.capLamports = 5 * SOL;
            f.snapshotStartCap = 5 * SOL;
            f.position(SyntheticIds.mint(), 9_000, 9 * SOL, lamports("1000000", 1));
            f.snapshotStartValue = java.util.Optional.of(29 * SOL);

            assertRejected(
                    policy.evaluate(f.inputs(f.buy(100_000_000L))),
                    RealRejectionCode.REAL_CAP_EXCEEDED,
                    RealRejectionDetail.CAP);
            assertThat(policy.evaluate(f.inputs(f.botSell())).approved()).isTrue();
        }

        /** S15: a free balance above the cap never enters the bot. */
        @Test
        void theBalanceAboveTheCapIsNeverUsed() {
            PolicyFixture f = new PolicyFixture();
            f.totalLamports = 12 * SOL;
            f.snapshotStartValue = java.util.Optional.of(12 * SOL);

            assertThat(policy.evaluate(f.inputs(f.buy(SOL))).approved()).isTrue();
        }
    }

    @Nested
    class ValuationBR52AndBR72 {

        /** S14: a position with an unknown price is worth its KNOWN cost, never zero. */
        @Test
        void anUnknownPriceValuesThePositionAtCost() {
            assertThat(RealRiskPolicy.positionsValue(java.util.List.of(
                            new PricedPosition(SyntheticIds.mint(), 1_000, 2 * SOL, new LamportPrice.Unknown()))))
                    .isEqualTo(new PositionsValue.Known(2 * SOL));
        }

        /**
         * 261121-paper-real-unico U2, Etapa 12 (written before the code; F-3, BR2.7): an unknown price with an unknown
         * cost leaves the value of the positions unknown, with the reason of the cost, never zero.
         */
        @Test
        void anUnknownPriceWithAnUnknownCostLeavesTheValueUnknownNeverZero() {
            assertThat(RealRiskPolicy.positionsValue(java.util.List.of(
                            new PricedPosition(SyntheticIds.mint(), 3, 7, lamports("0.5", 1)),
                            new PricedPosition(
                                    SyntheticIds.mint(),
                                    1_000,
                                    new com.memetrade.ledger.api.RealCost.Unknown(
                                            com.memetrade.ledger.api.RealCostUnknownReason.TIMEOUT),
                                    new LamportPrice.Unknown()))))
                    .isEqualTo(new PositionsValue.Unknown(com.memetrade.ledger.api.RealCostUnknownReason.TIMEOUT));
        }

        /** 261121-paper-real-unico U2 (F-3): a quoted position never needs its cost, even when it was not read. */
        @Test
        void aQuotedPositionDoesNotNeedItsCost() {
            assertThat(RealRiskPolicy.positionsValue(java.util.List.of(new PricedPosition(
                            SyntheticIds.mint(),
                            10,
                            new com.memetrade.ledger.api.RealCost.Unknown(
                                    com.memetrade.ledger.api.RealCostUnknownReason.NOT_READ),
                            lamports("2.25", 1)))))
                    .isEqualTo(new PositionsValue.Known(22));
        }

        @Test
        void aQuotedPositionIsRoundedDownInLamports() {
            assertThat(RealRiskPolicy.positionsValue(java.util.List.of(
                            new PricedPosition(SyntheticIds.mint(), 3, 7, lamports("0.5", 1)),
                            new PricedPosition(SyntheticIds.mint(), 10, 1, lamports("2.25", 120)))))
                    .isEqualTo(new PositionsValue.Known(1 + 22));
        }

        /**
         * 261121-paper-real-unico U2, Etapa 12 (written before the code; F-4, DaySnapshotDeferredIT): with the mark of
         * the photo deferred today and no photo, the buy is refused (price of a position, detail of the pending photo)
         * and records no FIRST_EVALUATION photo; without the mark the first buy of the day still takes it.
         */
        @Test
        void aDeferredPhotoOfTheDayRefusesTheBuyWithoutTakingAPhoto() {
            PolicyFixture deferred = new PolicyFixture();
            deferred.snapshotStartValue = java.util.Optional.empty();
            deferred.dayPhotoDeferred = true;
            PolicyFixture normal = new PolicyFixture();
            normal.snapshotStartValue = java.util.Optional.empty();

            RealVerdict refused = policy.evaluate(deferred.inputs(deferred.buy(SOL)));
            RealVerdict approved = policy.evaluate(normal.inputs(normal.buy(SOL)));

            assertThat(refused.approved()).isFalse();
            assertThat(refused.code()).isEqualTo(RealRejectionCode.STALE_DATA);
            assertThat(refused.detail()).isEqualTo(RealRejectionDetail.POSITION_PRICE_UNKNOWN);
            assertThat(refused.figures()).containsEntry(RealRiskPolicy.DAY_SNAPSHOT_PENDING, 1L);
            assertThat(refused.snapshotToRecord()).isEmpty();
            assertThat(approved.approved()).isTrue();
            assertThat(approved.snapshotToRecord()).hasValueSatisfying(photo -> assertThat(photo.takenBy())
                    .isEqualTo(DaySnapshot.TakenBy.FIRST_EVALUATION));
        }

        @Test
        void theQuoteInDollarsBecomesLamportsPerBaseUnitWithTheSolPriceAndTheDecimals() {
            LamportPrice price = LamportPrice.from(known("0.0002", 5), known("200", 20), known("6", 3600));

            assertThat(price).isInstanceOfSatisfying(LamportPrice.Known.class, quoted -> {
                assertThat(quoted.lamportsPerBaseUnit()).isEqualByComparingTo("0.001");
                assertThat(quoted.observedAt()).isEqualTo(NOW.minusSeconds(20));
            });
        }

        @Test
        void aMissingTokenPriceSolPriceOrDecimalsGivesAnUnknownPriceNeverZero() {
            assertThat(LamportPrice.from(unknown(), known("200", 1), known("6", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), unknown(), known("6", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), unknown()))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), known("0", 1), known("6", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
        }

        /** Review R-03: a positive quote too small for 18 places is unknown, never a known zero. */
        @Test
        void aPositiveQuoteThatRoundsToZeroIsUnknownNeverAKnownZero() {
            assertThat(LamportPrice.from(known("0.000000000000000001", 1), known("200", 1), known("18", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("0", 1), known("200", 1), known("18", 1)))
                    .as("a quote of exactly zero is a known zero")
                    .isInstanceOfSatisfying(LamportPrice.Known.class, price -> assertThat(
                                    price.lamportsPerBaseUnit().signum())
                            .isZero());
        }

        /** Review R-03: decimals that are not an integer from 0 to 18 make the price unknown and never throw. */
        @Test
        void decimalsOutsideTheRangeOrFractionalGiveAnUnknownPriceWithoutThrowing() {
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), known("19", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), known("-1", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), known("6.5", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), known("6.000", 1)))
                    .as("6.000 is the integer 6")
                    .isInstanceOf(LamportPrice.Known.class);
            assertThat(LamportPrice.from(known("1", 1), known("200", 1), known("99999999999", 1)))
                    .isInstanceOf(LamportPrice.Unknown.class);
        }

        @Test
        void theStaleQuoteOfAPositionStillValuesIt() {
            assertThat(RealRiskPolicy.positionsValue(
                            java.util.List.of(new PricedPosition(SyntheticIds.mint(), 1_000, 5, lamports("3", (int)
                                    Duration.ofHours(2).toSeconds())))))
                    .isEqualTo(new PositionsValue.Known(3_000));
        }
    }
}
