package com.memetrade.riskcontrol.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.pnl.application.AttributedCostReader;
import com.memetrade.pnl.application.PnlRealMetrics;
import com.memetrade.pnl.infra.PnlRealProperties;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.shared.jdbc.ReadDeadline;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; BR2.7, F-3, NFR1.20, NFR3.1, NFR3.11, NFR7.3,
 * reliability-design.md "Atribuição falhando na barreira e no ciclo"): the read of cost with attribution fails with an
 * error, a {@code TIMEOUT} by {@code 57014} ({@code pg_sleep} past 250 ms), a {@code TIMEOUT} without {@code 57014}
 * (the clock passes the limit between two blocks of 500 rows), {@code UNKNOWN_BOT} and {@code INCONSISTENT_READ}; in
 * the transaction of a round (and of a cycle, with its 5 s) the same transaction keeps writing, the next statement
 * gets no {@code 57014}, and {@code statement_timeout} and {@code work_mem} are back to what they were. A buy with a
 * position without price is refused without any read of attribution; a sale and a liquidation are approved.
 */
@Tag("u2-real-bot-pnl")
class AttributionFailureGateIT extends AbstractRealExecutionIT {

    @Autowired
    private RealExecutionPort orders;

    @Autowired
    private BotDirectory bots;

    @Autowired
    private RealCostMethod method;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PnlRealMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private RealRiskGate gate;

    private AttributedCostReader reader(RealLedger readLedger, RealExecutionPort readOrders, BotDirectory readBots) {
        return new AttributedCostReader(
                readLedger,
                readOrders,
                readBots,
                method,
                jdbcTemplate,
                transactionManager,
                clock,
                metrics,
                PnlRealProperties.defaults());
    }

    /** A confirmed buy of a bot of the person, with its entries in the ledger. */
    private void confirmedBuy(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        chain.effect(txSignature(order), new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(order));
        confirmation.runOnce();
    }

    /** The answer of the read in the transaction of a round (5 s), with what remains of the transaction afterwards. */
    private record Round(WalletCost cost, String timeout, String workMem, String next) {}

    private Round inARound(Setup setup, AttributedCostReader read, long expectedRowsDelta) {
        String signature = SyntheticIds.signature();
        Round round = transactions.execute(status -> {
            jdbcTemplate.execute("set local statement_timeout = '5s'");
            jdbcTemplate.execute("set local work_mem = '4MB'");
            long rows = ledger.view(setup.tenantId(), setup.botWalletId(), Optional.empty())
                    .walletRowCount();
            WalletCost cost = read.costOf(
                    setup.tenantId(), setup.userId(), setup.botWalletId(), CostPath.GATE, rows + expectedRowsDelta);
            String timeout = jdbcTemplate.queryForObject("show statement_timeout", String.class);
            String workMem = jdbcTemplate.queryForObject("show work_mem", String.class);
            jdbcTemplate.update(
                    "insert into real_entry_slot (tx_signature, slot, recorded_at) values (?, 1, now())", signature);
            String next = jdbcTemplate.queryForObject("select 'usable'", String.class);
            return new Round(cost, timeout, workMem, next);
        });
        assertThat(jdbc.sql("select count(*) from real_entry_slot where tx_signature = :s")
                        .param("s", signature)
                        .query(Long.class)
                        .single())
                .as("the transaction of the round committed what it wrote after the failure")
                .isEqualTo(1L);
        return round;
    }

    private static void usableAfterwards(Round round, RealCostUnknownReason reason) {
        assertThat(round.cost()).isEqualTo(new WalletCost.Unknown(reason));
        assertThat(round.timeout()).isEqualTo("5s");
        assertThat(round.workMem()).isEqualTo("4MB");
        assertThat(round.next()).isEqualTo("usable");
    }

    @Test
    void anErrorOfTheReadLeavesTheTransactionUsable() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);
        BotDirectory failing = failing(BotDirectory.class);

        usableAfterwards(inARound(setup, reader(ledger, orders, failing), 0), RealCostUnknownReason.ERROR);
    }

    @Test
    void aTimeoutOfTheDatabaseIsATimeoutAndLeavesTheTransactionUsable() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);
        RealExecutionPort sleeping = delegate(RealExecutionPort.class, orders, (method, args) -> {
            if (method.equals("streamSettledOrders") && args.length == 4) {
                jdbcTemplate.queryForObject("select pg_sleep(0.4)::text", String.class);
            }
        });

        usableAfterwards(inARound(setup, reader(ledger, sleeping, bots), 0), RealCostUnknownReason.TIMEOUT);
    }

    @Test
    void theDeadlineBetweenTwoBlocksIsATimeoutWithoutA57014() {
        Setup setup = realBot("AUTO_FULL");
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                          reference, idempotency_key, created_at)
                        select gen_random_uuid(), :tenant, :wallet, 'DEPOSIT', 1, 'dep:many-' || n,
                          :wallet || ':many:' || n, now()
                        from generate_series(1, 600) n""")
                .param("tenant", setup.tenantId())
                .param("wallet", setup.botWalletId())
                .update();
        AtomicInteger delivered = new AtomicInteger();
        RealLedger slowJava = new LedgerWithSlowConsumer(ledger, () -> {
            if (delivered.incrementAndGet() == 501) {
                clock.advance(Duration.ofMillis(300));
            }
        });

        usableAfterwards(inARound(setup, reader(slowJava, orders, bots), 0), RealCostUnknownReason.TIMEOUT);
    }

    @Test
    void aBotOutsideTheRealBotsOfThePersonIsUnknownBot() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);
        BotDirectory empty = new BotDirectory() {
            @Override
            public List<BotEntry> botsOf(UUID tenantId, UUID userId, PnlMarket market) {
                return List.of();
            }

            @Override
            public Optional<BotEntry> botOf(UUID tenantId, UUID userId, UUID botId) {
                return Optional.empty();
            }
        };

        usableAfterwards(inARound(setup, reader(ledger, orders, empty), 0), RealCostUnknownReason.UNKNOWN_BOT);
    }

    @Test
    void anotherCountOfRowsThanTheViewIsAnInconsistentRead() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);

        usableAfterwards(inARound(setup, reader(ledger, orders, bots), 1), RealCostUnknownReason.INCONSISTENT_READ);
    }

    @Test
    void theReadKnowsTheCostOfTheBuyOfTheBotWithItsRent() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);

        Round round = inARound(setup, reader(ledger, orders, bots), 0);

        assertThat(round.cost()).isEqualTo(new WalletCost.Known(java.util.Map.of(setup.mint(), 52_047_737L - 8_457L)));
        assertThat(round.timeout()).isEqualTo("5s");
        assertThat(round.workMem()).isEqualTo("4MB");
    }

    @Test
    void theBuyIsRefusedWithoutAnyReadOfAttributionAndTheSaleAndTheLiquidationGoOn() {
        Setup setup = realBot("AUTO_FULL");
        String unpriced = SyntheticIds.mint();
        position(wallet(setup), unpriced, 1_000, SOL);
        double readsBefore = gateReads();

        GateDecision buy = transactions.execute(status -> gate.evaluate(OrderIntent.botBuy(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 50_000_000L)));
        GateDecision sale = transactions.execute(status -> gate.evaluate(OrderIntent.botSell(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), unpriced, 1_000)));
        GateDecision liquidation = transactions.execute(status ->
                gate.evaluate(OrderIntent.userLiquidation(setup.tenantId(), setup.userId(), unpriced, 1_000)));

        assertThat(buy).isInstanceOfSatisfying(GateDecision.Rejected.class, rejected -> assertThat(rejected.detail())
                .isEqualTo(RealRejectionDetail.POSITION_PRICE_UNKNOWN));
        assertThat(sale.approved()).isTrue();
        assertThat(liquidation.approved()).isTrue();
        assertThat(gateReads()).as("no read of attribution").isEqualTo(readsBefore);
    }

    private double gateReads() {
        return registry.find("pnl.real.attribution.read").tag("path", "GATE").timers().stream()
                .mapToDouble(timer -> timer.count())
                .sum();
    }

    // ----- doubles of the ports
    // ---------------------------------------------------------------------------------------

    private interface Before {
        void call(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T delegate(Class<T> type, T target, Before before) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object[] arguments = args == null ? new Object[0] : args;
            before.call(method.getName(), arguments);
            try {
                return method.invoke(target, arguments);
            } catch (java.lang.reflect.InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T failing(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new IllegalStateException("the directory is down");
        });
    }

    /** The real ledger, with a consumer that spends time in Java after each row (the clock of the test moves). */
    private record LedgerWithSlowConsumer(RealLedger real, Runnable afterRow) implements RealLedger {

        @Override
        public void streamInNetworkOrder(
                UUID tenantId,
                java.util.Collection<UUID> botWalletIds,
                java.util.Set<RealEntryKind> kinds,
                ReadDeadline deadline,
                Consumer<NetworkOrderedEntry> consumer) {
            real.streamInNetworkOrder(tenantId, botWalletIds, kinds, deadline, entry -> {
                consumer.accept(entry);
                afterRow.run();
            });
        }

        @Override
        public void streamInNetworkOrder(
                UUID tenantId,
                java.util.Collection<UUID> botWalletIds,
                java.util.Set<RealEntryKind> kinds,
                Consumer<NetworkOrderedEntry> consumer) {
            real.streamInNetworkOrder(tenantId, botWalletIds, kinds, consumer);
        }

        @Override
        public boolean record(com.memetrade.ledger.api.RealEntry entry) {
            return real.record(entry);
        }

        @Override
        public com.memetrade.ledger.api.RecordSlotResult recordSlot(
                UUID tenantId, String txSignature, long slot, com.memetrade.ledger.api.SlotWriter writer) {
            return real.recordSlot(tenantId, txSignature, slot, writer);
        }

        @Override
        public void markEpochs(UUID tenantId, UUID botWalletId) {
            real.markEpochs(tenantId, botWalletId);
        }

        @Override
        public com.memetrade.ledger.api.RealBalance balance(UUID tenantId, UUID botWalletId) {
            return real.balance(tenantId, botWalletId);
        }

        @Override
        public List<com.memetrade.ledger.api.RealPosition> positions(UUID tenantId, UUID botWalletId) {
            return real.positions(tenantId, botWalletId);
        }

        @Override
        public com.memetrade.ledger.api.RealFlows flowsSince(UUID tenantId, UUID botWalletId, java.time.Instant since) {
            return real.flowsSince(tenantId, botWalletId, since);
        }

        @Override
        public com.memetrade.ledger.api.RealLedgerView view(
                UUID tenantId, UUID botWalletId, Optional<java.time.Instant> flowsSince) {
            return real.view(tenantId, botWalletId, flowsSince);
        }

        @Override
        public java.util.Map<String, Long> tokenBalances(UUID tenantId, UUID botWalletId) {
            return real.tokenBalances(tenantId, botWalletId);
        }

        @Override
        public void streamEntries(
                UUID tenantId,
                java.util.Collection<UUID> botWalletIds,
                java.time.Instant from,
                java.time.Instant to,
                Consumer<com.memetrade.ledger.api.RealEntryRecord> consumer) {
            real.streamEntries(tenantId, botWalletIds, from, to, consumer);
        }
    }
}
