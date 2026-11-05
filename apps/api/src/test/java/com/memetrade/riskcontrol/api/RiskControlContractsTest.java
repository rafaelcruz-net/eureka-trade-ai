package com.memetrade.riskcontrol.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.riskcontrol.api.GateResult.Rejected;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 3 of U6 (C14 as changed by the Functional Design; BR1.1, BR1.2, BR3.1, BR3.3): the public types of the barrier
 * are immutable records with the defence of the input, and their shape is the one recorded in contract-summary.md.
 */
@Tag("u6")
class RiskControlContractsTest {

    @Test
    void theReasonsFollowTheFixedOrderOfC21() {
        assertThat(Arrays.stream(RejectionReason.values()).map(Enum::name))
                .containsExactly(
                        "ORDER_SIZE",
                        "TOKEN_EXPOSURE",
                        "TOTAL_EXPOSURE",
                        "SLIPPAGE",
                        "MIN_LIQUIDITY",
                        "STALE_DATA",
                        "INTERVAL",
                        "DUPLICATE",
                        "INSUFFICIENT_PAPER_BALANCE",
                        "DAILY_LOSS",
                        "DRAWDOWN");
        assertThat(PauseTrigger.values()).containsExactly(PauseTrigger.DAILY_LOSS, PauseTrigger.DRAWDOWN);
    }

    @Test
    void rejectedCarriesTheRejectionIdAndASetOfPauseTriggers() {
        assertThat(Arrays.stream(Rejected.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("rejectionId", "reasons", "limitsVersion", "pauseTriggers");
        assertThat(Rejected.class.getRecordComponents()[3].getType()).isEqualTo(Set.class);
        assertThat(GateResult.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(GateResult.Approved.class, Rejected.class);
        assertThat(Arrays.stream(CandidateOrder.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("botId", "decisionId", "side", "chain", "tokenMint", "requestedQuantity");
    }

    @Test
    void rejectedIsImmutableAndKeepsTheMainReasonFirst() {
        List<RejectionReason> reasons =
                new ArrayList<>(List.of(RejectionReason.ORDER_SIZE, RejectionReason.DAILY_LOSS));
        Set<PauseTrigger> triggers = new HashSet<>(Set.of(PauseTrigger.DRAWDOWN, PauseTrigger.DAILY_LOSS));

        Rejected rejected = new Rejected(UUID.randomUUID(), reasons, 1, triggers);
        reasons.clear();
        triggers.clear();

        assertThat(rejected.reasons()).containsExactly(RejectionReason.ORDER_SIZE, RejectionReason.DAILY_LOSS);
        assertThat(rejected.mainReason()).isEqualTo(RejectionReason.ORDER_SIZE);
        assertThat(rejected.pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS, PauseTrigger.DRAWDOWN);
        assertThatThrownBy(() -> rejected.reasons().add(RejectionReason.SLIPPAGE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rejected.pauseTriggers().add(PauseTrigger.DAILY_LOSS))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new Rejected(UUID.randomUUID(), List.of(RejectionReason.DUPLICATE), 1, Set.of()).pauseTriggers())
                .isEmpty();
    }

    @Test
    void rejectedRefusesReasonsOutOfOrderRepeatedEmptyOrDuplicateWithOthers() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Rejected(id, List.of(), 1, Set.of())).hasMessageContaining("at least one");
        assertThatThrownBy(() ->
                        new Rejected(id, List.of(RejectionReason.DAILY_LOSS, RejectionReason.ORDER_SIZE), 1, Set.of()))
                .hasMessageContaining("fixed order");
        assertThatThrownBy(() ->
                        new Rejected(id, List.of(RejectionReason.SLIPPAGE, RejectionReason.SLIPPAGE), 1, Set.of()))
                .hasMessageContaining("fixed order");
        assertThatThrownBy(() ->
                        new Rejected(id, List.of(RejectionReason.SLIPPAGE, RejectionReason.DUPLICATE), 1, Set.of()))
                .hasMessageContaining("DUPLICATE");
        assertThatThrownBy(() -> new Rejected(id, List.of(RejectionReason.SLIPPAGE), 0, Set.of()))
                .hasMessageContaining("limitsVersion");
        assertThatThrownBy(() -> new Rejected(null, List.of(RejectionReason.SLIPPAGE), 1, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCandidateOrderRefusesAMissingFieldOrAQuantityNotAboveZero() {
        UUID bot = UUID.randomUUID();
        UUID decision = UUID.randomUUID();
        assertThatCode(() ->
                        new CandidateOrder(bot, decision, OrderSide.BUY, "solana", "Mint1", new BigDecimal("0.000001")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CandidateOrder(bot, decision, OrderSide.BUY, "solana", "Mint1", BigDecimal.ZERO))
                .hasMessageContaining("BR1.2");
        assertThatThrownBy(() ->
                        new CandidateOrder(bot, decision, OrderSide.SELL, "solana", "Mint1", new BigDecimal("-1")))
                .hasMessageContaining("BR1.2");
        assertThatThrownBy(() -> new CandidateOrder(bot, decision, OrderSide.BUY, " ", "Mint1", BigDecimal.ONE))
                .hasMessageContaining("chain");
        assertThatThrownBy(() -> new CandidateOrder(bot, decision, OrderSide.BUY, "solana", "", BigDecimal.ONE))
                .hasMessageContaining("tokenMint");
        assertThatThrownBy(() -> new CandidateOrder(null, decision, OrderSide.BUY, "solana", "Mint1", BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CandidateOrder(bot, decision, null, "solana", "Mint1", BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theLimitsRefuseValuesOutOfTheirDomain() {
        assertThatCode(RiskControlContractsTest::baseLimits).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits(0, "100", "200", "500", "2", "0", 0, "50", "10"))
                .hasMessageContaining("version");
        assertThatThrownBy(() -> limits(1, "0", "200", "500", "2", "0", 0, "50", "10"))
                .hasMessageContaining("maxOrderSize");
        assertThatThrownBy(() -> limits(1, "100", "600", "500", "2", "0", 0, "50", "10"))
                .hasMessageContaining("maxTokenExposure must not exceed");
        assertThatThrownBy(() -> limits(1, "100", "200", "500", "100.0001", "0", 0, "50", "10"))
                .hasMessageContaining("maxSlippagePct");
        assertThatThrownBy(() -> limits(1, "100", "200", "500", "2", "-0.000001", 0, "50", "10"))
                .hasMessageContaining("minLiquidity");
        assertThatThrownBy(() -> limits(1, "100", "200", "500", "2", "0", -1, "50", "10"))
                .hasMessageContaining("minIntervalSeconds");
        assertThatThrownBy(() -> limits(1, "100", "200", "500", "2", "0", 0, "0", "10"))
                .hasMessageContaining("maxDailyLoss");
        assertThatThrownBy(() -> limits(1, "100", "200", "500", "2", "0", 0, "50", "0"))
                .hasMessageContaining("maxDrawdownPct");
        assertThatCode(() -> limits(1, "100", "200", "500", "0", "0", 0, "50", "100"))
                .doesNotThrowAnyException();
    }

    @Test
    void theSidesAreBuyAndSellOnly() {
        assertThat(EnumSet.allOf(OrderSide.class)).containsExactly(OrderSide.BUY, OrderSide.SELL);
    }

    static BotLimitsVersionRef baseLimits() {
        return limits(
                1, "100.000000", "200.000000", "500.000000", "2.0000", "10000.000000", 300, "50.000000", "10.0000");
    }

    private static BotLimitsVersionRef limits(
            long version,
            String order,
            String token,
            String total,
            String slippage,
            String liquidity,
            long interval,
            String loss,
            String drawdown) {
        return new BotLimitsVersionRef(
                version,
                new BigDecimal(order),
                new BigDecimal(token),
                new BigDecimal(total),
                new BigDecimal(slippage),
                new BigDecimal(liquidity),
                interval,
                new BigDecimal(loss),
                new BigDecimal(drawdown));
    }
}
