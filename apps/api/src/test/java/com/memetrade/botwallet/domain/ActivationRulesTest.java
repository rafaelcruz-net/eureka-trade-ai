package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 16, written before the code (BR1.1 to BR1.4, BR1.7, BR1.9, BR2.1 to BR2.3, BR7.1, AC1.2.1 to AC1.2.5): the order of
 * the activation checks, the values carried by each challenge, the canonical consent text (the same the TxSigner checks
 * byte by byte), pauses with reasons, caps up to 10 SOL and the 24 h quarantine of a release.
 */
class ActivationRulesTest {

    private static final long SOL = 1_000_000_000L;
    private static final long MAX_CAP = 10 * SOL;
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private static BotWalletError gateError(boolean real, boolean terms, boolean released, boolean active) {
        return ActivationGate.check(real, terms, released, active).orElse(null);
    }

    @Test
    void theActivationChecksRealThenTermsThenReleaseThenTheExistingWallet() {
        assertThat(gateError(false, false, false, true)).isEqualTo(BotWalletError.REAL_DISABLED);
        assertThat(gateError(true, false, false, true)).isEqualTo(BotWalletError.TERMS_REACCEPTANCE_REQUIRED);
        assertThat(gateError(true, true, false, true)).isEqualTo(BotWalletError.REAL_NOT_RELEASED);
        assertThat(gateError(true, true, true, true)).isEqualTo(BotWalletError.BOT_WALLET_ALREADY_ACTIVE);
        assertThat(gateError(true, true, true, false)).isNull();
    }

    @Test
    void theActivationChallengeCarriesSignedAndTheChosenCap() {
        ChallengeValues values = ChallengeRules.values(ConsentKind.ACTIVATE, null, 2 * SOL, null, MAX_CAP);

        assertThat(values.mode()).isEqualTo(OperationMode.SIGNED);
        assertThat(values.capLamports()).isEqualTo(2 * SOL);
    }

    @Test
    void aCapAboveTheMaximumOrMissingIsRefused() {
        assertThatThrownBy(() -> ChallengeRules.values(ConsentKind.ACTIVATE, null, MAX_CAP + 1, null, MAX_CAP))
                .isInstanceOfSatisfying(BotWalletException.class, e -> assertThat(e.error())
                        .isEqualTo(BotWalletError.CAP_ABOVE_MAXIMUM));
        assertThatThrownBy(() -> ChallengeRules.values(ConsentKind.ACTIVATE, null, null, null, MAX_CAP))
                .isInstanceOfSatisfying(BotWalletException.class, e -> assertThat(e.error())
                        .isEqualTo(BotWalletError.VALIDATION_FAILED));
    }

    @Test
    void raisingTheCapKeepsTheCurrentModeAndRaisingTheModeKeepsTheCurrentCap() {
        Preferences current = Preferences.initial(SOL, 30_000_000L, 100_000L, MAX_CAP, NOW);

        ChallengeValues cap = ChallengeRules.values(ConsentKind.RAISE_CAP, null, 3 * SOL, current, MAX_CAP);
        ChallengeValues mode =
                ChallengeRules.values(ConsentKind.RAISE_MODE, OperationMode.AUTO_EXITS, null, current, MAX_CAP);

        assertThat(cap).isEqualTo(new ChallengeValues(OperationMode.SIGNED, 3 * SOL));
        assertThat(mode).isEqualTo(new ChallengeValues(OperationMode.AUTO_EXITS, SOL));
    }

    @Test
    void theConsentTextIsTheCanonicalTenLines() {
        String text = ConsentMessages.render(new ConsentMessages.Fields(
                ConsentKind.ACTIVATE,
                Network.LOCALNET,
                "MainWa11etAddress1111111111111111",
                null,
                OperationMode.SIGNED,
                2 * SOL,
                "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR",
                NOW));

        assertThat(text.split("\n", -1))
                .containsExactly(
                        "Eureka Trader - consentimento REAL v1",
                        "purpose: ACTIVATE",
                        "network: LOCALNET",
                        "main-wallet: MainWa11etAddress1111111111111111",
                        "bot-wallet: NEW",
                        "mode: SIGNED",
                        "cap-lamports: 2000000000",
                        "nonce: 8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR",
                        "issued-at: 2026-11-14T12:00:00Z",
                        "expires-at: 2026-11-14T12:05:00Z");
    }

    @Test
    void aPersonResumesOnlyTheirOwnPause() {
        Preferences paused = Preferences.initial(SOL, 0, 0, MAX_CAP, NOW).pause(PauseReason.USER, NOW);
        Preferences divergence = Preferences.initial(SOL, 0, 0, MAX_CAP, NOW).pause(PauseReason.DIVERGENCE, NOW);
        Preferences running = Preferences.initial(SOL, 0, 0, MAX_CAP, NOW);

        assertThat(paused.resumeByUser(NOW).pausedReasons()).isEmpty();
        assertThatThrownBy(() -> divergence.resumeByUser(NOW))
                .isInstanceOfSatisfying(
                        BotWalletException.class, e -> assertThat(e.error()).isEqualTo(BotWalletError.REAL_PAUSED));
        assertThatThrownBy(() -> running.resumeByUser(NOW))
                .isInstanceOfSatisfying(
                        BotWalletException.class, e -> assertThat(e.error()).isEqualTo(BotWalletError.REAL_NOT_PAUSED));
        assertThat(paused.pause(PauseReason.DIVERGENCE, NOW).resumeByUser(NOW).pausedReasons())
                .isEqualTo(EnumSet.of(PauseReason.DIVERGENCE));
    }

    @Test
    void loweringTheCapIsImmediateAndNeverAboveTheMaximum() {
        Preferences current = Preferences.initial(3 * SOL, 0, 0, MAX_CAP, NOW);

        assertThat(current.lowerCap(SOL, NOW).capLamports()).isEqualTo(SOL);
        assertThatThrownBy(() -> current.lowerCap(4 * SOL, NOW))
                .isInstanceOfSatisfying(
                        BotWalletException.class, e -> assertThat(e.error()).isEqualTo(BotWalletError.CONSENT_INVALID));
        assertThatThrownBy(() -> current.raiseCap(MAX_CAP + 1, NOW))
                .isInstanceOfSatisfying(BotWalletException.class, e -> assertThat(e.error())
                        .isEqualTo(BotWalletError.CAP_ABOVE_MAXIMUM));
    }

    /**
     * Since the U5 (BR1.1, BR1.4) the 24 hours only make the release due for the gates: it is in force once the gates job
     * turned it ACTIVE, on its network and until revoked.
     */
    @Test
    void aReleaseTakesEffect24HoursAfterItsRequestOnItsNetworkAndUntilRevoked() {
        UUID user = UUID.randomUUID();
        RealRelease seeded = RealRelease.seeded(user, Network.LOCALNET, user, NOW);
        RealRelease release = new RealRelease(
                seeded.releaseId(),
                user,
                Network.LOCALNET,
                user,
                NOW,
                seeded.effectiveAt(),
                null,
                ReleaseState.ACTIVE,
                null,
                NOW.plus(Duration.ofHours(24)),
                null);

        assertThat(release.effectiveAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(RealReleases.isEffective(List.of(seeded), Network.LOCALNET, NOW.plus(Duration.ofHours(25))))
                .isFalse();
        assertThat(RealReleases.isEffective(List.of(release), Network.LOCALNET, NOW.plus(Duration.ofHours(24))))
                .isTrue();
        assertThat(RealReleases.isEffective(List.of(release), Network.MAINNET, NOW.plus(Duration.ofDays(2))))
                .isFalse();
        assertThat(RealReleases.isEffective(
                        List.of(release.revokedAt(NOW.plus(Duration.ofHours(30)))),
                        Network.LOCALNET,
                        NOW.plus(Duration.ofDays(2))))
                .isFalse();
    }

    @Test
    void theChallengeIsValidFiveMinutesAndConsumedOnce() {
        ConsentChallenge challenge = ConsentChallenge.issue(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ConsentKind.ACTIVATE,
                "text",
                "nonce",
                OperationMode.SIGNED,
                SOL,
                NOW);

        assertThat(challenge.isUsableAt(NOW.plusSeconds(299))).isTrue();
        assertThat(challenge.isUsableAt(NOW.plusSeconds(300))).isFalse();
        ConsentChallenge inUse = challenge.lock(NOW);
        assertThat(inUse.state()).isEqualTo(ChallengeState.IN_USE);
        assertThat(inUse.release().state()).isEqualTo(ChallengeState.PENDING);
        assertThat(inUse.consume(NOW).state()).isEqualTo(ChallengeState.CONSUMED);
        assertThatThrownBy(() -> inUse.consume(NOW).lock(NOW)).isInstanceOf(BotWalletException.class);
        assertThat(Optional.of(challenge.expiresAt())).contains(NOW.plusSeconds(300));
    }
}
