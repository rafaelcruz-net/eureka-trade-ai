package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 17 of the U4, written after the code (U4-NFR3.1 to U4-NFR3.5, performance-design.md): an INFORMATIVE measurement,
 * with no gate (team.md "Desempenho": no performance gate until the requirements set numeric targets that gate). 21
 * people with a REAL bot in Automática total produce 4 decisions per second; the jobs run in turn as the worker runs
 * them, against PostgreSQL in a container, the fake router and the fake chain (no network). The test logs the p95 of each
 * stage (record of the decision, queue, driver, confirmation) and from the decision to the send, measured on the wall
 * clock of the test; it asserts only that every order reached CONFIRMED and that no order got a second transaction.
 */
@Tag("u4-real-execution")
class RealExecutionMeasureIT extends AbstractRealExecutionIT {

    private static final Logger log = LoggerFactory.getLogger(RealExecutionMeasureIT.class);

    private static final int PEOPLE = 21;
    private static final int DECISIONS = 2 * PEOPLE;
    private static final long TICK_MILLIS = 250;

    private static long p95(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compare);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(index, 0)) / 1_000_000L;
    }

    @Test
    void twentyOnePeopleAtFourDecisionsPerSecondReachConfirmedAndTheP95IsLogged() throws InterruptedException {
        List<Setup> people = new ArrayList<>();
        for (int i = 0; i < PEOPLE; i++) {
            people.add(realBot("AUTO_FULL"));
        }
        Map<String, List<Long>> stages = new HashMap<>();
        Map<UUID, Long> decidedAt = new HashMap<>();
        List<Long> decisionToSend = new ArrayList<>();
        List<UUID> orders = new ArrayList<>();
        int decided = 0;

        for (int tick = 0; decided < DECISIONS || !allFinal(orders); tick++) {
            long started = System.nanoTime();
            if (decided < DECISIONS) {
                Setup person = people.get(decided % PEOPLE);
                long before = System.nanoTime();
                UUID request = pendingBuy(person, 50_000_000L);
                stages.computeIfAbsent("record", key -> new ArrayList<>()).add(System.nanoTime() - before);
                decidedAt.put(request, System.nanoTime());
                decided++;
            }
            timed(stages, "queue", consumer::runOnce);
            for (UUID request : List.copyOf(decidedAt.keySet())) {
                UUID order = orderFor(request);
                if (order != null && !orders.contains(order)) {
                    orders.add(order);
                }
            }
            timed(stages, "driver", driver::runOnce);
            for (UUID request : List.copyOf(decidedAt.keySet())) {
                UUID order = orderFor(request);
                String signature = order == null ? null : sentSignature(order);
                if (signature != null) {
                    decisionToSend.add(System.nanoTime() - decidedAt.remove(request));
                    chain.effect(signature, new RealChain.SwapEffect(-50_008_457L, 1_000_000L, 8_457L, true));
                    chain.finalize(signature);
                }
            }
            timed(stages, "confirmation", confirmation::runOnce);
            assertThat(tick)
                    .as(() -> "the measurement did not finish: orders " + orders.size() + " "
                            + jdbc.sql("select state || ':' || coalesce(failure_code, '-') || ':' || count(*) from"
                                            + " real_order where real_order_id in (:ids) group by state, failure_code")
                                    .param("ids", orders.isEmpty() ? List.of(UUID.randomUUID()) : orders)
                                    .query(String.class)
                                    .list()
                            + " decisions " + decidedAt.size() + " "
                            + jdbc.sql("select state || ':' || coalesce(discard_reason, '-') || ':' || count(*) from"
                                            + " real_decision_request where decision_request_id in (:ids)"
                                            + " group by state, discard_reason")
                                    .param("ids", decidedAt.isEmpty() ? List.of(UUID.randomUUID()) : decidedAt.keySet())
                                    .query(String.class)
                                    .list())
                    .isLessThan(DECISIONS * 20);
            long rest = TICK_MILLIS - (System.nanoTime() - started) / 1_000_000L;
            if (rest > 0) {
                Thread.sleep(rest);
            }
        }

        log.info(
                "U4 informative measurement (21 people, 4 decisions/s, {} orders): p95 record={} ms, queue={} ms,"
                        + " driver={} ms, confirmation={} ms, decision to send={} ms",
                orders.size(),
                p95(stages.get("record")),
                p95(stages.get("queue")),
                p95(stages.get("driver")),
                p95(stages.get("confirmation")),
                p95(decisionToSend));
        assertThat(orders).hasSize(DECISIONS);
        assertThat(orders).allSatisfy(order -> assertThat(state(order)).isEqualTo("CONFIRMED"));
        assertThat(jdbc.sql("select count(*) from real_order_attempt where real_order_id in (:ids)")
                        .param("ids", orders)
                        .query(Long.class)
                        .single())
                .as("one transaction per order")
                .isEqualTo(DECISIONS);
    }

    /** The order created for a decision, or null while the queue has not consumed it. */
    private UUID orderFor(UUID decisionRequestId) {
        return jdbc.sql("select real_order_id from real_order where decision_request_id = :r")
                .param("r", decisionRequestId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /** The signature of a SENT order, or null before the send. */
    private String sentSignature(UUID orderId) {
        return jdbc.sql("select tx_signature from real_order where real_order_id = :o and state = 'SENT'")
                .param("o", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private boolean allFinal(List<UUID> orders) {
        return orders.size() == DECISIONS
                && orders.stream().allMatch(order -> "CONFIRMED".equals(state(order)) || "FAILED".equals(state(order)));
    }

    private static void timed(Map<String, List<Long>> stages, String stage, Runnable job) {
        long before = System.nanoTime();
        job.run();
        stages.computeIfAbsent(stage, key -> new ArrayList<>()).add(System.nanoTime() - before);
    }
}
