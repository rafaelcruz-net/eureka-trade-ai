package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.CodecException;
import com.memetrade.txsigner.codec.CompiledInstruction;
import com.memetrade.txsigner.codec.ProgramAddress;
import com.memetrade.txsigner.codec.SolanaMessage;
import com.memetrade.txsigner.codec.SolanaTransaction;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Content policy of a SWAP (261113-trading-real-bot U4, BR4.3, ADR-004, U4-NFR1.2, U4-NFR1.5; project.md "NEVER deixar
 * o TxSigner assinar transação fora da política de conteúdo"). Pure: decodes the message with the own codec and accepts
 * only the shape of a Jupiter swap of the bot wallet:
 *
 * <ul>
 *   <li>no address lookup table (without network the TxSigner could not see programs, owners nor destinations);
 *   <li>signers exactly {bot} in AUTO_FULL, or exactly {main wallet as fee payer, bot} in SIGNED, with a valid Ed25519
 *       signature of the main wallet over the same message, and the main wallet in no instruction at all;
 *   <li>programs: Jupiter v6 ({@code route} or {@code shared_accounts_route}, once), Token, Token-2022, Associated
 *       Token, Compute Budget; System only to wrap SOL of the bot wallet into its own wrapped SOL account;
 *   <li>the rent of a created account paid by the bot wallet; the funds owned by the bot wallet; every destination an
 *       associated token account OF THE BOT WALLET (derived here), no platform fee, no third destination;
 *   <li>no change of authority nor approval; an account is closed only to the bot wallet;
 *   <li>slippage at most 500; the minimum the route enforces on chain at least the minimum of the context, which is at
 *       least {@code floor(quotedOut x (10000 - slippage) / 10000)}; the input equal to the context and, for a buy,
 *       within the authorized cap; AUTO_FULL only for a wallet authorized in AUTO_FULL;
 *   <li>the priority fee within the ceiling and the total fee equal to the one calculated before signing.
 * </ul>
 *
 * Fail closed: any decoding error, any unknown instruction or account role is a refusal. Each refusal has exactly one
 * reason.
 */
public final class SwapPolicy {

    public static final byte[] SYSTEM_PROGRAM = new byte[32];
    public static final byte[] COMPUTE_BUDGET_PROGRAM = Base58.decode("ComputeBudget111111111111111111111111111111");
    public static final byte[] TOKEN_PROGRAM = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    public static final byte[] TOKEN_2022_PROGRAM = Base58.decode("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
    public static final byte[] ASSOCIATED_TOKEN_PROGRAM = ProgramAddress.ASSOCIATED_TOKEN_PROGRAM;
    public static final byte[] JUPITER_PROGRAM = Base58.decode("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4");
    public static final byte[] WRAPPED_SOL = Base58.decode("So11111111111111111111111111111111111111112");

    static final int MAX_SLIPPAGE_BPS = 500;

    private static final byte[] ROUTE = discriminator("route");
    private static final byte[] SHARED_ACCOUNTS_ROUTE = discriminator("shared_accounts_route");
    private static final int ROUTE_TAIL = 19;
    private static final long DEFAULT_UNITS_PER_INSTRUCTION = 200_000L;
    private static final long MAX_UNITS = 1_400_000L;
    private static final long MICRO_LAMPORTS_PER_LAMPORT = 1_000_000L;
    private static final BigInteger BPS = BigInteger.valueOf(10_000);

    private final long maxPriorityFeeLamports;
    private final long baseFeePerSignature;
    private final Ed25519Verifier verifier = new Ed25519Verifier();

    public SwapPolicy(long maxPriorityFeeLamports, long baseFeePerSignature) {
        this.maxPriorityFeeLamports = maxPriorityFeeLamports;
        this.baseFeePerSignature = baseFeePerSignature;
    }

    public PolicyDecision evaluate(SwapCheck check) {
        SwapContext context = check.context();
        if (context == null || !context.complete()) {
            return PolicyDecision.refuse(PolicyRefusal.CONTEXT_MISSING);
        }
        try {
            Base58.decodeExact(context.mint(), 32);
        } catch (CodecException notAMint) {
            return PolicyDecision.refuse(PolicyRefusal.CONTEXT_MISSING);
        }
        SolanaTransaction transaction;
        byte[] bot;
        byte[] main;
        try {
            transaction = SolanaTransaction.decodeBase64(check.transactionBase64());
            bot = Base58.decodeExact(check.botWallet(), 32);
            main = check.mainWallet() == null ? null : Base58.decodeExact(check.mainWallet(), 32);
        } catch (CodecException malformed) {
            return PolicyDecision.refuse(PolicyRefusal.MALFORMED_TRANSACTION);
        }
        PolicyRefusal refusal = refusalOf(transaction, bot, main, check, context);
        return refusal == null ? PolicyDecision.approve(transaction) : PolicyDecision.refuse(refusal);
    }

    private PolicyRefusal refusalOf(
            SolanaTransaction transaction, byte[] bot, byte[] main, SwapCheck check, SwapContext context) {
        SolanaMessage message = transaction.message();
        if (!message.addressTableLookups().isEmpty()) {
            return PolicyRefusal.LOOKUP_TABLE_NOT_ALLOWED;
        }
        if (context.mode() == SwapMode.AUTO_FULL && check.authorizedMode() != OperationMode.AUTO_FULL) {
            return PolicyRefusal.MODE_NOT_ALLOWED;
        }
        if (context.side() == SwapSide.BUY && context.inputLamports() > check.authorizedCapLamports()) {
            return PolicyRefusal.CAP_EXCEEDED;
        }
        PolicyRefusal signers = checkSigners(transaction, bot, main, context.mode());
        if (signers != null) {
            return signers;
        }
        if (context.maxSlippageBps() < 0 || context.maxSlippageBps() > MAX_SLIPPAGE_BPS) {
            return PolicyRefusal.SLIPPAGE_TOO_HIGH;
        }
        if (context.minOut() < floorMinimum(context.quotedOut(), context.maxSlippageBps())) {
            return PolicyRefusal.MIN_OUT_TOO_LOW;
        }
        if (context.mode() == SwapMode.SIGNED && mentionsMainWallet(message)) {
            return PolicyRefusal.MAIN_WALLET_NOT_ALLOWED;
        }
        Shape shape = new Shape(message, bot, context);
        for (CompiledInstruction instruction : message.instructions()) {
            PolicyRefusal refused = shape.accept(instruction);
            if (refused != null) {
                return refused;
            }
        }
        if (!shape.swapSeen) {
            return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
        }
        return checkFee(message, shape, context);
    }

    /** AUTO_FULL: exactly {bot}. SIGNED: exactly {main as fee payer, bot}, with the main signature valid. */
    private PolicyRefusal checkSigners(SolanaTransaction transaction, byte[] bot, byte[] main, SwapMode mode) {
        SolanaMessage message = transaction.message();
        int signers = message.header().requiredSignatures();
        if (mode == SwapMode.AUTO_FULL) {
            return signers == 1 && Arrays.equals(message.accountKey(0), bot) ? null : PolicyRefusal.SIGNER_NOT_ALLOWED;
        }
        if (main == null
                || signers != 2
                || !Arrays.equals(message.accountKey(0), main)
                || !Arrays.equals(message.accountKey(1), bot)) {
            return PolicyRefusal.SIGNER_NOT_ALLOWED;
        }
        boolean valid = verifier.verify(
                main, transaction.messageBytes(), transaction.signatures().get(0));
        return valid ? null : PolicyRefusal.MAIN_SIGNATURE_INVALID;
    }

    /** S27: in SIGNED the main wallet (key 0) is only the fee payer: never an account nor a program of an instruction. */
    private static boolean mentionsMainWallet(SolanaMessage message) {
        for (CompiledInstruction instruction : message.instructions()) {
            if (instruction.programIdIndex() == 0) {
                return true;
            }
            for (int index : instruction.accountIndexes()) {
                if (index == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private PolicyRefusal checkFee(SolanaMessage message, Shape shape, SwapContext context) {
        long priority;
        long total;
        try {
            long base = Math.multiplyExact(baseFeePerSignature, message.header().requiredSignatures());
            priority = priorityFee(shape);
            total = Math.addExact(base, priority);
        } catch (ArithmeticException overflow) {
            return PolicyRefusal.PRIORITY_FEE_TOO_HIGH;
        }
        if (priority > maxPriorityFeeLamports) {
            return PolicyRefusal.PRIORITY_FEE_TOO_HIGH;
        }
        return total == context.feeLamports() ? null : PolicyRefusal.FEE_MISMATCH;
    }

    /** ceil(unit price x unit limit / 10^6); without a limit, 200,000 units per other instruction (at most 1.4 M). */
    private static long priorityFee(Shape shape) {
        if (shape.unitPrice == null) {
            return 0;
        }
        long units = shape.unitLimit != null
                ? shape.unitLimit
                : Math.min(MAX_UNITS, DEFAULT_UNITS_PER_INSTRUCTION * shape.otherInstructions);
        long micro = Math.multiplyExact(shape.unitPrice, units);
        return Math.addExact(micro, MICRO_LAMPORTS_PER_LAMPORT - 1) / MICRO_LAMPORTS_PER_LAMPORT;
    }

    /** floor(quoted x (10000 - slippage) / 10000), exact for every long. */
    static long floorMinimum(long quoted, int slippageBps) {
        return BigInteger.valueOf(quoted)
                .multiply(BigInteger.valueOf(10_000L - slippageBps))
                .divide(BPS)
                .longValueExact();
    }

    /** Walks the instructions once and keeps what the fee and the route need. */
    private final class Shape {
        private final SolanaMessage message;
        private final byte[] bot;
        private final SwapContext context;
        private Long unitLimit;
        private Long unitPrice;
        private int otherInstructions;
        private boolean swapSeen;
        private boolean wrapSeen;

        Shape(SolanaMessage message, byte[] bot, SwapContext context) {
            this.message = message;
            this.bot = bot;
            this.context = context;
        }

        PolicyRefusal accept(CompiledInstruction instruction) {
            byte[] program = message.programId(instruction);
            if (Arrays.equals(program, COMPUTE_BUDGET_PROGRAM)) {
                return computeBudget(instruction);
            }
            otherInstructions++;
            if (Arrays.equals(program, SYSTEM_PROGRAM)) {
                return system(instruction);
            }
            if (Arrays.equals(program, ASSOCIATED_TOKEN_PROGRAM)) {
                return associatedToken(instruction);
            }
            if (isTokenProgram(program)) {
                return token(program, instruction);
            }
            if (Arrays.equals(program, JUPITER_PROGRAM)) {
                return jupiter(instruction);
            }
            return PolicyRefusal.PROGRAM_NOT_ALLOWED;
        }

        private byte[] account(CompiledInstruction instruction, int position) {
            return message.accountKey(instruction.accountIndexes()[position]);
        }

        private PolicyRefusal computeBudget(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (instruction.accountIndexes().length != 0 || data.length == 0) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            ByteBuffer value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data[0] == 2 && data.length == 5 && unitLimit == null) {
                long units = Integer.toUnsignedLong(value.getInt(1));
                if (units > MAX_UNITS) {
                    return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
                }
                unitLimit = units;
                return null;
            }
            if (data[0] == 3 && data.length == 9 && unitPrice == null) {
                long price = value.getLong(1);
                if (price < 0) {
                    return PolicyRefusal.PRIORITY_FEE_TOO_HIGH;
                }
                unitPrice = price;
                return null;
            }
            return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
        }

        /** Only one transfer of SOL from the bot wallet to its own wrapped SOL account (the wrap of a buy). */
        private PolicyRefusal system(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (data.length < 4) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            int kind =
                    ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (kind == 4) {
                return PolicyRefusal.DURABLE_NONCE_NOT_ALLOWED;
            }
            if (kind != 2 || data.length != 12 || instruction.accountIndexes().length != 2 || wrapSeen) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            wrapSeen = true;
            if (!Arrays.equals(account(instruction, 0), bot)) {
                return PolicyRefusal.FUNDS_OWNER_NOT_ALLOWED;
            }
            return isBotAccount(account(instruction, 1), WRAPPED_SOL) ? null : PolicyRefusal.DESTINATION_NOT_ALLOWED;
        }

        /** Create or CreateIdempotent of an associated token account of the bot wallet, paid by the bot wallet. */
        private PolicyRefusal associatedToken(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            boolean create = data.length == 0 || (data.length == 1 && (data[0] == 0 || data[0] == 1));
            if (!create || instruction.accountIndexes().length < 6) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            if (!Arrays.equals(account(instruction, 0), bot)) {
                return PolicyRefusal.RENT_PAYER_NOT_ALLOWED;
            }
            byte[] wallet = account(instruction, 2);
            if (!Arrays.equals(wallet, bot)) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            byte[] tokenProgram = account(instruction, 5);
            if (!isTokenProgram(tokenProgram)) {
                return PolicyRefusal.PROGRAM_NOT_ALLOWED;
            }
            if (!Arrays.equals(account(instruction, 4), SYSTEM_PROGRAM)) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            byte[] expected = ProgramAddress.associatedTokenAccount(wallet, tokenProgram, account(instruction, 3));
            return Arrays.equals(account(instruction, 1), expected) ? null : PolicyRefusal.DESTINATION_NOT_ALLOWED;
        }

        private PolicyRefusal token(byte[] program, CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (data.length == 0) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            int[] accounts = instruction.accountIndexes();
            switch (data[0]) {
                case 17 -> {
                    // SyncNative of the wrapped SOL account of the bot wallet.
                    if (accounts.length != 1 || data.length != 1) {
                        return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
                    }
                    byte[] expected = ProgramAddress.associatedTokenAccount(bot, program, WRAPPED_SOL);
                    return Arrays.equals(account(instruction, 0), expected)
                            ? null
                            : PolicyRefusal.DESTINATION_NOT_ALLOWED;
                }
                case 9 -> {
                    // CloseAccount: the rent goes back to the bot wallet, which owns the account.
                    if (accounts.length < 3 || data.length != 1) {
                        return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
                    }
                    boolean toBot =
                            Arrays.equals(account(instruction, 1), bot) && Arrays.equals(account(instruction, 2), bot);
                    return toBot ? null : PolicyRefusal.CLOSE_NOT_ALLOWED;
                }
                case 4, 6, 13 -> {
                    return PolicyRefusal.AUTHORITY_CHANGE;
                }
                default -> {
                    return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
                }
            }
        }

        /** The one route instruction of the Jupiter v6 program. */
        private PolicyRefusal jupiter(CompiledInstruction instruction) {
            byte[] data = instruction.data();
            if (swapSeen || data.length < 8 + ROUTE_TAIL) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            byte[] discriminator = Arrays.copyOf(data, 8);
            boolean shared = Arrays.equals(discriminator, SHARED_ACCOUNTS_ROUTE);
            if (!shared && !Arrays.equals(discriminator, ROUTE)) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            int[] accounts = instruction.accountIndexes();
            if (accounts.length < (shared ? 13 : 9)) {
                return PolicyRefusal.INSTRUCTION_NOT_ALLOWED;
            }
            swapSeen = true;
            int authority = shared ? 2 : 1;
            int destination = shared ? 6 : 3;
            int destinationMint = shared ? 8 : 5;
            int platformFee = shared ? 9 : 6;
            if (!isTokenProgram(account(instruction, 0))) {
                return PolicyRefusal.PROGRAM_NOT_ALLOWED;
            }
            if (!Arrays.equals(account(instruction, authority), bot)) {
                return PolicyRefusal.FUNDS_OWNER_NOT_ALLOWED;
            }
            byte[] outMint = account(instruction, destinationMint);
            boolean toSol = Arrays.equals(outMint, WRAPPED_SOL);
            if (toSol != (context.side() == SwapSide.SELL)) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            // Review of the U4 (Minor 4): the route is bound to the order of the context. A BUY spends the wrapped SOL
            // of the bot wallet and receives the mint of the order; a SELL spends the bot wallet's account of the mint
            // of the order. The amount spent is the input of the context (routeAmounts, AMOUNT_MISMATCH).
            byte[] orderMint = Base58.decodeExact(context.mint(), 32);
            byte[] inMint = context.side() == SwapSide.BUY ? WRAPPED_SOL : orderMint;
            if (context.side() == SwapSide.BUY && !Arrays.equals(outMint, orderMint)) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            byte[] source = account(instruction, shared ? 3 : 2);
            boolean botSource = context.side() == SwapSide.BUY
                    ? Arrays.equals(source, ProgramAddress.associatedTokenAccount(bot, TOKEN_PROGRAM, WRAPPED_SOL))
                    : isBotAccount(source, orderMint);
            if (!botSource) {
                return PolicyRefusal.SOURCE_NOT_ALLOWED;
            }
            if (shared && !Arrays.equals(account(instruction, 7), inMint)) {
                return PolicyRefusal.SOURCE_NOT_ALLOWED;
            }
            byte[] out = account(instruction, destination);
            if (!isBotAccount(out, outMint)) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            if (!shared) {
                byte[] optional = account(instruction, 4);
                if (!Arrays.equals(optional, JUPITER_PROGRAM) && !Arrays.equals(optional, out)) {
                    return PolicyRefusal.DESTINATION_NOT_ALLOWED;
                }
            }
            if (!Arrays.equals(account(instruction, platformFee), JUPITER_PROGRAM)) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            return routeAmounts(data);
        }

        /** The tail of the route data: in amount, quoted out amount, slippage and platform fee. */
        private PolicyRefusal routeAmounts(byte[] data) {
            ByteBuffer tail =
                    ByteBuffer.wrap(data, data.length - ROUTE_TAIL, ROUTE_TAIL).order(ByteOrder.LITTLE_ENDIAN);
            long in = tail.getLong();
            long quoted = tail.getLong();
            int slippage = Short.toUnsignedInt(tail.getShort());
            int platformFeeBps = Byte.toUnsignedInt(tail.get());
            if (platformFeeBps != 0) {
                return PolicyRefusal.DESTINATION_NOT_ALLOWED;
            }
            if (slippage > MAX_SLIPPAGE_BPS) {
                return PolicyRefusal.SLIPPAGE_TOO_HIGH;
            }
            if (in != context.input()) {
                return PolicyRefusal.AMOUNT_MISMATCH;
            }
            if (quoted < 0 || floorMinimum(quoted, slippage) < context.minOut()) {
                return PolicyRefusal.MIN_OUT_TOO_LOW;
            }
            return null;
        }

        /** The associated token account of the bot wallet for {@code mint}, under Token or Token-2022. */
        private boolean isBotAccount(byte[] account, byte[] mint) {
            return Arrays.equals(account, ProgramAddress.associatedTokenAccount(bot, TOKEN_PROGRAM, mint))
                    || Arrays.equals(account, ProgramAddress.associatedTokenAccount(bot, TOKEN_2022_PROGRAM, mint));
        }
    }

    private static boolean isTokenProgram(byte[] program) {
        return Arrays.equals(program, TOKEN_PROGRAM) || Arrays.equals(program, TOKEN_2022_PROGRAM);
    }

    private static byte[] discriminator(String name) {
        try {
            return Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(("global:" + name).getBytes(StandardCharsets.UTF_8)),
                    8);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** The programs a swap may call, for the documentation of the policy and its tests. */
    public static List<String> allowedPrograms() {
        return List.of(
                Base58.encode(JUPITER_PROGRAM),
                Base58.encode(TOKEN_PROGRAM),
                Base58.encode(TOKEN_2022_PROGRAM),
                Base58.encode(ASSOCIATED_TOKEN_PROGRAM),
                Base58.encode(COMPUTE_BUDGET_PROGRAM),
                Base58.encode(SYSTEM_PROGRAM));
    }
}
