package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.OpenSellsFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 8 (after the layer; NFR1.11, NFR1.12; performance-design.md "Conferência do plano"):
 * on a queue and an order book of realistic size (2 tenants, 20,000 closed decisions, 6 PENDING ones of the measured
 * bot, 14,600 final orders and one SELL order in progress), {@code EXPLAIN (FORMAT JSON)} of the exact statement of
 * {@code openSells} uses {@code real_decision_request_bot_pending_sell_idx} and {@code uq_real_order_bot_in_progress},
 * and never a sequential scan of {@code real_decision_request} or {@code real_order}. If the planner chose another path,
 * the index is adjusted, never this test.
 */
@Tag("u4-own-quantity-cycle")
class RealOpenSellsPlanIT extends AbstractRealExecutionIT {

    @Test
    void theOpenSalesOfABotAreReadByTheTwoPartialIndexes() throws Exception {
        Setup measured = realBot("AUTO_FULL");
        Setup other = realBot("AUTO_FULL");
        OpenSellsFixture fixture = new OpenSellsFixture(jdbc);
        closedDecisions(measured, 10_000);
        closedDecisions(other, 10_000);
        finalOrders(measured, 7_300);
        finalOrders(other, 7_300);
        for (int i = 0; i < 3; i++) {
            fixture.decision(
                    measured.tenantId(),
                    measured.userId(),
                    measured.botId(),
                    "BUY",
                    measured.mint(),
                    5,
                    "PENDING",
                    Instant.now());
        }
        fixture.pendingSell(measured.tenantId(), measured.userId(), measured.botId(), measured.mint(), 10);
        fixture.pendingSell(measured.tenantId(), measured.userId(), measured.botId(), measured.mint(), 20);
        fixture.decision(
                measured.tenantId(),
                measured.userId(),
                measured.botId(),
                "SELL",
                measured.mint(),
                30,
                "PENDING",
                Instant.now().plus(Duration.ofMinutes(10)));
        fixture.order(measured.tenantId(), measured.userId(), measured.botId(), "SELL", measured.mint(), 40, "SENT");
        // ANALYZE needs the owner of the tables; the application login would be skipped with a warning.
        connect(SUPERUSER).sql("analyze real_decision_request").update();
        connect(SUPERUSER).sql("analyze real_order").update();

        String sql = JdbcRealExecutionStore.OPEN_SELLS_SQL
                .replace(":bot", "'" + measured.botId() + "'::uuid")
                .replace(":tenant", "'" + measured.tenantId() + "'::uuid");
        String plan =
                jdbc.sql("explain (format json) " + sql).query(String.class).single();
        List<JsonNode> nodes = new ArrayList<>();
        collect(new ObjectMapper().readTree(plan), nodes);

        assertThat(nodes).anySatisfy(node -> assertThat(node.path("Index Name").asText())
                .isEqualTo("real_decision_request_bot_pending_sell_idx"));
        assertThat(nodes).anySatisfy(node -> assertThat(node.path("Index Name").asText())
                .isEqualTo("uq_real_order_bot_in_progress"));
        assertThat(nodes).noneSatisfy(node -> {
            assertThat(node.path("Node Type").asText()).isEqualTo("Seq Scan");
            assertThat(node.path("Relation Name").asText()).isIn("real_decision_request", "real_order");
        });
        List<com.memetrade.realexecution.api.OpenSell> open =
                transactions.execute(status -> port.openSells(measured.tenantId(), measured.botId()));
        assertThat(open)
                .extracting(com.memetrade.realexecution.api.OpenSell::baseUnits)
                .containsExactlyInAnyOrder(10L, 20L, 30L, 40L);
    }

    private void closedDecisions(Setup setup, int count) {
        jdbc.sql("""
                        insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id,
                          side, mint, token_base_units, reason, state, discard_reason, created_at, processed_at)
                        select gen_random_uuid(), :t, :u, :b, gen_random_uuid(), 'SELL', :mint, 5, 'plano',
                          case when g % 2 = 0 then 'PROCESSED' else 'DISCARDED' end,
                          case when g % 2 = 0 then null else 'STATE_CHANGED' end, now(), now()
                        from generate_series(1, :n) g""")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("b", setup.botId())
                .param("mint", setup.mint())
                .param("n", count)
                .update();
    }

    private void finalOrders(Setup setup, int count) {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key, side,
                          mint, input_base_units, operation_mode, signing_mode, max_slippage_bps, reason, state,
                          failure_code, tx_signature, created_at, updated_at)
                        select gen_random_uuid(), :t, :u, :b, 'BOT', 'plan-' || :salt || '-' || g, 'SELL', :mint, 5,
                          'AUTO_FULL', 'AUTO_FULL', 100, 'plano',
                          case when g % 3 = 0 then 'FAILED' else 'CONFIRMED' end,
                          case when g % 3 = 0 then 'CHAIN_REJECTED' else null end,
                          'plansig' || :salt || g, now(), now()
                        from generate_series(1, :n) g""")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("b", setup.botId())
                .param("salt", salt)
                .param("mint", setup.mint())
                .param("n", count)
                .update();
    }

    private static void collect(JsonNode node, List<JsonNode> nodes) {
        if (node.isObject() && node.has("Node Type")) {
            nodes.add(node);
        }
        node.elements().forEachRemaining(child -> collect(child, nodes));
    }
}
