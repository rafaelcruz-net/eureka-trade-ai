package com.memetrade.txsigner.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.memetrade.txsigner.testsupport.AbstractTxSignerIT;
import com.memetrade.txsigner.testsupport.ConsentText;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Mandatory test of team.md "REAL desligado por padrão" for the TxSigner: with the shipped configuration no bot wallet
 * is created, no swap is signed and nothing is asked to the KMS.
 */
class RealOffByDefaultIT extends AbstractTxSignerIT {

    @Test
    void withTheDefaultConfigurationNoWalletIsCreatedAndTheKmsIsNeverCalled() throws Exception {
        TestWallet main = TestWallet.generate();
        String message = ConsentText.activation(main.address()).text();

        mvc.perform(post("/internal/v1/wallets")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"mainWalletAddress\":\""
                                + main.address() + "\",\"consentMessage\":" + quoted(message)
                                + ",\"consentSignature\":\"" + main.sign(message) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAL_DISABLED"));

        assertThat(kms.createCalls()).isZero();
        assertThat(kms.signCalls()).isZero();
    }

    /** U4 (U4-NFR8.2): with the shipped configuration a SWAP is refused with 403 REAL_DISABLED and the KMS never signs. */
    @Test
    void withTheDefaultConfigurationNoSwapIsSigned() throws Exception {
        com.memetrade.txsigner.testsupport.SwapTx swap = new com.memetrade.txsigner.testsupport.SwapTx(
                TestWallet.generate().publicKey(), TestWallet.generate());
        var context = swap.context();

        mvc.perform(post("/internal/v1/signatures")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signingRequestId\":\"" + UUID.randomUUID() + "\",\"botWalletAddress\":\""
                                + com.memetrade.txsigner.codec.Base58.encode(swap.bot)
                                + "\",\"operationKind\":\"SWAP\",\"transactionBase64\":\"" + swap.base64()
                                + "\",\"context\":{\"mode\":\"AUTO_FULL\",\"side\":\"BUY\",\"inputLamports\":\""
                                + context.inputLamports() + "\",\"quotedOut\":\"" + context.quotedOut()
                                + "\",\"minOut\":\"" + context.minOut() + "\",\"maxSlippageBps\":"
                                + context.maxSlippageBps() + ",\"feeLamports\":\"" + context.feeLamports()
                                + "\",\"mint\":\"" + context.mint() + "\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAL_DISABLED"));

        assertThat(kms.signCalls()).isZero();
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\n", "\\n") + "\"";
    }
}
