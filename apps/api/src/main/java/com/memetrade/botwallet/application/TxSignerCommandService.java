package com.memetrade.botwallet.application;

import com.memetrade.botwallet.domain.CommandStore;
import com.memetrade.botwallet.domain.CommandStore.Kind;
import com.memetrade.botwallet.domain.CommandStore.PendingCommand;
import com.memetrade.botwallet.domain.TxSignerPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 261113-trading-real-bot U5 (BR4.1, BR6.2, U5-NFR1.2, U5-NFR4.1): the commands to the TxSigner that only restrict, kept
 * until it confirms. LOWER_CAP lowers the cap of its policy; DEACTIVATE closes the policy of a wallet closed by an
 * anonymization (its address is then cleared). Each send uses the commandId as the requestId, so the TxSigner, which is
 * idempotent by requestId, applies a command once however many times it arrives. The TxSigner is never called inside a
 * database transaction; nothing here signs.
 */
@Service
public class TxSignerCommandService {

    private static final Logger log = LoggerFactory.getLogger(TxSignerCommandService.class);
    private static final int BATCH = 100;

    private final CommandStore store;
    private final TxSignerPort signer;
    private final Clock clock;

    public TxSignerCommandService(CommandStore store, TxSignerPort signer, Clock clock) {
        this.store = store;
        this.signer = signer;
        this.clock = clock;
    }

    /** Records a LOWER_CAP in the transaction of the caller. */
    public UUID lowerCap(UUID botWalletId, long capLamports) {
        return enqueue(botWalletId, Kind.LOWER_CAP, Map.of("capLamports", Long.toString(capLamports)));
    }

    /** Records a DEACTIVATE in the transaction of the caller; the payload carries only the bot wallet id. */
    public UUID deactivate(UUID botWalletId) {
        return enqueue(botWalletId, Kind.DEACTIVATE, Map.of());
    }

    private UUID enqueue(UUID botWalletId, Kind kind, Map<String, String> values) {
        UUID commandId = UUID.randomUUID();
        Map<String, String> payload = new java.util.LinkedHashMap<>(values);
        payload.put("botWalletId", botWalletId.toString());
        store.insert(new PendingCommand(commandId, botWalletId, kind, payload, 0, clock.instant()));
        return commandId;
    }

    /** One round of the job: every pending command once; returns how many the TxSigner confirmed. */
    public int resendPending() {
        int confirmed = 0;
        for (PendingCommand command : store.pending(BATCH)) {
            if (send(command)) {
                confirmed++;
            }
        }
        return confirmed;
    }

    private boolean send(PendingCommand command) {
        Instant now = clock.instant();
        store.attempted(command.commandId(), now);
        Optional<String> address = store.botAddress(command.botWalletId());
        try {
            if (address.isPresent()) {
                deliver(command, address.get());
            }
        } catch (TxSignerPort.SignerUnavailableException down) {
            log.warn("TxSigner command not delivered yet: commandId={}, kind={}", command.commandId(), command.kind());
            return false;
        } catch (TxSignerPort.SignerRefusedException refused) {
            if (!"WALLET_NOT_FOUND".equals(refused.code())) {
                log.warn(
                        "TxSigner refused a command: commandId={}, kind={}, code={}",
                        command.commandId(),
                        command.kind(),
                        refused.code());
                return false;
            }
        }
        store.confirmed(command.commandId(), now);
        if (command.kind() == Kind.DEACTIVATE) {
            store.clearBotAddress(command.botWalletId());
        }
        log.info("TxSigner command confirmed: commandId={}, kind={}", command.commandId(), command.kind());
        return true;
    }

    private void deliver(PendingCommand command, String address) {
        switch (command.kind()) {
            case LOWER_CAP ->
                signer.lowerCap(
                        command.commandId(),
                        address,
                        Long.parseLong(command.payload().get("capLamports")));
            case DEACTIVATE -> signer.close(command.commandId(), address);
        }
    }
}
