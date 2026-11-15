package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Mandatory test of team.md "REAL desligado por padrão" for the api: with the shipped configuration the routes that
 * open risk answer 403 REAL_DISABLED, no bot wallet is created and nothing reaches the TxSigner; reading and the
 * withdrawal route are not blocked by the flag.
 */
class RealOffByDefaultRoutesIT extends AbstractRealIT {

    @Test
    void theRoutesThatOpenRiskAnswerRealDisabledAndNoWalletIsCreated() throws Exception {
        Person person = signIn(true, true);
        int walletsBefore = signer.walletsCreated();

        MockHttpServletResponse challenge = call(
                person,
                post("/api/v1/real/consents/challenge").content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"1\"}"));
        MockHttpServletResponse activation = call(
                person,
                post("/api/v1/real/bot-wallet")
                        .content("{\"challengeId\":\"0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c001\",\"signature\":\""
                                + "1".repeat(64) + "\"}"));
        MockHttpServletResponse read = call(person, get("/api/v1/real/bot-wallet"));
        MockHttpServletResponse withdrawal =
                call(person, post("/api/v1/real/bot-wallet/withdrawals").content("{\"amount\":{\"all\":true}}"));

        assertThat(body(challenge).get("code").asText()).isEqualTo("REAL_DISABLED");
        assertThat(body(activation).get("code").asText()).isEqualTo("REAL_DISABLED");
        assertThat(read.getStatus()).isEqualTo(404);
        assertThat(body(withdrawal).get("code").asText()).isEqualTo("BOT_WALLET_NOT_ACTIVE");
        assertThat(signer.walletsCreated()).isEqualTo(walletsBefore);
        assertThat(jdbc.sql("select count(*) from bot_wallet_account where user_id = :u")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
