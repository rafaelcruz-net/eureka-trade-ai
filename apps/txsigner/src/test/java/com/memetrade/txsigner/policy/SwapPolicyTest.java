package com.memetrade.txsigner.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.testsupport.SolanaVectors;
import com.memetrade.txsigner.testsupport.SwapTx;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U4, written BEFORE the policy (BR4.3, U4-NFR1.2, U4-NFR1.5, project.md "NEVER deixar o TxSigner
 * assinar fora da política"): one attack per reason of refusal, each one a valid swap changed in ONE thing, plus the
 * swaps that must pass (the SDK vectors in the shape of the Jupiter swap-instructions, legacy, and the same swaps as the
 * api compiles them, v0). Recorded mainnet transactions join this list once {@code record.sh} has run.
 */
class SwapPolicyTest {

    private static final long CAP = 2_000_000_000L;
    private static final long MAX_PRIORITY_FEE = 1_000_000L;

    private final SwapPolicy policy = new SwapPolicy(MAX_PRIORITY_FEE, 5_000L);
    private final TestWallet bot = TestWallet.generate();
    private final TestWallet main = TestWallet.generate();
    private final byte[] stranger = TestWallet.generate().publicKey();

    private SwapTx buy() {
        return new SwapTx(bot.publicKey(), main);
    }

    private SwapTx signedBuy() {
        SwapTx tx = buy();
        tx.mode = SwapMode.SIGNED;
        return tx;
    }

    private SwapTx sell() {
        SwapTx tx = buy();
        tx.side = SwapSide.SELL;
        tx.in = 987_654_321L;
        tx.quoted = 48_000_000L;
        tx.slippageBps = 300;
        return tx;
    }

    private PolicyDecision evaluate(SwapTx tx) {
        return evaluate(tx, tx.context(), OperationMode.AUTO_FULL);
    }

    private PolicyDecision evaluate(SwapTx tx, SwapContext context, OperationMode authorized) {
        return policy.evaluate(new SwapCheck(tx.base64(), bot.address(), main.address(), context, CAP, authorized));
    }

    private PolicyRefusal refusal(SwapTx tx) {
        PolicyDecision decision = evaluate(tx);
        assertThat(decision.approved()).as("the attack must be refused").isFalse();
        return decision.refusal();
    }

    // ----- swaps that pass -----

    @Test
    void theSdkVectorsInTheShapeOfTheJupiterPass() {
        for (String name : List.of("auto-full-buy", "signed-buy", "auto-full-sell", "signed-sell")) {
            JsonNode vector = SolanaVectors.message(name);
            boolean signed = name.startsWith("signed");
            boolean buy = name.endsWith("buy");
            String botWallet = vector.path("signers").get(signed ? 1 : 0).asText();
            String mainWallet = signed ? vector.path("feePayer").asText() : Base58.encode(TxBytes.filled(5));
            long in = buy ? 50_000_000L : 987_654_321L;
            long quoted = buy ? 123_456_789L : 48_000_000L;
            int slippage = buy ? 200 : 300;
            long priority = buy ? (12_345L * 280_000 + 999_999) / 1_000_000 : (10_000L * 250_000 + 999_999) / 1_000_000;
            SwapContext context = new SwapContext(
                    signed ? SwapMode.SIGNED : SwapMode.AUTO_FULL,
                    buy ? SwapSide.BUY : SwapSide.SELL,
                    buy ? in : null,
                    buy ? null : in,
                    quoted,
                    quoted * (10_000 - slippage) / 10_000,
                    slippage,
                    5_000L * (signed ? 2 : 1) + priority,
                    Base58.encode(TxBytes.filled(43)));

            PolicyDecision decision = policy.evaluate(new SwapCheck(
                    vector.path("signedTransactionBase64").asText(),
                    botWallet,
                    mainWallet,
                    context,
                    CAP,
                    OperationMode.AUTO_FULL));

            assertThat(decision.approved()).as(name + ": " + decision.refusal()).isTrue();
        }
    }

    @Test
    void anAutoFullBuyAsTheApiCompilesItPasses() {
        assertThat(evaluate(buy()).approved()).isTrue();
    }

    @Test
    void aSignedBuyWithTheSignatureOfTheMainWalletPasses() {
        assertThat(evaluate(signedBuy(), signedBuy().context(), OperationMode.SIGNED)
                        .approved())
                .isTrue();
    }

    @Test
    void aSellBackToSolPassesInBothModes() {
        SwapTx signed = sell();
        signed.mode = SwapMode.SIGNED;

        assertThat(evaluate(sell()).approved()).isTrue();
        assertThat(evaluate(signed, signed.context(), OperationMode.AUTO_EXITS).approved())
                .isTrue();
    }

    @Test
    void theSharedAccountsRouteOfTheJupiterPasses() {
        SwapTx tx = buy();
        tx.sharedAccountsRoute = true;

        assertThat(evaluate(tx).approved()).isTrue();
    }

    @Test
    void aBuyAtExactlyTheCapPassesButAMinimumTheRouteDoesNotEnforceDoesNot() {
        SwapTx tx = buy();
        tx.in = CAP;
        SwapContext stricter = withMinOut(tx.context(), tx.context().minOut() + 1);

        assertThat(evaluate(tx).approved()).isTrue();
        assertThat(evaluate(tx, stricter, OperationMode.AUTO_FULL).refusal())
                .as("the route must enforce at least the minimum of the context")
                .isEqualTo(PolicyRefusal.MIN_OUT_TOO_LOW);
    }

    // ----- one attack per reason -----

    @Test
    void aMessageWithAnAddressLookupTableIsRefused() {
        SwapTx tx = buy();
        tx.lookupTable = true;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.LOOKUP_TABLE_NOT_ALLOWED);
    }

    @Test
    void anotherSignerOrAnotherFeePayerIsRefused() {
        SwapTx extraSigner = buy();
        extraSigner.extra.add(new SwapTx.Ix(
                SwapTx.SYSTEM,
                List.of(SwapTx.signer(stranger, true), SwapTx.account(bot.publicKey(), true)),
                TxBytes.concat(TxBytes.u32(2), TxBytes.u64(1))));
        SwapTx otherPayer = signedBuy();
        otherPayer.feePayer = stranger;

        assertThat(refusal(extraSigner)).isEqualTo(PolicyRefusal.SIGNER_NOT_ALLOWED);
        assertThat(evaluate(otherPayer, otherPayer.context(), OperationMode.SIGNED)
                        .refusal())
                .isEqualTo(PolicyRefusal.SIGNER_NOT_ALLOWED);
    }

    @Test
    void aSignedSwapWithoutAValidSignatureOfTheMainWalletIsRefused() {
        SwapTx missing = signedBuy();
        missing.mainSignature = new byte[64];
        SwapTx forged = signedBuy();
        forged.mainSignature = TestWallet.generate().signRaw(forged.message());

        assertThat(evaluate(missing, missing.context(), OperationMode.SIGNED).refusal())
                .isEqualTo(PolicyRefusal.MAIN_SIGNATURE_INVALID);
        assertThat(evaluate(forged, forged.context(), OperationMode.SIGNED).refusal())
                .isEqualTo(PolicyRefusal.MAIN_SIGNATURE_INVALID);
    }

    @Test
    void theMainWalletMovingSolOrOwningAnAccountIsRefused() {
        // S27: in SIGNED the main wallet is only the fee payer.
        SwapTx transfers = signedBuy();
        transfers.transferFrom = main.publicKey();
        SwapTx owns = signedBuy();
        owns.ataWallet = main.publicKey();

        assertThat(evaluate(transfers, transfers.context(), OperationMode.SIGNED)
                        .refusal())
                .isEqualTo(PolicyRefusal.MAIN_WALLET_NOT_ALLOWED);
        assertThat(evaluate(owns, owns.context(), OperationMode.SIGNED).refusal())
                .isEqualTo(PolicyRefusal.MAIN_WALLET_NOT_ALLOWED);
    }

    @Test
    void aProgramOutsideTheListIsRefused() {
        SwapTx tx = buy();
        tx.extra.add(new SwapTx.Ix(TxBytes.filled(77), List.of(SwapTx.account(stranger, true)), new byte[] {1}));

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.PROGRAM_NOT_ALLOWED);
    }

    @Test
    void anInstructionOutsideTheShapeIsRefused() {
        SwapTx createAccount = buy();
        createAccount.extra.add(new SwapTx.Ix(
                SwapTx.SYSTEM,
                List.of(SwapTx.signer(bot.publicKey(), true), SwapTx.account(stranger, true)),
                TxBytes.concat(TxBytes.u32(0), new byte[48])));
        SwapTx tokenTransfer = buy();
        tokenTransfer.extra.add(new SwapTx.Ix(
                SwapTx.TOKEN,
                List.of(
                        SwapTx.account(tokenTransfer.tokenAccount(), true),
                        SwapTx.account(stranger, true),
                        SwapTx.signer(bot.publicKey(), false)),
                TxBytes.concat(new byte[] {3}, TxBytes.u64(10))));

        assertThat(refusal(createAccount)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
        assertThat(refusal(tokenTransfer)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void aDurableNonceIsRefused() {
        SwapTx tx = buy();
        tx.extra.add(new SwapTx.Ix(
                SwapTx.SYSTEM,
                List.of(SwapTx.account(stranger, true), SwapTx.signer(bot.publicKey(), false)),
                TxBytes.u32(4)));

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.DURABLE_NONCE_NOT_ALLOWED);
    }

    @Test
    void aPriorityFeeAboveTheCeilingIsRefused() {
        SwapTx tx = buy();
        tx.unitPrice = 5_000_000L;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.PRIORITY_FEE_TOO_HIGH);
    }

    @Test
    void aFeeDifferentFromTheContextIsRefused() {
        SwapTx tx = buy();
        SwapContext context = tx.context();
        SwapContext lower = new SwapContext(
                context.mode(),
                context.side(),
                context.inputLamports(),
                context.inputBaseUnits(),
                context.quotedOut(),
                context.minOut(),
                context.maxSlippageBps(),
                context.feeLamports() - 1,
                context.mint());

        assertThat(evaluate(tx, lower, OperationMode.AUTO_FULL).refusal()).isEqualTo(PolicyRefusal.FEE_MISMATCH);
    }

    @Test
    void theRentOfANewAccountPaidByAnotherWalletIsRefused() {
        SwapTx tx = buy();
        tx.ataFunder = stranger;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.RENT_PAYER_NOT_ALLOWED);
    }

    @Test
    void fundsOwnedByAnotherWalletAreRefused() {
        SwapTx tx = buy();
        tx.routeAuthority = stranger;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.FUNDS_OWNER_NOT_ALLOWED);
    }

    @Test
    void aDestinationThatIsNotOfTheBotWalletIsRefused() {
        SwapTx route = buy();
        route.routeDestination = SwapTx.ata(stranger, SwapTx.TOKEN, route.mint);
        SwapTx optional = buy();
        optional.optionalDestination = stranger;
        SwapTx platform = buy();
        platform.platformFeeAccount = stranger;
        SwapTx account = buy();
        account.ataWallet = stranger;
        SwapTx wrap = buy();
        wrap.transferTo = stranger;

        assertThat(refusal(route)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
        assertThat(refusal(optional)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
        assertThat(refusal(platform)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
        assertThat(refusal(account)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
        assertThat(refusal(wrap)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
    }

    @Test
    void aPlatformFeeOfTheRouteIsRefused() {
        SwapTx tx = buy();
        tx.platformFeeBps = 50;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
    }

    @Test
    void aMinimumOutputBelowTheQuoteMinusTheSlippageIsRefused() {
        SwapTx context = buy();
        SwapTx routeSlippage = buy();
        routeSlippage.dataSlippageBps = 400;
        SwapTx routeQuote = buy();
        routeQuote.dataQuoted = routeQuote.quoted / 2;

        assertThat(evaluate(
                                context,
                                withMinOut(context.context(), context.context().minOut() - 1),
                                OperationMode.AUTO_FULL)
                        .refusal())
                .isEqualTo(PolicyRefusal.MIN_OUT_TOO_LOW);
        assertThat(refusal(routeSlippage)).isEqualTo(PolicyRefusal.MIN_OUT_TOO_LOW);
        assertThat(refusal(routeQuote)).isEqualTo(PolicyRefusal.MIN_OUT_TOO_LOW);
    }

    @Test
    void aSlippageAboveFiveHundredBasisPointsIsRefused() {
        SwapTx tx = buy();
        tx.slippageBps = 600;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.SLIPPAGE_TOO_HIGH);
    }

    @Test
    void aChangeOfAuthorityOrAnApprovalIsRefused() {
        SwapTx setAuthority = buy();
        setAuthority.extra.add(new SwapTx.Ix(
                SwapTx.TOKEN,
                List.of(SwapTx.account(setAuthority.tokenAccount(), true), SwapTx.signer(bot.publicKey(), false)),
                TxBytes.concat(new byte[] {6, 2, 1}, stranger)));
        SwapTx approve = buy();
        approve.extra.add(new SwapTx.Ix(
                SwapTx.TOKEN,
                List.of(
                        SwapTx.account(approve.tokenAccount(), true),
                        SwapTx.account(stranger, false),
                        SwapTx.signer(bot.publicKey(), false)),
                TxBytes.concat(new byte[] {4}, TxBytes.u64(1_000))));

        assertThat(refusal(setAuthority)).isEqualTo(PolicyRefusal.AUTHORITY_CHANGE);
        assertThat(refusal(approve)).isEqualTo(PolicyRefusal.AUTHORITY_CHANGE);
    }

    @Test
    void closingAnAccountToAnotherWalletIsRefused() {
        SwapTx tx = buy();
        tx.closeDestination = stranger;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.CLOSE_NOT_ALLOWED);
    }

    @Test
    void anInputAboveTheAuthorizedCapIsRefused() {
        SwapTx tx = buy();
        tx.in = CAP + 1;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.CAP_EXCEEDED);
    }

    @Test
    void anAutoFullSwapOfAWalletAuthorizedOnlyToSignIsRefused() {
        SwapTx tx = buy();

        assertThat(evaluate(tx, tx.context(), OperationMode.AUTO_EXITS).refusal())
                .isEqualTo(PolicyRefusal.MODE_NOT_ALLOWED);
        assertThat(evaluate(tx, tx.context(), OperationMode.SIGNED).refusal())
                .isEqualTo(PolicyRefusal.MODE_NOT_ALLOWED);
    }

    @Test
    void aRouteInputDifferentFromTheContextIsRefused() {
        SwapTx tx = buy();
        tx.dataIn = tx.in + 1;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.AMOUNT_MISMATCH);
    }

    // ----- review of the U4 (Minor 4): the source of the route is bound to the context, written before the code -----

    @Test
    void aSellThatSpendsMoreUnitsThanTheContextIsRefused() {
        SwapTx tx = sell();
        tx.dataIn = tx.in * 2;

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.AMOUNT_MISMATCH);
    }

    @Test
    void aBuyWhoseRouteSpendsAnotherAccountThanTheWrappedSolOfTheBotIsRefused() {
        SwapTx tx = buy();
        tx.routeSource = tx.tokenAccount();

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.SOURCE_NOT_ALLOWED);
    }

    @Test
    void aSellWhoseRouteSpendsAnotherMintThanTheOrderIsRefused() {
        SwapTx tx = sell();
        tx.routeSource =
                SwapTx.ata(bot.publicKey(), SwapTx.TOKEN, com.memetrade.txsigner.testsupport.TxBytes.filled(44));

        assertThat(refusal(tx)).isEqualTo(PolicyRefusal.SOURCE_NOT_ALLOWED);
    }

    @Test
    void aSharedRouteWithAnotherSourceMintIsRefused() {
        SwapTx buy = buy();
        buy.sharedAccountsRoute = true;
        buy.routeSourceMint = buy.mint;
        SwapTx sell = sell();
        sell.sharedAccountsRoute = true;
        sell.routeSourceMint = com.memetrade.txsigner.testsupport.TxBytes.filled(44);

        assertThat(refusal(buy)).isEqualTo(PolicyRefusal.SOURCE_NOT_ALLOWED);
        assertThat(refusal(sell)).isEqualTo(PolicyRefusal.SOURCE_NOT_ALLOWED);
    }

    @Test
    void aBuyOfAnotherMintThanTheOrderIsRefused() {
        SwapTx tx = buy();
        SwapContext context = tx.context();
        SwapContext otherMint = new SwapContext(
                context.mode(),
                context.side(),
                context.inputLamports(),
                context.inputBaseUnits(),
                context.quotedOut(),
                context.minOut(),
                context.maxSlippageBps(),
                context.feeLamports(),
                Base58.encode(com.memetrade.txsigner.testsupport.TxBytes.filled(44)));

        assertThat(evaluate(tx, otherMint, OperationMode.AUTO_FULL).refusal())
                .isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
    }

    @Test
    void aContextWithoutTheMintIsRefused() {
        SwapTx tx = buy();
        SwapContext context = tx.context();
        SwapContext noMint = new SwapContext(
                context.mode(),
                context.side(),
                context.inputLamports(),
                context.inputBaseUnits(),
                context.quotedOut(),
                context.minOut(),
                context.maxSlippageBps(),
                context.feeLamports(),
                null);

        assertThat(evaluate(tx, noMint, OperationMode.AUTO_FULL).refusal()).isEqualTo(PolicyRefusal.CONTEXT_MISSING);
    }

    @Test
    void aMissingContextOrAMalformedTransactionIsRefused() {
        SwapTx tx = buy();

        assertThat(policy.evaluate(new SwapCheck(
                                tx.base64(), bot.address(), main.address(), null, CAP, OperationMode.AUTO_FULL))
                        .refusal())
                .isEqualTo(PolicyRefusal.CONTEXT_MISSING);
        assertThat(evaluate(tx, withMinOut(tx.context(), null), OperationMode.AUTO_FULL)
                        .refusal())
                .isEqualTo(PolicyRefusal.CONTEXT_MISSING);
        assertThat(policy.evaluate(new SwapCheck(
                                "AAAA", bot.address(), main.address(), tx.context(), CAP, OperationMode.AUTO_FULL))
                        .refusal())
                .isEqualTo(PolicyRefusal.MALFORMED_TRANSACTION);
    }

    @Test
    void aSecondRouteOrNoRouteIsRefused() {
        SwapTx twice = buy();
        twice.extra.add(twice.instructions().stream()
                .filter(ix -> java.util.Arrays.equals(ix.program(), SwapTx.JUPITER))
                .findFirst()
                .orElseThrow());

        assertThat(refusal(twice)).isEqualTo(PolicyRefusal.INSTRUCTION_NOT_ALLOWED);
    }

    @Test
    void aBuyThatSendsTheTokenToSolOrASellThatBuysAnotherTokenIsRefused() {
        SwapTx sellToToken = sell();
        sellToToken.routeDestination = sellToToken.tokenAccount();

        assertThat(refusal(sellToToken)).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
    }

    private static SwapContext withMinOut(SwapContext context, Long minOut) {
        return new SwapContext(
                context.mode(),
                context.side(),
                context.inputLamports(),
                context.inputBaseUnits(),
                context.quotedOut(),
                minOut,
                context.maxSlippageBps(),
                context.feeLamports(),
                context.mint());
    }
}
