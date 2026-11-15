package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.CodecException;
import com.memetrade.txsigner.codec.CompiledInstruction;
import com.memetrade.txsigner.codec.SolanaMessage;
import com.memetrade.txsigner.codec.SolanaTransaction;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Content policy of a WITHDRAWAL (BR5.1, ADR-004): pure, decodes the message with the own codec and accepts only the
 * exact shape: (a) no address lookup table; (b) one signer, the bot wallet, which is also the fee payer; (c) no durable
 * nonce; (d) at most one SetComputeUnitLimit (up to the ceiling), at most one SetComputeUnitPrice and exactly one System
 * Program Transfer from the bot wallet to the main wallet of the policy, with the lamports of the withdrawal; (e) no
 * other instruction, program or account; (f) the total fee within the ceiling and equal to the calculated fee. Fail
 * closed: any decoding error is a refusal.
 */
public final class WithdrawalPolicy {

    public static final byte[] SYSTEM_PROGRAM = new byte[32];
    public static final byte[] COMPUTE_BUDGET_PROGRAM = Base58.decode("ComputeBudget111111111111111111111111111111");

    private static final int SYSTEM_TRANSFER = 2;
    private static final int SYSTEM_ADVANCE_NONCE = 4;
    private static final int TRANSFER_DATA_LENGTH = 12;
    private static final int SET_COMPUTE_UNIT_LIMIT = 2;
    private static final int SET_COMPUTE_UNIT_PRICE = 3;
    private static final long DEFAULT_UNITS_PER_INSTRUCTION = 200_000L;
    private static final long MAX_UNITS = 1_400_000L;
    private static final long MICRO_LAMPORTS_PER_LAMPORT = 1_000_000L;

    private final int maxComputeUnits;
    private final long baseFeePerSignature;

    public WithdrawalPolicy(int maxComputeUnits, long baseFeePerSignature) {
        this.maxComputeUnits = maxComputeUnits;
        this.baseFeePerSignature = baseFeePerSignature;
    }

    public PolicyDecision evaluate(WithdrawalCheck check) {
        WithdrawalContext context = check.context();
        if (context == null || context.lamports() == null || context.feeLamports() == null) {
            return PolicyDecision.refuse(PolicyRefusal.CONTEXT_MISSING);
        }
        SolanaTransaction transaction;
        byte[] bot;
        byte[] main;
        try {
            transaction = SolanaTransaction.decodeBase64(check.transactionBase64());
            bot = Base58.decodeExact(check.botWallet(), 32);
            main = Base58.decodeExact(check.mainWallet(), 32);
        } catch (CodecException malformed) {
            return PolicyDecision.refuse(PolicyRefusal.MALFORMED_TRANSACTION);
        }
        PolicyRefusal refusal = refusalOf(transaction.message(), bot, main, check);
        return refusal == null ? PolicyDecision.approve(transaction) : PolicyDecision.refuse(refusal);
    }

    private PolicyRefusal refusalOf(SolanaMessage message, byte[] bot, byte[] main, WithdrawalCheck check) {
        if (!message.addressTableLookups().isEmpty()) {
            return PolicyRefusal.ALT_NOT_ALLOWED;
        }
        if (message.header().requiredSignatures() != 1) {
            return PolicyRefusal.EXTRA_SIGNER;
        }
        if (!Arrays.equals(message.accountKey(0), bot)) {
            return PolicyRefusal.FEE_PAYER_MISMATCH;
        }
        Shape shape = new Shape();
        for (CompiledInstruction instruction : message.instructions()) {
            PolicyRefusal refused = shape.accept(message, instruction);
            if (refused != null) {
                return refused;
            }
        }
        if (shape.transfer == null) {
            return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
        }
        PolicyRefusal transfer = checkTransfer(message, shape, main, check);
        if (transfer != null) {
            return transfer;
        }
        PolicyRefusal accounts = checkAccounts(message, bot, main);
        return accounts != null ? accounts : checkFee(message, shape, check);
    }

    /** Only the bot wallet, the main wallet and the two allowed programs may appear among the static keys. */
    private static PolicyRefusal checkAccounts(SolanaMessage message, byte[] bot, byte[] main) {
        for (byte[] key : message.accountKeys()) {
            boolean allowed = Arrays.equals(key, bot)
                    || Arrays.equals(key, main)
                    || Arrays.equals(key, SYSTEM_PROGRAM)
                    || Arrays.equals(key, COMPUTE_BUDGET_PROGRAM);
            if (!allowed) {
                return PolicyRefusal.ACCOUNT_NOT_ALLOWED;
            }
        }
        return null;
    }

    private static PolicyRefusal checkTransfer(SolanaMessage message, Shape shape, byte[] main, WithdrawalCheck check) {
        int[] accounts = shape.transfer.accountIndexes();
        if (accounts.length != 2 || accounts[0] != 0) {
            return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
        }
        if (!Arrays.equals(message.accountKey(accounts[1]), main)) {
            return PolicyRefusal.DESTINATION_NOT_ALLOWED;
        }
        long lamports = ByteBuffer.wrap(shape.transfer.data(), 4, 8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
        return lamports == check.context().lamports() ? null : PolicyRefusal.AMOUNT_MISMATCH;
    }

    private PolicyRefusal checkFee(SolanaMessage message, Shape shape, WithdrawalCheck check) {
        long fee;
        try {
            fee = totalFee(message, shape);
        } catch (ArithmeticException overflow) {
            return PolicyRefusal.FEE_TOO_HIGH;
        }
        if (fee > check.maxWithdrawalFeeLamports()) {
            return PolicyRefusal.FEE_TOO_HIGH;
        }
        return fee == check.context().feeLamports() ? null : PolicyRefusal.FEE_MISMATCH;
    }

    /** Base fee per signature plus ceil(unit price x unit limit / 10^6) (BR5.1 f, NFR4.3). */
    private long totalFee(SolanaMessage message, Shape shape) {
        long base = Math.multiplyExact(baseFeePerSignature, message.header().requiredSignatures());
        if (shape.unitPrice == null) {
            return base;
        }
        long units = shape.unitLimit != null
                ? shape.unitLimit
                : Math.min(MAX_UNITS, DEFAULT_UNITS_PER_INSTRUCTION * shape.otherInstructions);
        long microLamports = Math.multiplyExact(shape.unitPrice, units);
        long priority = Math.addExact(microLamports, MICRO_LAMPORTS_PER_LAMPORT - 1) / MICRO_LAMPORTS_PER_LAMPORT;
        return Math.addExact(base, priority);
    }

    /** Walks the instructions once and keeps the allowed ones. */
    private final class Shape {
        private CompiledInstruction transfer;
        private Long unitLimit;
        private Long unitPrice;
        private int otherInstructions;

        PolicyRefusal accept(SolanaMessage message, CompiledInstruction instruction) {
            byte[] program = message.programId(instruction);
            if (Arrays.equals(program, SYSTEM_PROGRAM)) {
                otherInstructions++;
                return acceptSystem(instruction);
            }
            if (Arrays.equals(program, COMPUTE_BUDGET_PROGRAM)) {
                return acceptComputeBudget(instruction);
            }
            return PolicyRefusal.PROGRAM_NOT_ALLOWED;
        }

        private PolicyRefusal acceptSystem(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (data.length < 4) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            int kind =
                    ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (kind == SYSTEM_ADVANCE_NONCE) {
                return PolicyRefusal.DURABLE_NONCE_NOT_ALLOWED;
            }
            if (kind != SYSTEM_TRANSFER || data.length != TRANSFER_DATA_LENGTH || transfer != null) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            transfer = instruction;
            return null;
        }

        private PolicyRefusal acceptComputeBudget(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (instruction.accountIndexes().length != 0 || data.length == 0) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            ByteBuffer value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data[0] == SET_COMPUTE_UNIT_LIMIT && data.length == 5 && unitLimit == null) {
                long units = Integer.toUnsignedLong(value.getInt(1));
                if (units > maxComputeUnits) {
                    return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
                }
                unitLimit = units;
                return null;
            }
            if (data[0] == SET_COMPUTE_UNIT_PRICE && data.length == 9 && unitPrice == null) {
                long price = value.getLong(1);
                if (price < 0) {
                    // An unsigned price above 2^63 - 1 can never fit the fee ceiling.
                    return PolicyRefusal.FEE_TOO_HIGH;
                }
                unitPrice = price;
                return null;
            }
            return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
        }
    }

    /** Allowed programs, for the documentation of the policy and its tests. */
    public static List<String> allowedPrograms() {
        return List.of(Base58.encode(SYSTEM_PROGRAM), Base58.encode(COMPUTE_BUDGET_PROGRAM));
    }
}
