package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; BR8.6, NFR3.1, team.md "isolamento entre contas"): the
 * read of cost uses the tenant and the person of the wallet asked for, and reads nothing of another tenant: neither the
 * rows of a wallet of somebody else, nor an order of somebody else named by a row (it is "gone", UNOWNED, never the bot
 * of the other person).
 */
@Tag("u2-real-bot-pnl")
class AttributedCostTenantIT extends AbstractRealExecutionIT {

    @Autowired
    private RealWalletCost walletCost;

    @Test
    void theWalletOfAnotherTenantGivesNothing() {
        Setup mine = realBot("AUTO_FULL");
        Setup theirs = realBot("AUTO_FULL");
        position(wallet(theirs), SyntheticIds.mint(), 1_000, 7 * 1_000_000L);

        WalletCost cost = transactions.execute(
                status -> walletCost.costOf(mine.tenantId(), mine.userId(), theirs.botWalletId(), CostPath.GATE, 0));

        assertThat(cost).isEqualTo(new WalletCost.Known(Map.of()));
    }

    @Test
    void anOrderOfAnotherTenantNamedByARowIsNeverReadAndNeverItsBot() {
        Setup mine = realBot("AUTO_FULL");
        Setup theirs = realBot("AUTO_FULL");
        String mint = SyntheticIds.mint();
        UUID theirOrder = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                          side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                          tx_signature, out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                        values (:id, :t, :u, :b, 'BOT', :key, 'BUY', :mint, 100, 'AUTO_FULL', 'AUTO_FULL', 100,
                          'teste', 'CONFIRMED', :sig, 1, 5000, 'BOT_WALLET', now(), now())""")
                .param("id", theirOrder)
                .param("t", theirs.tenantId())
                .param("u", theirs.userId())
                .param("b", theirs.botId())
                .param("key", "tenant:" + theirOrder)
                .param("mint", mint)
                .param("sig", SyntheticIds.signature())
                .update();
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, reference, idempotency_key, created_at)
                        values (gen_random_uuid(), :t, :w, 'SWAP_FILL_IN', -100, :mint, 10, :ref, :key, :at)""")
                .param("t", mine.tenantId())
                .param("w", mine.botWalletId())
                .param("mint", mint)
                .param("ref", "order:" + theirOrder)
                .param("key", "tenant-fill:" + theirOrder)
                .param("at", Timestamp.from(clock.instant()))
                .update();

        WalletCost cost = transactions.execute(status -> walletCost.costOf(
                mine.tenantId(),
                mine.userId(),
                mine.botWalletId(),
                CostPath.GATE,
                ledger.view(mine.tenantId(), mine.botWalletId(), Optional.empty())
                        .walletRowCount()));

        // The order of the other person is not found for this person: the buy is UNOWNED, never UNKNOWN_BOT.
        assertThat(cost).isEqualTo(new WalletCost.Known(Map.of(mint, 100L)));
    }
}
