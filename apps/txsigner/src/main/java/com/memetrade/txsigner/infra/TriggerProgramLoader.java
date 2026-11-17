package com.memetrade.txsigner.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.txsigner.policy.TriggerProgram;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 261113-trading-real-bot U7 (BR0.3, U7-NFR1.2, logical-components.md "Hash do trigger-program.json"): loads the program
 * of the Jupiter Trigger recorded by {@code record.sh --trigger}, only when its SHA-256 equals the
 * {@code trigger-program.json.sha256} versioned beside it and, when configured, the property
 * {@code txsigner.trigger.program-sha256} (required by the production profile). Without the file the TxSigner starts as
 * before, with the trigger policy not recorded. Any mismatch, a malformed file, or a configured hash without the file
 * stops the start naming the check; the content of the file is never logged.
 */
public final class TriggerProgramLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BASE58 = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    private static final Pattern DISCRIMINATOR = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private TriggerProgramLoader() {}

    /**
     * @param json the bytes of {@code trigger-program.json}, empty when it was not recorded
     * @param sidecar the content of {@code trigger-program.json.sha256} (the hash, optionally followed by the name)
     * @param configuredSha256 {@code txsigner.trigger.program-sha256}, blank when not configured
     * @param production the production profile is active (the configured hash is then required)
     */
    public static TriggerProgram load(
            Optional<byte[]> json, Optional<String> sidecar, String configuredSha256, boolean production) {
        String configured =
                configuredSha256 == null ? "" : configuredSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (json.isEmpty()) {
            if (!configured.isEmpty()) {
                throw new IllegalStateException(
                        "txsigner.trigger.program-sha256 is set but trigger-program.json was not found");
            }
            return TriggerProgram.notRecorded();
        }
        String actual = sha256(json.get());
        String recorded = sidecar.map(text -> text.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "trigger-program.json was found without its trigger-program.json.sha256"));
        if (!SHA256.matcher(recorded).matches() || !recorded.equals(actual)) {
            throw new IllegalStateException("The SHA-256 of trigger-program.json does not match its .sha256");
        }
        if (production && configured.isEmpty()) {
            throw new IllegalStateException("The production profile needs txsigner.trigger.program-sha256");
        }
        if (!configured.isEmpty() && !configured.equals(actual)) {
            throw new IllegalStateException(
                    "The SHA-256 of trigger-program.json does not match txsigner.trigger.program-sha256");
        }
        return parse(json.get(), actual);
    }

    private static TriggerProgram parse(byte[] json, String sha256) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalStateException("trigger-program.json is not valid JSON");
        }
        String programId = root.path("programId").asText("");
        String create = root.path("discriminators").path("create").asText("");
        String cancel = root.path("discriminators").path("cancel").asText("");
        if (!BASE58.matcher(programId).matches()
                || !DISCRIMINATOR.matcher(create).matches()
                || !DISCRIMINATOR.matcher(cancel).matches()) {
            throw new IllegalStateException(
                    "trigger-program.json needs programId (Base58) and the create and cancel discriminators (8 bytes)");
        }
        return TriggerProgram.recorded(programId, create, cancel, sha256);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }
}
