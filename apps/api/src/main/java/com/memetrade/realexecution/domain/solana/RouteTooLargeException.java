package com.memetrade.realexecution.domain.solana;

/**
 * U4-NFR1.5: the route of the Jupiter does not fit one packet (1232 bytes) with static keys only. The driver asks once
 * more with a smaller {@code maxAccounts}; then the order becomes FAILED {@code ROUTE_TOO_LARGE}.
 */
public class RouteTooLargeException extends RuntimeException {

    private final int transactionSize;

    public RouteTooLargeException(int transactionSize) {
        super("The swap transaction would have " + transactionSize + " bytes, above one packet");
        this.transactionSize = transactionSize;
    }

    public int transactionSize() {
        return transactionSize;
    }
}
