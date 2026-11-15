package com.memetrade.txsigner.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.testsupport.JupiterSwapFixture;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Step 7, attack tests written before the code (BR5.1, BR5.2, NFR1.4, NFR4.3, AC5.1.1): the TxSigner signs a
 * withdrawal only when the transaction has EXACTLY the allowed shape, instruction by instruction. Every other shape is
 * refused with a reason code, and a malformed transaction is refused, never signed.
 */
class WithdrawalPolicyTest {

    private static final byte[] BOT = TxBytes.filled(1);
    private static final byte[] MAIN = TxBytes.filled(2);
    private static final byte[] STRANGER = TxBytes.filled(3);
    private static final long AMOUNT = 500_000_000L;
    private static final long MAX_FEE = 100_000L;

    private final WithdrawalPolicy policy = new WithdrawalPolicy(1_000, 5_000L);

    /** Fee of the canonical shape: 5000 per signature plus ceil(450 units x 2 micro-lamports / 10^6) = 1. */
    private static final long CANONICAL_FEE = 5_001L;

    private static byte[] canonical() {
        return TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L).build();
    }

    private PolicyDecision evaluate(byte[] message, WithdrawalContext context) {
        return evaluateWire(TxBytes.unsigned(message, signatureCount(message)), context);
    }

    private PolicyDecision evaluateWire(byte[] wire, WithdrawalContext context) {
        return policy.evaluate(new WithdrawalCheck(
                Base64.getEncoder().encodeToString(wire), Base58.encode(BOT), Base58.encode(MAIN), context, MAX_FEE));
    }

    private static WithdrawalContext context(long lamports, long fee) {
        return new WithdrawalContext(lamports, fee);
    }

    private static int signatureCount(byte[] message) {
        return (message[0] & 0x80) != 0 ? message[1] : message[0];
    }

    private PolicyRefusal refusalOf(byte[] message) {
        PolicyDecision decision = evaluate(message, context(AMOUNT, CANONICAL_FEE));
        assertThat(decision.approved()).as("must be refused").isFalse();
        return decision.refusal();
    }

    @Test
    void theExactWithdrawalShapeIsApproved() {
        PolicyDecision decision = evaluate(canonical(), context(AMOUNT, CANONICAL_FEE));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.refusal()).isNull();
        assertThat(decision.transaction().messageBytes()).isEqualTo(canonical());
    }

    @Test
    void aWithdrawalWithoutComputeBudgetInstructionsIsApprovedWithTheBaseFee() {
        byte[] message = TxBytes.withdrawal(BOT, MAIN, AMOUNT, null, null).build();

        assertThat(evaluate(message, context(AMOUNT, 5_000L)).approved()).isTrue();
    }

    @ParameterizedTest(name = "system instruction {0}")
    @ValueSource(ints = {0, 1, 3, 8, 9, 10, 11, 12})
    void aHiddenSystemInstructionIsRefused(int systemIndex) {
        // 0 CreateAccount, 1 Assign, 3 CreateAccountWithSeed, 8 Allocate, 9 AllocateWithSeed, 10 AssignWithSeed,
        // 11 TransferWithSeed, 12 UpgradeNonceAccount.
        byte[] message = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .instruction(2, new int[] {0}, TxBytes.systemInstruction(systemIndex, STRANGER))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void twoTransfersAreRefused() {
        byte[] message = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .instruction(2, new int[] {0, 1}, TxBytes.transfer(1L))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void aDurableNonceIsRefused() {
        byte[] message = TxBytes.message()
                .header(1, 0, 2)
                .key(BOT)
                .key(MAIN)
                .key(TxBytes.SYSTEM_PROGRAM)
                .key(TxBytes.COMPUTE_BUDGET_PROGRAM)
                .instruction(2, new int[] {1, 0}, TxBytes.systemInstruction(4, new byte[0]))
                .instruction(2, new int[] {0, 1}, TxBytes.transfer(AMOUNT))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.DURABLE_NONCE_NOT_ALLOWED);
    }

    @Test
    void anAddressLookupTableIsRefused() {
        byte[] message = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .addressTable(STRANGER, new int[] {0}, new int[0])
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.ALT_NOT_ALLOWED);
    }

    @Test
    void aPriorityPriceAboveTheFeeCeilingIsRefused() {
        // ceil(1000 x 100_000_000 / 10^6) = 100_000 of priority plus 5000 of base = 105_000 > 100_000.
        byte[] message =
                TxBytes.withdrawal(BOT, MAIN, AMOUNT, 1_000, 100_000_000L).build();

        assertThat(evaluate(message, context(AMOUNT, 105_000L)).refusal()).isEqualTo(PolicyRefusal.FEE_TOO_HIGH);
    }

    @Test
    void aPriceThatOverflowsTheFeeIsRefused() {
        byte[] message =
                TxBytes.withdrawal(BOT, MAIN, AMOUNT, 1_000, Long.MAX_VALUE).build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.FEE_TOO_HIGH);
    }

    @Test
    void aComputeUnitLimitAboveTheCeilingOrRepeatedIsRefused() {
        byte[] tooHigh = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 1_001, 2L).build();
        byte[] twice = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .instruction(3, new int[0], TxBytes.setComputeUnitLimit(400))
                .build();
        byte[] heapFrame = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .instruction(3, new int[0], TxBytes.concat(new byte[] {1}, TxBytes.u32(32_768)))
                .build();

        assertThat(refusalOf(tooHigh)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
        assertThat(refusalOf(twice)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
        assertThat(refusalOf(heapFrame)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void aSecondSignerIsRefused() {
        byte[] message = TxBytes.message()
                .header(2, 0, 1)
                .key(BOT)
                .key(STRANGER)
                .key(MAIN)
                .key(TxBytes.SYSTEM_PROGRAM)
                .instruction(3, new int[] {0, 2}, TxBytes.transfer(AMOUNT))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.EXTRA_SIGNER);
    }

    @Test
    void anotherFeePayerIsRefused() {
        byte[] message = TxBytes.message()
                .header(1, 0, 1)
                .key(STRANGER)
                .key(BOT)
                .key(MAIN)
                .key(TxBytes.SYSTEM_PROGRAM)
                .instruction(3, new int[] {1, 2}, TxBytes.transfer(AMOUNT))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.FEE_PAYER_MISMATCH);
    }

    @Test
    void aStrangeDestinationIsRefused() {
        byte[] message = TxBytes.withdrawal(BOT, STRANGER, AMOUNT, 450, 2L).build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
    }

    @Test
    void aTransferFromAnotherAccountThanTheBotIsRefused() {
        byte[] message = TxBytes.message()
                .header(1, 0, 2)
                .key(BOT)
                .key(MAIN)
                .key(TxBytes.SYSTEM_PROGRAM)
                .key(TxBytes.COMPUTE_BUDGET_PROGRAM)
                .instruction(2, new int[] {1, 0}, TxBytes.transfer(AMOUNT))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void anAmountOrAFeeDifferentFromTheWithdrawalIsRefused() {
        assertThat(evaluate(canonical(), context(AMOUNT + 1, CANONICAL_FEE)).refusal())
                .isEqualTo(PolicyRefusal.AMOUNT_MISMATCH);
        assertThat(evaluate(canonical(), context(AMOUNT, CANONICAL_FEE + 1)).refusal())
                .isEqualTo(PolicyRefusal.FEE_MISMATCH);
    }

    @Test
    void aMissingContextIsRefused() {
        assertThat(evaluate(canonical(), null).refusal()).isEqualTo(PolicyRefusal.CONTEXT_MISSING);
        assertThat(evaluate(canonical(), new WithdrawalContext(null, CANONICAL_FEE))
                        .refusal())
                .isEqualTo(PolicyRefusal.CONTEXT_MISSING);
        assertThat(evaluate(canonical(), new WithdrawalContext(AMOUNT, null)).refusal())
                .isEqualTo(PolicyRefusal.CONTEXT_MISSING);
    }

    @Test
    void anotherProgramIsRefused() {
        byte[] closeAccount = TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L)
                .key(JupiterSwapFixture.TOKEN_PROGRAM)
                .header(1, 0, 3)
                .instruction(4, new int[] {0, 1, 0}, new byte[] {9})
                .build();

        assertThat(refusalOf(closeAccount)).isEqualTo(PolicyRefusal.PROGRAM_NOT_ALLOWED);
    }

    @Test
    void anAccountOutsideTheWithdrawalIsRefused() {
        byte[] message =
                TxBytes.withdrawal(BOT, MAIN, AMOUNT, 450, 2L).key(STRANGER).build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.ACCOUNT_NOT_ALLOWED);
    }

    @Test
    void transferDataWithExtraBytesIsRefused() {
        byte[] message = TxBytes.message()
                .header(1, 0, 2)
                .key(BOT)
                .key(MAIN)
                .key(TxBytes.SYSTEM_PROGRAM)
                .key(TxBytes.COMPUTE_BUDGET_PROGRAM)
                .instruction(2, new int[] {0, 1}, TxBytes.concat(TxBytes.transfer(AMOUNT), new byte[] {0}))
                .build();

        assertThat(refusalOf(message)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void aMalformedTransactionIsRefusedNeverSigned() {
        PolicyDecision garbage = policy.evaluate(new WithdrawalCheck(
                "AAAA", Base58.encode(BOT), Base58.encode(MAIN), context(AMOUNT, CANONICAL_FEE), MAX_FEE));
        PolicyDecision notBase64 = policy.evaluate(new WithdrawalCheck(
                "@@@", Base58.encode(BOT), Base58.encode(MAIN), context(AMOUNT, CANONICAL_FEE), MAX_FEE));

        assertThat(garbage.refusal()).isEqualTo(PolicyRefusal.MALFORMED_TRANSACTION);
        assertThat(notBase64.refusal()).isEqualTo(PolicyRefusal.MALFORMED_TRANSACTION);
    }

    @Test
    void aSwapShapedTransactionIsNeverAWithdrawal() {
        byte[] swap = JupiterSwapFixture.message(BOT);

        assertThat(refusalOf(swap)).isEqualTo(PolicyRefusal.ALT_NOT_ALLOWED);
    }
}
