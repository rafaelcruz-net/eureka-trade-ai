package com.memetrade.realexecution.domain.solana;

import java.util.List;

/**
 * The server builds the swap message (BR4.2, U4-NFR1.1, U4-NFR1.5, deviation of Q2): a v0 message with static keys
 * only, from the instructions of the Jupiter and a blockhash of the network. The fee payer is the bot wallet in
 * AUTO_FULL and the registered main wallet in SIGNED. The result keeps the bytes and their SHA-256; the order records
 * the message BEFORE any signature is asked, and the same message is used by every repetition (U4-NFR4.1). The browser
 * never builds nor changes it: it only signs it (project.md).
 */
public final class MessageBuilder {

    /** One packet of the network: the largest serialized transaction. */
    public static final int MAX_TRANSACTION_SIZE = 1232;

    private MessageBuilder() {}

    /**
     * @throws RouteTooLargeException when the transaction would not fit one packet
     * @throws IllegalArgumentException for a key, a blockhash or an instruction list out of shape
     */
    public static BuiltMessage build(byte[] feePayer, List<SwapInstruction> instructions, byte[] blockhash) {
        MessagePlan plan = MessagePlan.of(feePayer, instructions);
        byte[] message = plan.compileV0(blockhash);
        int size = ShortVec.length(plan.requiredSignatures()) + 64 * plan.requiredSignatures() + message.length;
        if (size > MAX_TRANSACTION_SIZE) {
            throw new RouteTooLargeException(size);
        }
        return new BuiltMessage(message, plan.requiredSignatures());
    }
}
