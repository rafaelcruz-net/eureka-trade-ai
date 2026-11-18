package com.memetrade.txsigner.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.memetrade.txsigner.domain.WalletRequestLimiter;
import com.memetrade.txsigner.testsupport.AbstractTxSignerIT;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 31 of the U1, written BEFORE the code (NFR1.9 second half): through {@code /internal/v1/signatures} the 61st
 * request in a minute for one bot wallet answers {@code 429 WALLET_RATE_LIMITED} (RFC 7807, with the reason and the
 * correlation id, never the transaction), and another wallet keeps its own count. The requests are trigger operations,
 * refused before any KMS call, so the test needs no wallet in the store.
 */
@TestPropertySource(properties = "txsigner.real.enabled=true")
class WalletRateLimitIT extends AbstractTxSignerIT {

    private static String body(String wallet) {
        return """
                {"signingRequestId":"%s","botWalletAddress":"%s","operationKind":"TRIGGER_CANCEL","transactionBase64":"%s"}""".formatted(UUID.randomUUID(), wallet, Base64.getEncoder().encodeToString(new byte[200]));
    }

    private org.springframework.test.web.servlet.ResultActions sign(String wallet) throws Exception {
        return mvc.perform(post("/internal/v1/signatures")
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(wallet)));
    }

    @Test
    void theSixtyFirstRequestOfAWalletInAMinuteIsRefusedWith429AndAnotherWalletIsNotAffected() throws Exception {
        String wallet = TestWallet.generate().address();
        for (int i = 0; i < WalletRequestLimiter.REQUESTS_PER_MINUTE; i++) {
            sign(wallet).andExpect(status().isUnprocessableEntity());
        }

        sign(wallet)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WALLET_RATE_LIMITED"))
                .andExpect(jsonPath("$.reason").value("WALLET_RATE_LIMITED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.signedTransactionBase64").doesNotExist());

        sign(TestWallet.generate().address())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("POLICY_NOT_RECORDED"));
    }
}
