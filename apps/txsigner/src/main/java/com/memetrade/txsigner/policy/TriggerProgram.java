package com.memetrade.txsigner.policy;

import java.util.Optional;

/**
 * 261113-trading-real-bot U7 (BR0.2, BR0.3, BR2.1, U7-NFR1.2): the program of the Jupiter Trigger API as recorded once
 * by {@code infra/solana-fixtures/record.sh --trigger}: its id and the discriminators of the creation and of the
 * cancellation, with the SHA-256 of the file. Without the recording it is not recorded, and the TxSigner refuses
 * {@code TRIGGER_CREATE} and {@code TRIGGER_CANCEL} with {@code POLICY_NOT_RECORDED}. Even recorded, this delivery has no
 * {@code TriggerPolicy}: it is written after the recording, with its attack tests first (BR0.5), so the refusal stays.
 */
public record TriggerProgram(
        boolean recorded,
        Optional<String> programId,
        Optional<String> createDiscriminator,
        Optional<String> cancelDiscriminator,
        Optional<String> sha256) {

    public static TriggerProgram notRecorded() {
        return new TriggerProgram(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static TriggerProgram recorded(String programId, String create, String cancel, String sha256) {
        return new TriggerProgram(
                true, Optional.of(programId), Optional.of(create), Optional.of(cancel), Optional.of(sha256));
    }
}
