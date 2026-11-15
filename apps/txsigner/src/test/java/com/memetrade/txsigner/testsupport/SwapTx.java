package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.ProgramAddress;
import com.memetrade.txsigner.policy.SwapContext;
import com.memetrade.txsigner.policy.SwapMode;
import com.memetrade.txsigner.policy.SwapSide;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TEST ONLY: builds swap transactions in the shape of the Jupiter swap-instructions that the api compiles (U4 BR4.2,
 * U4-NFR1.5), written byte by byte with {@link TxBytes} so the policy is checked against bytes it did not produce. Every
 * part of the shape can be changed by one setter, so each attack of BR4.3 differs from a valid swap in ONE thing only.
 * The associated token accounts use {@link ProgramAddress}, which {@code SolanaVectorConformanceTest} checks against the
 * SDK vectors.
 */
public final class SwapTx {

    public static final byte[] SYSTEM = new byte[32];
    public static final byte[] TOKEN = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    public static final byte[] TOKEN_2022 = Base58.decode("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
    public static final byte[] ATA = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
    public static final byte[] COMPUTE_BUDGET = Base58.decode("ComputeBudget111111111111111111111111111111");
    public static final byte[] JUPITER = Base58.decode("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4");
    public static final byte[] EVENT_AUTHORITY = Base58.decode("D8cy77BBepLMngZx6ZukaTff5hCt1HrWyKk3Hnd9oitf");
    public static final byte[] WSOL = Base58.decode("So11111111111111111111111111111111111111112");

    public final byte[] bot;
    public final TestWallet main;
    public SwapMode mode = SwapMode.AUTO_FULL;
    public SwapSide side = SwapSide.BUY;
    public byte[] mint = TxBytes.filled(43);
    public long in = 50_000_000L;
    public long quoted = 123_456_789L;
    public int slippageBps = 200;
    public int dataSlippageBps = -1;
    public long dataQuoted = -1;
    public long dataIn = -1;
    public int platformFeeBps;
    public int unitLimit = 280_000;
    public long unitPrice = 12_345L;
    public boolean sharedAccountsRoute;
    public boolean lookupTable;
    public byte[] feePayer;
    public byte[] routeAuthority;
    public byte[] routeDestination;
    /** Review of the U4 (Minor 4): the account the route spends, and the source mint of the shared route. */
    public byte[] routeSource;

    public byte[] routeSourceMint;
    public byte[] optionalDestination;
    public byte[] platformFeeAccount;
    public byte[] ataFunder;
    public byte[] ataWallet;
    public byte[] closeDestination;
    public byte[] transferFrom;
    public byte[] transferTo;
    public byte[] mainSignature;
    public final List<Ix> extra = new ArrayList<>();

    /** One instruction: program, accounts (key with flags) and data. */
    public record Ix(byte[] program, List<Meta> accounts, byte[] data) {}

    /** One account of an instruction. */
    public record Meta(byte[] key, boolean signer, boolean writable) {}

    public SwapTx(byte[] bot, TestWallet main) {
        this.bot = bot.clone();
        this.main = main;
    }

    public static byte[] ata(byte[] owner, byte[] tokenProgram, byte[] mint) {
        return ProgramAddress.associatedTokenAccount(owner, tokenProgram, mint);
    }

    public static Meta signer(byte[] key, boolean writable) {
        return new Meta(key, true, writable);
    }

    public static Meta account(byte[] key, boolean writable) {
        return new Meta(key, false, writable);
    }

    public byte[] wsolAccount() {
        return ata(bot, TOKEN, WSOL);
    }

    public byte[] tokenAccount() {
        return ata(bot, TOKEN, mint);
    }

    /** The context the BotWallet sends with this swap; the fee is the one the message really costs. */
    public SwapContext context() {
        long minOut = quoted * (10_000L - slippageBps) / 10_000L;
        return new SwapContext(
                mode,
                side,
                side == SwapSide.BUY ? in : null,
                side == SwapSide.SELL ? in : null,
                quoted,
                minOut,
                slippageBps,
                fee(),
                com.memetrade.txsigner.codec.Base58.encode(mint));
    }

    public long fee() {
        int signatures = mode == SwapMode.SIGNED ? 2 : 1;
        long priority = (unitPrice * unitLimit + 999_999L) / 1_000_000L;
        return 5_000L * signatures + priority;
    }

    public List<Ix> instructions() {
        byte[] wsol = wsolAccount();
        byte[] token = tokenAccount();
        List<Ix> list = new ArrayList<>();
        list.add(new Ix(COMPUTE_BUDGET, List.of(), TxBytes.setComputeUnitLimit(unitLimit)));
        list.add(new Ix(COMPUTE_BUDGET, List.of(), TxBytes.setComputeUnitPrice(unitPrice)));
        list.add(createIdempotent(wsol, WSOL));
        if (side == SwapSide.BUY) {
            list.add(new Ix(
                    SYSTEM,
                    List.of(signer(or(transferFrom, bot), true), account(or(transferTo, wsol), true)),
                    TxBytes.concat(TxBytes.u32(2), TxBytes.u64(in))));
            list.add(new Ix(TOKEN, List.of(account(wsol, true)), new byte[] {17}));
            list.add(createIdempotent(token, mint));
            list.add(route(wsol, token, mint));
        } else {
            list.add(route(token, wsol, WSOL));
        }
        list.add(new Ix(
                TOKEN,
                List.of(account(wsol, true), account(or(closeDestination, bot), true), signer(bot, false)),
                new byte[] {9}));
        list.addAll(extra);
        return list;
    }

    private Ix createIdempotent(byte[] account, byte[] tokenMint) {
        return new Ix(
                ATA,
                List.of(
                        new Meta(or(ataFunder, bot), Arrays.equals(or(ataFunder, bot), bot), true),
                        account(account, true),
                        account(or(ataWallet, bot), false),
                        account(tokenMint, false),
                        account(SYSTEM, false),
                        account(TOKEN, false)),
                new byte[] {1});
    }

    private Ix route(byte[] source, byte[] destination, byte[] destinationMint) {
        List<Meta> accounts = new ArrayList<>();
        byte[] authority = or(routeAuthority, bot);
        if (sharedAccountsRoute) {
            accounts.add(account(TOKEN, false));
            accounts.add(account(TxBytes.filled(70), false));
            accounts.add(new Meta(authority, Arrays.equals(authority, bot), false));
            accounts.add(account(or(routeSource, source), true));
            accounts.add(account(TxBytes.filled(71), true));
            accounts.add(account(TxBytes.filled(72), true));
            accounts.add(account(or(routeDestination, destination), true));
            accounts.add(account(or(routeSourceMint, side == SwapSide.BUY ? WSOL : mint), false));
            accounts.add(account(destinationMint, false));
            accounts.add(account(or(platformFeeAccount, JUPITER), false));
            accounts.add(account(JUPITER, false));
            accounts.add(account(EVENT_AUTHORITY, false));
            accounts.add(account(JUPITER, false));
        } else {
            accounts.add(account(TOKEN, false));
            accounts.add(new Meta(authority, Arrays.equals(authority, bot), false));
            accounts.add(account(or(routeSource, source), true));
            accounts.add(account(or(routeDestination, destination), true));
            accounts.add(account(or(optionalDestination, JUPITER), false));
            accounts.add(account(destinationMint, false));
            accounts.add(account(or(platformFeeAccount, JUPITER), false));
            accounts.add(account(EVENT_AUTHORITY, false));
            accounts.add(account(JUPITER, false));
        }
        for (int pool = 0; pool < 4; pool++) {
            accounts.add(account(TxBytes.filled(60 + pool), pool % 2 == 0));
        }
        byte[] discriminator = discriminator(sharedAccountsRoute ? "shared_accounts_route" : "route");
        byte[] plan =
                sharedAccountsRoute ? new byte[] {3, 1, 0, 0, 0, 7, 100, 0, 1} : new byte[] {1, 0, 0, 0, 7, 100, 0, 1};
        ByteBuffer tail = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        tail.putLong(dataIn >= 0 ? dataIn : in)
                .putLong(dataQuoted >= 0 ? dataQuoted : quoted)
                .putShort((short) (dataSlippageBps >= 0 ? dataSlippageBps : slippageBps))
                .put((byte) platformFeeBps);
        return new Ix(JUPITER, accounts, TxBytes.concat(TxBytes.concat(discriminator, plan), tail.array()));
    }

    public static byte[] discriminator(String name) {
        try {
            return Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(("global:" + name).getBytes(StandardCharsets.UTF_8)),
                    8);
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** The v0 message (or legacy), with the static keys compiled like the api does. */
    public byte[] message() {
        byte[] payer = or(feePayer, mode == SwapMode.SIGNED ? main.publicKey() : bot);
        Map<String, Meta> merged = new LinkedHashMap<>();
        put(merged, new Meta(payer, true, true));
        if (mode == SwapMode.SIGNED) {
            put(merged, new Meta(bot, true, true));
        }
        List<Ix> instructions = instructions();
        for (Ix ix : instructions) {
            ix.accounts().forEach(meta -> put(merged, meta));
            put(merged, new Meta(ix.program(), false, false));
        }
        List<Meta> keys = new ArrayList<>(merged.values());
        keys.sort((a, b) -> a.signer() != b.signer()
                ? (a.signer() ? -1 : 1)
                : a.writable() != b.writable() ? (a.writable() ? -1 : 1) : 0);
        int signers = (int) keys.stream().filter(Meta::signer).count();
        int readonlySigned =
                (int) keys.stream().filter(k -> k.signer() && !k.writable()).count();
        int readonlyUnsigned =
                (int) keys.stream().filter(k -> !k.signer() && !k.writable()).count();
        TxBytes tx = TxBytes.message().header(signers, readonlySigned, readonlyUnsigned);
        keys.forEach(k -> tx.key(k.key()));
        tx.blockhash(TxBytes.filled(9));
        for (Ix ix : instructions) {
            int[] indexes = ix.accounts().stream()
                    .mapToInt(meta -> indexOf(keys, meta.key()))
                    .toArray();
            tx.instruction(indexOf(keys, ix.program()), indexes, ix.data());
        }
        tx.versioned();
        if (lookupTable) {
            tx.addressTable(TxBytes.filled(31), new int[] {0}, new int[] {1});
        }
        return tx.build();
    }

    public int signerCount() {
        return mode == SwapMode.SIGNED ? 2 : 1;
    }

    /** The unsigned wire transaction; in SIGNED, slot 0 already has the signature of the main wallet. */
    public String base64() {
        byte[] message = message();
        int signers = message[1];
        byte[][] slots = new byte[signers][];
        for (int i = 0; i < signers; i++) {
            slots[i] = new byte[64];
        }
        if (mode == SwapMode.SIGNED) {
            slots[0] = mainSignature != null ? mainSignature : main.signRaw(message);
        }
        return Base64.getEncoder().encodeToString(TxBytes.transaction(message, slots));
    }

    private static void put(Map<String, Meta> merged, Meta meta) {
        merged.merge(
                Base58.encode(meta.key()),
                meta,
                (old, added) ->
                        new Meta(old.key(), old.signer() || added.signer(), old.writable() || added.writable()));
    }

    private static int indexOf(List<Meta> keys, byte[] key) {
        for (int i = 0; i < keys.size(); i++) {
            if (Arrays.equals(keys.get(i).key(), key)) {
                return i;
            }
        }
        throw new IllegalStateException("Key not compiled");
    }

    private static byte[] or(byte[] value, byte[] fallback) {
        return value != null ? value : fallback;
    }
}
