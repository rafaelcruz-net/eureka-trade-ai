package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 7, WRITTEN BEFORE THE CODE (BR1.8, BR1.9; reliability-design.md "Editar limites de
 * um bot REAL"): a REAL bot has no limits of its own. Over HTTP, {@code changeBotLimits} on a REAL bot answers 422 on
 * the field {@code limits} and writes no version, and a REAL draft refuses a virtual balance or limits in step 2; the
 * same calls on a PAPER bot keep today's behaviour.
 */
class BotLimitsRealRefusedIT extends AbstractPaperRealIT {

    private static final String LIMITS = """
            {"maxOrderSize":"100.000000","maxTokenExposure":"200.000000","maxTotalExposure":"500.000000",
            "maxDailyLoss":"50.000000","maxDrawdown":"10.0000","maxSlippage":"2.0000","minLiquidity":"10000.000000",
            "minIntervalSeconds":240}""";

    @Test
    void changingTheLimitsOfARealBotIs422OnLimitsAndWritesNoVersion() throws Exception {
        Person person = eligiblePerson();
        String botId =
                body(create(person, "REAL", UUID.randomUUID())).get("botId").asText();

        MockHttpServletResponse refused =
                call(person, post("/api/v1/bots/" + botId + "/limits").content(LIMITS));

        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        JsonNode problem = body(refused);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("fields").get(0).get("field").asText()).isEqualTo("limits");
        assertThat(versions(botId)).isZero();
    }

    @Test
    void aRealDraftRefusesAVirtualBalanceAndLimitsInStepTwoButSavesTheStepWithoutThem() throws Exception {
        Person person = eligiblePerson();
        String botId =
                body(create(person, "REAL", UUID.randomUUID())).get("botId").asText();

        MockHttpServletResponse withBalance = call(
                person,
                put("/api/v1/bots/" + botId + "/draft")
                        .content("{\"step\":2,\"virtualBalance\":\"1000.000000\",\"limits\":" + LIMITS + "}"));
        MockHttpServletResponse withoutBalance =
                call(person, put("/api/v1/bots/" + botId + "/draft").content("{\"step\":2}"));

        assertThat(withBalance.getStatus()).isEqualTo(422);
        assertThat(withBalance.getContentAsString()).contains("virtualBalance");
        assertThat(withoutBalance.getStatus())
                .as(withoutBalance.getContentAsString())
                .isEqualTo(200);
        assertThat(body(withoutBalance).get("draftStep").asInt()).isEqualTo(2);
    }

    @Test
    void aPaperDraftStillNeedsItsVirtualBalanceAndLimits() throws Exception {
        Person person = signIn(false, false);
        String botId =
                body(create(person, "PAPER", UUID.randomUUID())).get("botId").asText();

        MockHttpServletResponse missing =
                call(person, put("/api/v1/bots/" + botId + "/draft").content("{\"step\":2}"));

        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(missing.getContentAsString()).contains("limits");
    }

    private long versions(String botId) {
        return jdbc.sql("select count(*) from bot_limits_version where bot_id = :b")
                .param("b", UUID.fromString(botId))
                .query(Long.class)
                .single();
    }
}
