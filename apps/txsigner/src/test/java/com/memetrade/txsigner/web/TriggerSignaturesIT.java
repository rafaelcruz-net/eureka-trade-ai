package com.memetrade.txsigner.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.memetrade.txsigner.policy.TriggerProgram;
import com.memetrade.txsigner.testsupport.AbstractTxSignerIT;
import com.memetrade.txsigner.testsupport.TestWallet;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 11 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR0.3, S44, U7-NFR1.2, U7-NFR5.4): through
 * {@code /internal/v1/signatures}, without the recording ({@code trigger-program.json} absent, as in this build) the
 * TxSigner starts and refuses {@code TRIGGER_CREATE} and {@code TRIGGER_CANCEL} with {@code SIGNING_POLICY_REFUSED}
 * and the reason {@code POLICY_NOT_RECORDED}; each refusal counts {@code txsigner_trigger_refusals_total{reason}}.
 */
@TestPropertySource(properties = "txsigner.real.enabled=true")
class TriggerSignaturesIT extends AbstractTxSignerIT {

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TriggerProgram program;

    private String body(String kind) {
        return """
                {"signingRequestId":"%s","botWalletAddress":"%s","operationKind":"%s","transactionBase64":"%s"}""".formatted(
                        UUID.randomUUID(),
                        TestWallet.generate().address(),
                        kind,
                        Base64.getEncoder().encodeToString(new byte[200]));
    }

    @Test
    void withoutTheRecordingTheTxSignerStartsWithoutTheTriggerPolicy() {
        assertThat(program.recorded()).isFalse();
    }

    @Test
    void s44BothTriggerOperationsAreRefusedWithPolicyNotRecordedAndCounted() throws Exception {
        double before = meters.counter("txsigner_trigger_refusals", "reason", "POLICY_NOT_RECORDED")
                .count();

        for (String kind : new String[] {"TRIGGER_CREATE", "TRIGGER_CANCEL"}) {
            mvc.perform(post("/internal/v1/signatures")
                            .header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(kind)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("SIGNING_POLICY_REFUSED"))
                    .andExpect(jsonPath("$.reason").value("POLICY_NOT_RECORDED"));
        }

        assertThat(meters.counter("txsigner_trigger_refusals", "reason", "POLICY_NOT_RECORDED")
                        .count())
                .isEqualTo(before + 2);
    }
}
