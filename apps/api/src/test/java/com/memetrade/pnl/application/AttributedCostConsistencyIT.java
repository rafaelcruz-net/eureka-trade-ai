package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; performance-design.md "Consistência com o view",
 * review R-08): the read of cost proves it saw the rows of one snapshot or fails closed. A row of the wallet inserted by
 * another connection between the view and the read gives {@code INCONSISTENT_READ}; an order confirmed between the read
 * of the orders and the read of the rows is attributed to the right bot by the read on the spot.
 */
@Tag("u2-real-bot-pnl")
class AttributedCostConsistencyIT extends AbstractRealExecutionIT {

    @Autowired
    private RealWalletCost walletCost;

    @Autowired
    private RealExecutionPort orders;

    @Autowired
    private com.memetrade.pnl.api.BotDirectory bots;

    @Autowired
    private com.memetrade.ledger.api.RealCostMethod method;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private PnlRealMetrics metrics;

    @Test
    void aRowInsertedByAnotherConnectionBetweenTheViewAndTheReadIsAnInconsistentRead() {
        Setup setup = realBot("AUTO_FULL");

        WalletCost cost = transactions.execute(status -> {
            long rows = ledger.view(setup.tenantId(), setup.botWalletId(), Optional.empty())
                    .walletRowCount();
            connect(APP)
                    .sql("""
                            insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                              reference, idempotency_key, created_at)
                            values (gen_random_uuid(), :t, :w, 'DEPOSIT', 5, :ref, :ref, now())""")
                    .param("t", setup.tenantId())
                    .param("w", setup.botWalletId())
                    .param("ref", "dep:" + UUID.randomUUID())
                    .update();
            return walletCost.costOf(setup.tenantId(), setup.userId(), setup.botWalletId(), CostPath.CYCLE, rows);
        });

        assertThat(cost).isEqualTo(new WalletCost.Unknown(RealCostUnknownReason.INCONSISTENT_READ));
    }

    @Test
    void anOrderConfirmedBetweenTheOrdersAndTheRowsIsAttributedToItsBotByTheReadOnTheSpot() {
        Setup setup = realBot("AUTO_FULL");
        String mint = SyntheticIds.mint();
        Instant at = clock.instant().minusSeconds(600);
        settled(setup, UUID.randomUUID(), "BUY", mint, -100L, 100, at);
        // UNOWNED: a buy whose order is gone, with another average cost.
        fill(setup, "order:" + UUID.randomUUID(), "SWAP_FILL_IN", mint, -300L, 100, at.plusSeconds(1));
        UUID sale = UUID.randomUUID();
        settled(setup, sale, "SELL", mint, 50L, 100, at.plusSeconds(2));
        RealExecutionPort late = lateOrders(orders, sale);
        AttributedCostReader read = new AttributedCostReader(
                ledger,
                late,
                bots,
                method,
                jdbcTemplate,
                transactionManager,
                clock,
                metrics,
                com.memetrade.pnl.infra.PnlRealProperties.defaults());

        WalletCost cost = transactions.execute(status -> read.costOf(
                setup.tenantId(),
                setup.userId(),
                setup.botWalletId(),
                CostPath.CYCLE,
                ledger.view(setup.tenantId(), setup.botWalletId(), Optional.empty())
                        .walletRowCount()));

        // The own sale of the bot removes the 100 of the bot (cost 100); UNOWNED keeps its 100 with a cost of 300.
        // Without the read on the spot the sale would be of UNOWNED and remove 300.
        assertThat(cost).isEqualTo(new WalletCost.Known(Map.of(mint, 300L)));
    }

    /** A settled order of the bot of {@code setup} and its swap fill. */
    private void settled(Setup setup, UUID order, String side, String mint, long lamports, long units, Instant at) {
        String signature = SyntheticIds.signature();
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                          side, mint, input_lamports, input_base_units, operation_mode, signing_mode,
                          max_slippage_bps, reason, state, tx_signature, out_base_units, fee_lamports, fee_payer,
                          created_at, updated_at)
                        values (:id, :t, :u, :b, 'BOT', :key, :side, :mint, :inputLamports, :inputUnits, 'AUTO_FULL',
                          'AUTO_FULL', 100, 'teste', 'CONFIRMED', :sig, 1, 5000, 'BOT_WALLET', :at, :at)""")
                .param("id", order)
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("b", setup.botId())
                .param("key", "consistency:" + order)
                .param("side", side)
                .param("mint", mint)
                .param("inputLamports", "BUY".equals(side) ? Math.abs(lamports) : null)
                .param("inputUnits", "SELL".equals(side) ? units : null)
                .param("sig", signature)
                .param("at", Timestamp.from(at))
                .update();
        fill(setup, "order:" + order, "BUY".equals(side) ? "SWAP_FILL_IN" : "SWAP_FILL_OUT", mint, lamports, units, at);
    }

    private void fill(Setup setup, String reference, String kind, String mint, long lamports, long units, Instant at) {
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, reference, idempotency_key, created_at)
                        values (gen_random_uuid(), :t, :w, :kind, :lamports, :mint, :units, :ref, :key, :at)""")
                .param("t", setup.tenantId())
                .param("w", setup.botWalletId())
                .param("kind", kind)
                .param("lamports", lamports)
                .param("mint", mint)
                .param("units", units)
                .param("ref", reference)
                .param("key", reference + ":" + kind)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The orders as the stream saw them before {@code late} was confirmed; the read on the spot finds it. */
    @SuppressWarnings("unchecked")
    private static RealExecutionPort lateOrders(RealExecutionPort real, UUID late) {
        return (RealExecutionPort) java.lang.reflect.Proxy.newProxyInstance(
                RealExecutionPort.class.getClassLoader(),
                new Class<?>[] {RealExecutionPort.class},
                (proxy, m, args) -> {
                    Object[] arguments = args == null ? new Object[0] : args.clone();
                    if (m.getName().equals("streamSettledOrders")) {
                        Consumer<RealOrderView> consumer = (Consumer<RealOrderView>) arguments[arguments.length - 1];
                        arguments[arguments.length - 1] = (Consumer<RealOrderView>) order -> {
                            if (!order.realOrderId().equals(late)) {
                                consumer.accept(order);
                            }
                        };
                    }
                    try {
                        return m.invoke(real, arguments);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }
}
