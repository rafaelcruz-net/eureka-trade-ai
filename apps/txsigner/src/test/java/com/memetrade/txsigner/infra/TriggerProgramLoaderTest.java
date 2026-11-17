package com.memetrade.txsigner.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.policy.TriggerProgram;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Step 11 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR0.3, U7-NFR1.2, logical-components.md "Hash do
 * trigger-program.json"): the program of the Jupiter Trigger recorded once by {@code record.sh --trigger} is loaded only
 * when its SHA-256 matches the {@code .sha256} beside it and, when configured (always in production), the property
 * {@code txsigner.trigger.program-sha256}. Without the file the TxSigner starts as before and the trigger policy is not
 * recorded; a file that does not match stops the start.
 */
class TriggerProgramLoaderTest {

    static final String PROGRAM = """
            {"version":1,"programId":"Trig1111111111111111111111111111111111111111","discriminators":\
            {"create":"0102030405060708","cancel":"1112131415161718"}}""";

    static String sha256(String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<byte[]> file(String text) {
        return Optional.of(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void withoutTheRecordingThePolicyIsNotRecordedAndTheStartGoesOn() {
        TriggerProgram program = TriggerProgramLoader.load(Optional.empty(), Optional.empty(), "", false);

        assertThat(program.recorded()).isFalse();
    }

    @Test
    void theRecordingWithItsHashIsLoaded() throws Exception {
        TriggerProgram program = TriggerProgramLoader.load(
                file(PROGRAM), Optional.of(sha256(PROGRAM) + "  trigger-program.json\n"), "", false);

        assertThat(program.recorded()).isTrue();
        assertThat(program.programId()).contains("Trig1111111111111111111111111111111111111111");
        assertThat(program.sha256()).contains(sha256(PROGRAM));
    }

    @Test
    void aRecordingWhoseHashDoesNotMatchStopsTheStart() {
        assertThatThrownBy(() -> TriggerProgramLoader.load(file(PROGRAM), Optional.of("0".repeat(64)), "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void aRecordingWithoutItsHashFileStopsTheStart() {
        assertThatThrownBy(() -> TriggerProgramLoader.load(file(PROGRAM), Optional.empty(), "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".sha256");
    }

    @Test
    void theConfiguredHashMustMatchToo() throws Exception {
        String hash = sha256(PROGRAM);

        assertThatThrownBy(() -> TriggerProgramLoader.load(file(PROGRAM), Optional.of(hash), "f".repeat(64), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("txsigner.trigger.program-sha256");
        assertThat(TriggerProgramLoader.load(file(PROGRAM), Optional.of(hash), hash, true)
                        .recorded())
                .isTrue();
    }

    @Test
    void inProductionTheHashIsAlsoConfigured() throws Exception {
        String hash = sha256(PROGRAM);

        assertThatThrownBy(() -> TriggerProgramLoader.load(file(PROGRAM), Optional.of(hash), "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("txsigner.trigger.program-sha256");
    }

    @Test
    void aConfiguredHashWithoutTheRecordingStopsTheStart() {
        assertThatThrownBy(() -> TriggerProgramLoader.load(Optional.empty(), Optional.empty(), "a".repeat(64), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger-program.json");
    }

    @Test
    void aMalformedRecordingStopsTheStart() throws Exception {
        String broken = "{\"version\":1,\"programId\":\"not base58 0OIl\"}";

        assertThatThrownBy(() -> TriggerProgramLoader.load(file(broken), Optional.of(sha256(broken)), "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger-program.json");
    }
}
