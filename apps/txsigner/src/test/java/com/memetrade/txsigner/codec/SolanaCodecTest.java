package com.memetrade.txsigner.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.testsupport.TxBytes;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Step 5 (NFR1.3, BR5.1): the own minimal Solana codec, written before the code. Known vectors and malformed input
 * (length, alphabet, overflow, non-canonical encodings, truncation, trailing bytes, out-of-range indexes).
 */
class SolanaCodecTest {

    private static final byte[] BOT = TxBytes.filled(1);
    private static final byte[] MAIN = TxBytes.filled(2);

    @Test
    void base58KnownVectors() {
        assertThat(Base58.encode(new byte[0])).isEmpty();
        assertThat(Base58.encode(new byte[] {0})).isEqualTo("1");
        assertThat(Base58.encode(new byte[32])).isEqualTo("11111111111111111111111111111111");
        assertThat(Base58.encode(HexFormat.of().parseHex("00010966776006953d5567439e5e39f86a0d273beed61967f6")))
                .isEqualTo("16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM");
        assertThat(HexFormat.of().formatHex(Base58.decode("ComputeBudget111111111111111111111111111111")))
                .isEqualTo("0306466fe5211732ffecadba72c39be7bc8ce5bbc5f7126b2c439b3a40000000");
    }

    @Test
    void base58RoundTripsRandomKeysWithLeadingZeros() {
        Random seeded = new Random(42);
        for (int i = 0; i < 200; i++) {
            byte[] key = new byte[32];
            seeded.nextBytes(key);
            key[0] = (byte) (i % 3 == 0 ? 0 : key[0]);
            assertThat(Base58.decodeExact(Base58.encode(key), 32)).isEqualTo(key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0OIl", "abc0", "has space", "ção"})
    void base58RefusesCharactersOutsideTheAlphabet(String input) {
        assertThatThrownBy(() -> Base58.decode(input)).isInstanceOf(CodecException.class);
    }

    @Test
    void base58RefusesOversizedInputAndWrongLengths() {
        assertThatThrownBy(() -> Base58.decode("1".repeat(Base58.MAX_INPUT_LENGTH + 1)))
                .isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> Base58.decode(null)).isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> Base58.decodeExact(Base58.encode(new byte[31]), 32))
                .isInstanceOf(CodecException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,00", "127,7f", "128,8001", "16383,ff7f", "16384,808001", "65535,ffff03"})
    void compactU16KnownVectors(int value, String hex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompactU16.write(out, value);

        assertThat(HexFormat.of().formatHex(out.toByteArray())).isEqualTo(hex);
        assertThat(CompactU16.read(new ByteReader(HexFormat.of().parseHex(hex))))
                .isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ffff07", "80808001", "8000", "ff00", "80"})
    void compactU16RefusesOverflowNonCanonicalAndTruncated(String hex) {
        assertThatThrownBy(() -> CompactU16.read(new ByteReader(HexFormat.of().parseHex(hex))))
                .isInstanceOf(CodecException.class);
    }

    @Test
    void aLegacyWithdrawalParsesAndSerializesBackToTheSameBytes() {
        byte[] bytes = TxBytes.withdrawal(BOT, MAIN, 123_456L, 450, 2L).build();

        SolanaMessage message = SolanaMessage.parse(bytes);

        assertThat(message.version()).isEqualTo(SolanaMessage.Version.LEGACY);
        assertThat(message.header()).isEqualTo(new MessageHeader(1, 0, 2));
        assertThat(message.accountKeys()).hasSize(4);
        assertThat(message.recentBlockhash()).isEqualTo(TxBytes.filled(7));
        assertThat(message.instructions()).hasSize(3);
        assertThat(message.instructions().get(2).accountIndexes()).containsExactly(0, 1);
        assertThat(message.instructions().get(2).data()).isEqualTo(TxBytes.transfer(123_456L));
        assertThat(message.addressTableLookups()).isEmpty();
        assertThat(message.serialize()).isEqualTo(bytes);
    }

    @Test
    void signerAndWritableFlagsFollowTheHeader() {
        SolanaMessage message = SolanaMessage.parse(
                TxBytes.withdrawal(BOT, MAIN, 1L, null, null).build());

        assertThat(message.isSigner(0)).isTrue();
        assertThat(message.isWritable(0)).isTrue();
        assertThat(message.isSigner(1)).isFalse();
        assertThat(message.isWritable(1)).isTrue();
        assertThat(message.isWritable(2)).isFalse();
        assertThat(message.isWritable(3)).isFalse();
    }

    @Test
    void aV0MessageWithAnAddressTableRoundTrips() {
        byte[] bytes = TxBytes.withdrawal(BOT, MAIN, 1L, null, null)
                .addressTable(TxBytes.filled(5), new int[] {1}, new int[] {2, 3})
                .build();

        SolanaMessage message = SolanaMessage.parse(bytes);

        assertThat(message.version()).isEqualTo(SolanaMessage.Version.V0);
        assertThat(message.addressTableLookups()).singleElement().satisfies(lookup -> {
            assertThat(lookup.tableKey()).isEqualTo(TxBytes.filled(5));
            assertThat(lookup.writableIndexes()).containsExactly(1);
            assertThat(lookup.readonlyIndexes()).containsExactly(2, 3);
        });
        assertThat(message.serialize()).isEqualTo(bytes);
    }

    @Test
    void everyTruncationOfAMessageIsRefused() {
        byte[] bytes = TxBytes.withdrawal(BOT, MAIN, 1L, 450, 1L).build();

        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThatThrownBy(() -> SolanaMessage.parse(truncated))
                    .as("prefix of %d bytes", length)
                    .isInstanceOf(CodecException.class);
        }
    }

    @Test
    void trailingBytesAfterTheMessageAreRefused() {
        byte[] bytes =
                TxBytes.concat(TxBytes.withdrawal(BOT, MAIN, 1L, null, null).build(), new byte[] {0});

        assertThatThrownBy(() -> SolanaMessage.parse(bytes)).isInstanceOf(CodecException.class);
    }

    @Test
    void indexesOutsideTheAccountListAreRefused() {
        byte[] badProgram = TxBytes.message()
                .header(1, 0, 1)
                .key(BOT)
                .key(TxBytes.SYSTEM_PROGRAM)
                .instruction(2, new int[] {0}, TxBytes.transfer(1L))
                .build();
        byte[] badAccount = TxBytes.message()
                .header(1, 0, 1)
                .key(BOT)
                .key(TxBytes.SYSTEM_PROGRAM)
                .instruction(1, new int[] {0, 5}, TxBytes.transfer(1L))
                .build();

        assertThatThrownBy(() -> SolanaMessage.parse(badProgram)).isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> SolanaMessage.parse(badAccount)).isInstanceOf(CodecException.class);
    }

    @Test
    void anInconsistentHeaderIsRefused() {
        byte[] noSigner = TxBytes.message().header(0, 0, 0).key(BOT).build();
        byte[] allReadonly = TxBytes.message().header(1, 1, 0).key(BOT).build();
        byte[] tooManyReadonly =
                TxBytes.message().header(1, 0, 2).key(BOT).key(MAIN).build();

        assertThatThrownBy(() -> SolanaMessage.parse(noSigner)).isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> SolanaMessage.parse(allReadonly)).isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> SolanaMessage.parse(tooManyReadonly)).isInstanceOf(CodecException.class);
    }

    @Test
    void anUnknownMessageVersionIsRefused() {
        byte[] v0 = TxBytes.withdrawal(BOT, MAIN, 1L, null, null).versioned().build();
        byte[] v1 = v0.clone();
        v1[0] = (byte) 0x81;

        assertThat(SolanaMessage.parse(v0).version()).isEqualTo(SolanaMessage.Version.V0);
        assertThatThrownBy(() -> SolanaMessage.parse(v1)).isInstanceOf(CodecException.class);
    }

    @Test
    void aTransactionWithAWrongNumberOfSignaturesIsRefused() {
        byte[] message = TxBytes.withdrawal(BOT, MAIN, 1L, null, null).build();

        assertThatThrownBy(() -> SolanaTransaction.decode(TxBytes.unsigned(message, 2)))
                .isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> SolanaTransaction.decode(TxBytes.unsigned(message, 0)))
                .isInstanceOf(CodecException.class);
    }

    @Test
    void aTransactionLargerThanOnePacketIsRefused() {
        byte[] oversized = TxBytes.message()
                .header(1, 0, 1)
                .key(BOT)
                .key(TxBytes.SYSTEM_PROGRAM)
                .instruction(1, new int[] {0}, new byte[1300])
                .build();

        assertThatThrownBy(() -> SolanaTransaction.decode(TxBytes.unsigned(oversized, 1)))
                .isInstanceOf(CodecException.class);
        assertThatThrownBy(() -> SolanaTransaction.decodeBase64("not base64 !")).isInstanceOf(CodecException.class);
    }

    @Test
    void withSignaturePlacesTheSignatureWithoutTouchingTheMessage() {
        byte[] message = TxBytes.withdrawal(BOT, MAIN, 9L, null, null).build();
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 0x5a);

        SolanaTransaction signed =
                SolanaTransaction.decode(TxBytes.unsigned(message, 1)).withSignature(0, signature);

        assertThat(signed.serialize()).isEqualTo(TxBytes.transaction(message, signature));
        assertThat(signed.messageBytes()).isEqualTo(message);
        assertThat(signed.signatures().get(0)).isEqualTo(signature);
        assertThatThrownBy(() -> signed.withSignature(1, signature)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signed.withSignature(0, new byte[63])).isInstanceOf(IllegalArgumentException.class);
    }
}
