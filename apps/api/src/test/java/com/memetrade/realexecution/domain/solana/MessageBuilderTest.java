package com.memetrade.realexecution.domain.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.shared.solana.Base58Codec;
import com.memetrade.shared.solana.SolanaVectors;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 3 of the U4, written BEFORE the builder (U4-NFR1.1, U4-NFR1.5, deviation of Q2): the server builds the v0
 * message of the swap with STATIC keys only, in the key order of the Solana runtime (fee payer first, then writable
 * signers, readonly signers, writable and readonly accounts, each group in first-appearance order), measures it, and
 * keeps its SHA-256. A v0 message without lookup tables is the legacy message with the prefix 0x80 and an empty table
 * list, so it is checked against the legacy bytes of the SDK vectors.
 */
@Tag("u4-real-execution")
class MessageBuilderTest {

    private static List<SwapInstruction> instructions(SolanaVectors.MessageVector vector) {
        List<SwapInstruction> list = new ArrayList<>();
        for (SolanaVectors.InstructionVector ix : vector.instructions()) {
            List<AccountRef> accounts = new ArrayList<>();
            for (SolanaVectors.AccountVector account : ix.accounts()) {
                accounts.add(new AccountRef(key(account.pubkey()), account.signer(), account.writable()));
            }
            list.add(new SwapInstruction(key(ix.programId()), accounts, ix.data()));
        }
        return list;
    }

    private static byte[] key(String base58) {
        return Base58Codec.decode(base58, 32);
    }

    private static byte[] v0Of(byte[] legacy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80);
        out.writeBytes(legacy);
        out.write(0);
        return out.toByteArray();
    }

    private static BuiltMessage build(SolanaVectors.MessageVector vector) {
        return MessageBuilder.build(key(vector.feePayer()), instructions(vector), key(vector.blockhash()));
    }

    @Test
    void everyVectorIsBuiltAsTheV0FormOfTheSdkMessage() {
        for (SolanaVectors.MessageVector vector : SolanaVectors.messages()) {
            assertThat(build(vector).messageBytes()).as(vector.name()).isEqualTo(v0Of(vector.legacyMessage()));
        }
    }

    @Test
    void theFeePayerComesFirstAndTheSignedModeHasTwoSigners() {
        BuiltMessage auto = build(SolanaVectors.message("auto-full-buy"));
        BuiltMessage signed = build(SolanaVectors.message("signed-buy"));

        assertThat(auto.signerCount()).isEqualTo(1);
        assertThat(signed.signerCount()).isEqualTo(2);
        assertThat(Arrays.copyOfRange(signed.messageBytes(), 5, 37))
                .isEqualTo(key(SolanaVectors.message("signed-buy").feePayer()));
        assertThat(Arrays.copyOfRange(signed.messageBytes(), 37, 69))
                .isEqualTo(key(SolanaVectors.message("signed-buy").signers().get(1)));
    }

    @Test
    void theMessageHasNoLookupTable() {
        byte[] message = build(SolanaVectors.message("signed-sell")).messageBytes();

        assertThat(message[0] & 0xFF).isEqualTo(0x80);
        assertThat(message[message.length - 1]).isZero();
    }

    @Test
    void theDigestIsTheSha256OfTheMessageBytes() throws Exception {
        BuiltMessage built = build(SolanaVectors.message("auto-full-sell"));

        assertThat(built.digestHex())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(built.messageBytes())));
    }

    @Test
    void theUnsignedTransactionHasOneEmptySlotPerSignerAndTheMessage() {
        BuiltMessage built = build(SolanaVectors.message("signed-buy"));
        WireTransaction wire = WireTransaction.decodeBase64(built.unsignedTransactionBase64());

        assertThat(wire.signatures()).hasSize(2).allSatisfy(slot -> assertThat(slot)
                .containsOnly(0));
        assertThat(wire.messageBytes()).isEqualTo(built.messageBytes());
        assertThat(built.transactionSize()).isEqualTo(1 + 2 * 64 + built.messageBytes().length);
    }

    @Test
    void theSignedVectorsFitOnePacket() {
        for (SolanaVectors.MessageVector vector : SolanaVectors.messages()) {
            assertThat(build(vector).transactionSize())
                    .as(vector.name())
                    .isLessThanOrEqualTo(MessageBuilder.MAX_TRANSACTION_SIZE);
        }
    }

    @Test
    void aRouteThatDoesNotFitOnePacketIsRefusedAsTooLarge() {
        SolanaVectors.MessageVector vector = SolanaVectors.message("signed-buy");
        List<SwapInstruction> large = new ArrayList<>(instructions(vector));
        List<AccountRef> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            byte[] synthetic = new byte[32];
            Arrays.fill(synthetic, (byte) (100 + i));
            many.add(new AccountRef(synthetic, false, false));
        }
        large.add(new SwapInstruction(key(vector.instructions().getLast().programId()), many, new byte[8]));

        MessagePlan plan = MessagePlan.of(key(vector.feePayer()), large);
        assertThat(plan.fitsOnePacket()).isFalse();
        assertThatThrownBy(() -> MessageBuilder.build(key(vector.feePayer()), large, key(vector.blockhash())))
                .isInstanceOf(RouteTooLargeException.class);
    }

    @Test
    void aKeyUsedTwiceIsMergedWithTheStrongestFlagsInFirstAppearanceOrder() {
        byte[] payer = filled(1);
        byte[] program = filled(2);
        byte[] shared = filled(3);
        byte[] other = filled(4);
        List<SwapInstruction> list = List.of(
                new SwapInstruction(program, List.of(new AccountRef(shared, false, false)), new byte[] {1}),
                new SwapInstruction(
                        program,
                        List.of(new AccountRef(other, false, false), new AccountRef(shared, false, true)),
                        new byte[] {2}));

        MessagePlan plan = MessagePlan.of(payer, list);

        assertThat(plan.keys()).hasSize(4);
        assertThat(plan.keys().get(0)).isEqualTo(payer);
        assertThat(plan.keys().get(1))
                .as("the writable account comes before the readonly ones")
                .isEqualTo(shared);
        assertThat(plan.keys().get(2)).isEqualTo(program);
        assertThat(plan.keys().get(3)).isEqualTo(other);
        assertThat(plan.readonlyUnsigned()).isEqualTo(2);
    }

    @Test
    void theFeePayerIsAlwaysAWritableSignerEvenWhenAnInstructionReadsItOnly() {
        byte[] payer = filled(1);
        byte[] program = filled(2);
        MessagePlan plan = MessagePlan.of(
                payer,
                List.of(new SwapInstruction(program, List.of(new AccountRef(payer, false, false)), new byte[0])));

        assertThat(plan.requiredSignatures()).isEqualTo(1);
        assertThat(plan.readonlySigned()).isZero();
        assertThat(plan.keys().getFirst()).isEqualTo(payer);
    }

    @Test
    void invalidInputsAreRefusedBeforeAnyByteIsWritten() {
        byte[] payer = filled(1);
        List<SwapInstruction> one = List.of(new SwapInstruction(filled(2), List.of(), new byte[] {1}));

        assertThatThrownBy(() -> MessageBuilder.build(payer, List.of(), filled(9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageBuilder.build(new byte[31], one, filled(9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageBuilder.build(payer, one, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountRef(new byte[33], false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWireReaderRefusesMalformedTransactions() {
        BuiltMessage built = build(SolanaVectors.message("auto-full-buy"));
        byte[] wire = Base64.getDecoder().decode(built.unsignedTransactionBase64());

        assertThatThrownBy(() -> WireTransaction.decode(Arrays.copyOf(wire, 30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireTransaction.decode(new byte[] {0, 1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireTransaction.decode(new byte[1300])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireTransaction.decodeBase64("not base64 !"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWireWriterPutsASignatureInItsSlotWithoutTouchingTheMessage() {
        BuiltMessage built = build(SolanaVectors.message("signed-buy"));
        WireTransaction wire = WireTransaction.decodeBase64(built.unsignedTransactionBase64());
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 7);

        WireTransaction signed = wire.withSignature(0, signature);

        assertThat(signed.signatures().get(0)).isEqualTo(signature);
        assertThat(signed.signatures().get(1)).containsOnly(0);
        assertThat(signed.messageBytes()).isEqualTo(built.messageBytes());
        assertThat(WireTransaction.decodeBase64(signed.toBase64()).messageBytes())
                .isEqualTo(built.messageBytes());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
