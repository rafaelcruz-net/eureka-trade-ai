package com.memetrade.bots.worker;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.U5Bots;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U4, Etapa 5, T14 (written before the code; BR4.4, BR1.6, NFR4.1, NFR4.2): the PAPER branch of
 * the cycle never reads the flag of the U4 nor the own holdings. Two identical PAPER bots decide on the same data, one
 * with the flag on and one with it off: the recorded request ({@code DecisionJson.write(request, outcome)}: parameters,
 * inputs, context and factors) is the same, field by field; 0 reads of the own holdings and 0 reads of the flag.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityPaperUnchangedIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T12:05:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private OwnHoldingsStep productionStep;

    @Autowired
    private OwnQuantitySell productionFlag;

    @Autowired
    private OwnQuantityMetrics productionMetrics;

    @Autowired
    private com.memetrade.bots.infra.JdbcBotRepository repository;

    @AfterEach
    void restore() {
        cycles.useOwnQuantity(productionStep, productionFlag, productionMetrics);
    }

    @Test
    void thePaperRequestIsTheSameWithTheFlagOnAndOffAndReadsNothingOfTheU4() {
        TokenId token = newToken();
        Principal first = newPerson();
        Principal second = newPerson();
        UUID on = fixtures.active(first, token, U5Bots.wideLimits());
        UUID off = fixtures.active(second, token, U5Bots.wideLimits());
        clock.set(SLOT.plusSeconds(60));
        buySignal(market, assessments, token, SLOT);

        Map<String, String> withFlag = run(first, on, true);
        Map<String, String> withoutFlag = run(second, off, false);

        assertThat(withFlag).isEqualTo(withoutFlag);
        assertThat(withFlag.get("context")).doesNotContain("ownQuantity");
    }

    private Map<String, String> run(Principal person, UUID botId, boolean flagOn) {
        OwnQuantityCycle.TestFlag flag = new OwnQuantityCycle.TestFlag(flagOn);
        RealBotHoldings never = (tenant, user, bot, budget) -> {
            throw new AssertionError("the PAPER branch never reads the own holdings");
        };
        OwnQuantityCycle.CountingHoldings holdings = new OwnQuantityCycle.CountingHoldings(never);
        OwnHoldingsStreaks streaks = new OwnHoldingsStreaks(clock);
        OwnQuantityMetrics metrics = new OwnQuantityMetrics(new SimpleMeterRegistry(), streaks);
        OwnHoldingsStep step = new OwnHoldingsStep(repository, holdings, flag, metrics, streaks, clock);
        cycles.useOwnQuantity(step, flag, metrics);

        CycleResult result = cycles.runDispatched(
                new DispatchedBot(new BotRef(botId, TenantScope.of(person)), com.memetrade.bots.domain.BotMarket.PAPER),
                SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(holdings.calls.get()).isZero();
        assertThat(flag.reads.get()).isZero();
        List<Map<String, Object>> rows = jdbc.sql("""
                        select parameters::text as parameters, inputs::text as inputs, context::text as context,
                          factors::text as factors from decision where bot_id = :b""").param("b", botId).query().listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, String> recorded = new java.util.TreeMap<>();
        rows.getFirst().forEach((key, value) -> recorded.put(key, String.valueOf(value)));
        return recorded;
    }
}
