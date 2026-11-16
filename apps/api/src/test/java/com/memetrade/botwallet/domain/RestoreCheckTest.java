package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 8 of the U5, written BEFORE the code (BR2.5, Q11, S24, S24b, reliability-design.md "Restauração"): only the bot
 * wallet signs its own exits and each is recorded before it goes to the network, so a signature of the network where the
 * bot wallet is a signer and that the database does not know exists only after a restore (or a misuse of the key). A
 * deposit (an entry the bot wallet does not sign) never counts; a known signature is never looked up.
 */
@Tag("u5-real-operations")
class RestoreCheckTest {

    private static final String BOT = "BotWa11et1111111111111111111111111111111111";
    private static final String MAIN = "MainWa11et111111111111111111111111111111111";

    private final List<String> lookedUp = new ArrayList<>();

    private RestoreCheck.SignerLookup signers(Map<String, List<String>> bySignature) {
        return signature -> {
            lookedUp.add(signature);
            return bySignature.getOrDefault(signature, List.of());
        };
    }

    private static List<ChainPort.SignatureInfo> history(String... signatures) {
        List<ChainPort.SignatureInfo> infos = new ArrayList<>();
        for (String signature : signatures) {
            infos.add(new ChainPort.SignatureInfo(signature, false));
        }
        return infos;
    }

    /** S24: swaps after the backup are signed by the bot wallet and unknown to the database. */
    @Test
    void aSignatureOfTheBotWalletThatTheDatabaseDoesNotKnowIsUnknown() {
        int unknown = RestoreCheck.unknownSignedBy(
                BOT, history("SigSwapAfterBackup"), Set.of(), signers(Map.of("SigSwapAfterBackup", List.of(BOT))));

        assertThat(unknown).isEqualTo(1);
    }

    /** S24b: a deposit is signed by the main wallet only: an ordinary restart pauses nobody. */
    @Test
    void aDepositNotSignedByTheBotWalletNeverCounts() {
        int unknown = RestoreCheck.unknownSignedBy(
                BOT, history("SigDeposit"), Set.of(), signers(Map.of("SigDeposit", List.of(MAIN))));

        assertThat(unknown).isZero();
    }

    @Test
    void aKnownSignatureIsNeverLookedUp() {
        int unknown = RestoreCheck.unknownSignedBy(
                BOT,
                history("SigKnownSwap", "SigKnownWithdrawal"),
                Set.of("SigKnownSwap", "SigKnownWithdrawal"),
                signers(Map.of()));

        assertThat(unknown).isZero();
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void theSignedModeWithTheMainWalletFirstStillCountsTheBotWalletAsASigner() {
        int unknown = RestoreCheck.unknownSignedBy(
                BOT, history("SigSigned"), Set.of(), signers(Map.of("SigSigned", List.of(MAIN, BOT))));

        assertThat(unknown).isEqualTo(1);
    }

    @Test
    void severalUnknownAndKnownAreCountedOneByOne() {
        int unknown = RestoreCheck.unknownSignedBy(
                BOT,
                history("A", "B", "C", "D"),
                Set.of("B"),
                signers(Map.of("A", List.of(BOT), "C", List.of(MAIN), "D", List.of(BOT))));

        assertThat(unknown).isEqualTo(2);
        assertThat(lookedUp).containsExactly("A", "C", "D");
    }

    @Test
    void anEmptyHistoryIsNothingUnknown() {
        assertThat(RestoreCheck.unknownSignedBy(BOT, List.of(), Set.of(), signers(Map.of())))
                .isZero();
    }
}
