package com.memetrade.riskcontrol.real.domain;

import static com.memetrade.riskcontrol.real.domain.PolicyFixture.SOL;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import java.util.EnumSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Step 5 of the U5 of 261113-trading-real-bot, written BEFORE the code (logical-components.md "Mudanças em outras
 * Units", iteration 2; BR2.5, BR3.1): the barrier of the U3 refuses the global pauses of the U5 as {@code REAL_PAUSED}
 * with the details {@code PAUSED_RESTORE} and {@code PAUSED_RESTORE_CHECK_PENDING} (built as "PAUSED_" + reason), and
 * the liquidation asked by the person stops in them like in the kill switch and a divergence.
 */
@Tag("u5-real-operations")
class RealRiskPolicyGlobalPausesTest {

    private final RealRiskPolicy policy = new RealRiskPolicy();

    @ParameterizedTest
    @EnumSource(
            value = RealPauseReason.class,
            names = {"RESTORE", "RESTORE_CHECK_PENDING", "KILL_SWITCH"})
    void aGlobalPauseRefusesEveryBuyAndSellOfTheBot(RealPauseReason reason) {
        PolicyFixture f = new PolicyFixture();
        f.pauses.add(reason);
        RealRejectionDetail expected = RealRejectionDetail.valueOf("PAUSED_" + reason.name());

        RealVerdict buy = policy.evaluate(f.inputs(f.buy(SOL / 2)));
        RealVerdict sell = policy.evaluate(f.inputs(f.botSell()));

        assertThat(buy.approved()).isFalse();
        assertThat(buy.code()).isEqualTo(RealRejectionCode.REAL_PAUSED);
        assertThat(buy.detail()).isEqualTo(expected);
        assertThat(sell.detail()).isEqualTo(expected);
    }

    /** logical-components.md iteration 2: the liquidation is refused in RESTORE and RESTORE_CHECK_PENDING. */
    @ParameterizedTest
    @EnumSource(
            value = RealPauseReason.class,
            names = {"RESTORE", "RESTORE_CHECK_PENDING"})
    void theRestorePausesAlsoStopTheLiquidation(RealPauseReason reason) {
        PolicyFixture f = new PolicyFixture();
        f.pauses.add(reason);

        RealVerdict liquidation = policy.evaluate(f.inputs(f.liquidation()));

        assertThat(liquidation.approved()).isFalse();
        assertThat(liquidation.detail()).isEqualTo(RealRejectionDetail.valueOf("PAUSED_" + reason.name()));
    }

    @Test
    void theKillSwitchComesFirstThenTheRestoreThenItsCheckThenTheDivergence() {
        PolicyFixture f = new PolicyFixture();
        f.pauses.addAll(EnumSet.of(
                RealPauseReason.DIVERGENCE,
                RealPauseReason.RESTORE_CHECK_PENDING,
                RealPauseReason.RESTORE,
                RealPauseReason.KILL_SWITCH));

        assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).detail())
                .isEqualTo(RealRejectionDetail.PAUSED_KILL_SWITCH);
        f.pauses.remove(RealPauseReason.KILL_SWITCH);
        assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).detail()).isEqualTo(RealRejectionDetail.PAUSED_RESTORE);
        f.pauses.remove(RealPauseReason.RESTORE);
        assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).detail())
                .isEqualTo(RealRejectionDetail.PAUSED_RESTORE_CHECK_PENDING);
        f.pauses.remove(RealPauseReason.RESTORE_CHECK_PENDING);
        assertThat(policy.evaluate(f.inputs(f.buy(SOL / 2))).detail()).isEqualTo(RealRejectionDetail.PAUSED_DIVERGENCE);
    }

    @Test
    void everyPauseReasonHasItsPlaceInTheSeverity() {
        assertThat(RealRiskPolicy.SEVERITY).containsExactlyInAnyOrder(RealPauseReason.values());
    }
}
