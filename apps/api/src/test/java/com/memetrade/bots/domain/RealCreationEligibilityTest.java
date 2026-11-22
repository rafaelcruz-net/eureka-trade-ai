package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.RealCreationEligibility.Release;
import com.memetrade.bots.domain.RealCreationEligibility.Sources;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 261121-paper-real-unico U1 (BR2.1 to BR2.3, AC1.2.x): the strongest reason of the REAL eligibility, written before
 * the code from the business rule. Order: KILL_SWITCH, REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED, QUARANTINE; the
 * first reason found ends the evaluation (no later source is read).
 */
class RealCreationEligibilityTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-11-22T13:30:00Z");

    /** Sources where each reason in {@code reasons} holds and every other one does not; records what was read. */
    private static class Fixed implements Sources {
        private final Set<RealIneligibilityReason> reasons;
        private final String releaseWhenNotReleased;
        private final List<String> reads = new ArrayList<>();

        Fixed(Set<RealIneligibilityReason> reasons) {
            this(reasons, "NONE");
        }

        Fixed(Set<RealIneligibilityReason> reasons, String releaseWhenNotReleased) {
            this.reasons = reasons;
            this.releaseWhenNotReleased = releaseWhenNotReleased;
        }

        @Override
        public boolean killSwitchActive() {
            reads.add("killSwitch");
            return reasons.contains(RealIneligibilityReason.KILL_SWITCH);
        }

        @Override
        public boolean realEnabled() {
            reads.add("realEnabled");
            return !reasons.contains(RealIneligibilityReason.REAL_DISABLED);
        }

        @Override
        public boolean walletActive() {
            reads.add("wallet");
            return !reasons.contains(RealIneligibilityReason.WALLET_INACTIVE);
        }

        @Override
        public Release release() {
            reads.add("release");
            if (reasons.contains(RealIneligibilityReason.NOT_RELEASED)) {
                return new Release(releaseWhenNotReleased, Optional.empty());
            }
            if (reasons.contains(RealIneligibilityReason.QUARANTINE)) {
                return new Release("QUARANTINE", Optional.of(EFFECTIVE));
            }
            return new Release("ACTIVE", Optional.of(EFFECTIVE.minusSeconds(86_400)));
        }
    }

    @Test
    void withoutAnyReasonTheRealIsAvailableWithoutReasonEffectiveAtNorAction() {
        Fixed sources = new Fixed(Set.of());

        RealEligibility eligibility = RealCreationEligibility.evaluate(sources);

        assertThat(eligibility.available()).isTrue();
        assertThat(eligibility.reason()).isEmpty();
        assertThat(eligibility.effectiveAt()).isEmpty();
        assertThat(eligibility.action()).isEmpty();
        assertThat(sources.reads).containsExactly("killSwitch", "realEnabled", "wallet", "release");
    }

    @ParameterizedTest
    @EnumSource(RealIneligibilityReason.class)
    void eachReasonAloneIsTheAnswer(RealIneligibilityReason reason) {
        RealEligibility eligibility = RealCreationEligibility.evaluate(new Fixed(EnumSet.of(reason)));

        assertThat(eligibility.available()).isFalse();
        assertThat(eligibility.reason()).contains(reason);
    }

    static Stream<Arguments> pairs() {
        List<Arguments> pairs = new ArrayList<>();
        RealIneligibilityReason[] order = RealIneligibilityReason.values();
        for (int stronger = 0; stronger < order.length; stronger++) {
            for (int weaker = stronger + 1; weaker < order.length; weaker++) {
                pairs.add(Arguments.of(order[stronger], order[weaker]));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} beats {1}")
    @MethodSource("pairs")
    void ofTwoReasonsOnlyTheStrongestComesBack(RealIneligibilityReason stronger, RealIneligibilityReason weaker) {
        RealEligibility eligibility = RealCreationEligibility.evaluate(new Fixed(EnumSet.of(stronger, weaker)));

        assertThat(eligibility.reason()).contains(stronger);
    }

    @Test
    void thereAreExactlyTenPairsInThePrecedenceOrder() {
        assertThat(pairs()).hasSize(10);
        assertThat(RealIneligibilityReason.values())
                .containsExactly(
                        RealIneligibilityReason.KILL_SWITCH,
                        RealIneligibilityReason.REAL_DISABLED,
                        RealIneligibilityReason.WALLET_INACTIVE,
                        RealIneligibilityReason.NOT_RELEASED,
                        RealIneligibilityReason.QUARANTINE);
    }

    @Test
    void theFirstReasonEndsTheEvaluationAndNoLaterSourceIsRead() {
        Fixed killed = new Fixed(EnumSet.allOf(RealIneligibilityReason.class));
        RealCreationEligibility.evaluate(killed);
        assertThat(killed.reads).containsExactly("killSwitch");

        Fixed disabled = new Fixed(EnumSet.of(RealIneligibilityReason.REAL_DISABLED));
        RealCreationEligibility.evaluate(disabled);
        assertThat(disabled.reads).containsExactly("killSwitch", "realEnabled");

        Fixed noWallet = new Fixed(EnumSet.of(RealIneligibilityReason.WALLET_INACTIVE));
        RealCreationEligibility.evaluate(noWallet);
        assertThat(noWallet.reads).containsExactly("killSwitch", "realEnabled", "wallet");
    }

    @Test
    void theQuarantineCarriesTheUtcInstantTheReleaseStartsToCount() {
        RealEligibility eligibility =
                RealCreationEligibility.evaluate(new Fixed(EnumSet.of(RealIneligibilityReason.QUARANTINE)));

        assertThat(eligibility.reason()).contains(RealIneligibilityReason.QUARANTINE);
        assertThat(eligibility.effectiveAt()).contains(Instant.parse("2026-11-22T13:30:00Z"));
        assertThat(eligibility.action()).isEmpty();
    }

    @Test
    void onlyTheInactiveWalletCarriesTheActionAndNoEffectiveAt() {
        RealEligibility wallet =
                RealCreationEligibility.evaluate(new Fixed(EnumSet.of(RealIneligibilityReason.WALLET_INACTIVE)));
        RealEligibility killed =
                RealCreationEligibility.evaluate(new Fixed(EnumSet.of(RealIneligibilityReason.KILL_SWITCH)));

        assertThat(wallet.action()).contains(RealEligibility.Action.ACTIVATE_BOT_WALLET);
        assertThat(wallet.effectiveAt()).isEmpty();
        assertThat(killed.action()).isEmpty();
        assertThat(killed.effectiveAt()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "BLOCKED", "REVOKED", "SOMETHING_NEW"})
    void aReleaseNoneBlockedRevokedOrUnknownIsNotReleased(String state) {
        RealEligibility eligibility =
                RealCreationEligibility.evaluate(new Fixed(EnumSet.of(RealIneligibilityReason.NOT_RELEASED), state));

        assertThat(eligibility.reason()).contains(RealIneligibilityReason.NOT_RELEASED);
        assertThat(eligibility.effectiveAt()).isEmpty();
    }

    @Test
    void aQuarantineWhoseInstantAlreadyPassedStaysQuarantineUntilTheReleaseIsActive() {
        Sources passed = new Fixed(Set.of()) {
            @Override
            public Release release() {
                return new Release("QUARANTINE", Optional.of(Instant.parse("2020-01-01T00:00:00Z")));
            }
        };

        assertThat(RealCreationEligibility.evaluate(passed).reason()).contains(RealIneligibilityReason.QUARANTINE);
    }

    @Test
    void theRecordRefusesAnInconsistentShape() {
        assertThat(RealEligibility.refused(RealIneligibilityReason.QUARANTINE, Optional.empty())
                        .effectiveAt())
                .isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new RealEligibility(true, Optional.of(RealIneligibilityReason.KILL_SWITCH), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RealEligibility(false, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RealEligibility(
                        false, Optional.of(RealIneligibilityReason.KILL_SWITCH), Optional.of(EFFECTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
