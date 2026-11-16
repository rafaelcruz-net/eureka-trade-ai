package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5 (entities.md PendingTxSignerCommand, BR4.1, BR6.2): persistence port of the commands to the
 * TxSigner not yet confirmed. The payload carries only ids and values, never an address (a CHECK of the table refuses
 * one); the address is read from the bot wallet at each send.
 */
public interface CommandStore {

    void insert(PendingCommand command);

    /** The commands not yet confirmed, oldest first, at most {@code limit}. */
    List<PendingCommand> pending(int limit);

    /** The address of the bot wallet of a command, while it is still known. */
    Optional<String> botAddress(UUID botWalletId);

    /** One more try, at {@code at}. */
    void attempted(UUID commandId, Instant at);

    /** The TxSigner confirmed; for a DEACTIVATE, the address of the bot wallet is cleared too (BR6.2). */
    boolean confirmed(UUID commandId, Instant at);

    /** BR6.2: once the TxSigner closed the wallet, its address leaves the database. */
    void clearBotAddress(UUID botWalletId);

    /** A command to the TxSigner. */
    record PendingCommand(
            UUID commandId, UUID botWalletId, Kind kind, Map<String, String> payload, int attempts, Instant createdAt) {

        public PendingCommand {
            payload = Map.copyOf(payload);
        }
    }

    /** LOWER_CAP of BR4.1, DEACTIVATE of BR6.2 (the policy CLOSE of the TxSigner). */
    enum Kind {
        LOWER_CAP,
        DEACTIVATE
    }
}
