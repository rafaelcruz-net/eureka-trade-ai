package com.memetrade.botwallet.domain;

import java.util.List;
import java.util.Set;

/**
 * 261113-trading-real-bot U5 (BR2.5, Q11): only the bot wallet signs its own exits (swaps and withdrawals), and each is
 * recorded in the database before it goes to the network. So a signature of the network, newer than what the database
 * read, where the bot wallet is a signer and that the database does not know, exists only after the database lost
 * history (a restore) or a misuse of the key. Entries the bot wallet does not sign (deposits) never count; a known
 * signature is never looked up on the network.
 */
public final class RestoreCheck {

    private RestoreCheck() {}

    /** The signers of one transaction, read from the network. */
    @FunctionalInterface
    public interface SignerLookup {
        List<String> signers(String signature);
    }

    /** How many signatures of {@code history} the bot wallet signed and the database does not know. */
    public static int unknownSignedBy(
            String botAddress, List<ChainPort.SignatureInfo> history, Set<String> known, SignerLookup lookup) {
        int unknown = 0;
        for (ChainPort.SignatureInfo info : history) {
            if (known.contains(info.signature())) {
                continue;
            }
            if (lookup.signers(info.signature()).contains(botAddress)) {
                unknown++;
            }
        }
        return unknown;
    }
}
