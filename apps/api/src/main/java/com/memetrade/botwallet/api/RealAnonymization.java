package com.memetrade.botwallet.api;

import java.util.Objects;
import java.util.UUID;

/**
 * M1 {@code closeForAnonymization} (261113-trading-real-bot U5, BR6.1, BR6.2; consumer: the anonymization of the
 * {@code privacy} module). Runs in the transaction of the anonymization ({@code MANDATORY}). A bot wallet with a balance,
 * a position, or an order or a withdrawal in progress refuses it (the person withdraws first) and nothing changes;
 * otherwise the wallet is closed, its addresses and messages are cleared and the trail loses its address.
 */
@FunctionalInterface
public interface RealAnonymization {

    CloseOutcome closeForAnonymization(UUID tenantId, UUID userId);

    /**
     * Closed (with or without a bot wallet), or refused with {@code BALANCE}, {@code POSITION}, {@code ORDER_IN_PROGRESS}
     * or, since the U7, {@code OPEN_CONDITIONALS}.
     */
    sealed interface CloseOutcome permits CloseOutcome.Closed, CloseOutcome.Refused {

        /** @param hadBotWallet the person had a bot wallet that is now closed */
        record Closed(boolean hadBotWallet) implements CloseOutcome {}

        record Refused(String reason) implements CloseOutcome {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
