package com.memetrade.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import org.p2p.solanaj.core.Account;

/**
 * TEST-ONLY tool (test scope, the only place the Solana SDK is allowed): generates the smoke wallet, writes the key
 * pair to the given file OUTSIDE the repository (scripts/verify.sh passes a mktemp path and deletes it at the end)
 * and prints ONLY the public address on stdout.
 *
 * <p>Run: {@code mvnw test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.memetrade.tools.SmokeWalletTool -Dexec.args=<file>}
 */
public final class SmokeWalletTool {

    private SmokeWalletTool() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SmokeWalletTool <key file outside the repository>");
        }
        System.out.println(generate(Path.of(args[0])));
    }

    /** Writes {"address": base58, "seed": base64 of the 32-byte Ed25519 seed} and returns the address. */
    static String generate(Path keyFile) throws IOException {
        Account account = new Account();
        String address = account.getPublicKeyBase58();
        byte[] seed = Arrays.copyOf(account.getSecretKey(), 32);
        String json = "{\"address\":\"" + address + "\",\"seed\":\""
                + Base64.getEncoder().encodeToString(seed) + "\"}";
        Files.writeString(
                keyFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return address;
    }
}
