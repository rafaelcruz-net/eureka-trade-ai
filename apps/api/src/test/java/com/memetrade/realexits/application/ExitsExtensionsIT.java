package com.memetrade.realexits.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.botwallet.api.EscrowBalances;
import com.memetrade.botwallet.api.EscrowBalances.Escrow;
import com.memetrade.botwallet.api.OpenExitsCheck;
import com.memetrade.botwallet.api.RealAnonymization;
import com.memetrade.realexits.AbstractRealExitsIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 5 of the U7, written BEFORE the code (BR5.3b, BR6.9, BR8.1, S47, S49, S50): the implementations of the extension
 * interfaces of the M1 by {@code realexits}, seen through the beans the other modules receive.
 */
@Tag("u7-exits-and-fiscal")
class ExitsExtensionsIT extends AbstractRealExitsIT {

    @Autowired
    private EscrowBalances escrows;

    @Autowired
    private OpenExitsCheck openExits;

    @Autowired
    private RealAnonymization anonymization;

    /** S49: only the conditionals of the Jupiter OPEN or CANCELLING have a custody to add to the reconciliation. */
    @Test
    void theCustodiesOfTheOpenAndCancellingConditionalsAreListedForTheReconciliation() {
        Setup setup = realBot("AUTO_EXITS");
        UUID open = conditional(setup, "OPEN", 5_000L);
        String account = jdbc.sql("select escrow_account from real_conditional_order where conditional_order_id = :id")
                .param("id", open)
                .query(String.class)
                .single();

        assertThat(escrows.openEscrows(setup.tenantId(), setup.botWalletId()))
                .containsExactly(new Escrow(setup.mint(), java.util.Optional.of(account)));
        assertThat(escrows.openEscrows(UUID.randomUUID(), setup.botWalletId()))
                .as("another tenant never sees them")
                .isEmpty();
    }

    /** The simulated adapter keeps the token in the bot wallet: no custody (infrastructure-specification.md). */
    @Test
    void aSimulatedConditionalHasNoCustody() {
        Setup setup = realBot("AUTO_EXITS");
        jdbc.sql("""
                        insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,
                          position_mint, kind, stop_price_usd, amount_base_units, slippage_bps, state, adapter,
                          jupiter_order_id, idempotency_key, created_at, updated_at)
                        values (gen_random_uuid(), :t, :u, :w, :m, 'STOP', 0.00008, 5000, 200, 'OPEN', 'SIMULATED',
                          'sim-1', gen_random_uuid()::text, now(), now())""")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("w", setup.botWalletId())
                .param("m", setup.mint())
                .update();

        assertThat(escrows.openEscrows(setup.tenantId(), setup.botWalletId())).isEmpty();
        assertThat(openExits.hasOpenExits(setup.tenantId(), setup.userId()))
                .as("still an open conditional for the anonymization")
                .isTrue();
    }

    /** S47: an OPEN conditional refuses the anonymization with OPEN_CONDITIONALS, through the M1. */
    @Test
    void anOpenConditionalRefusesTheAnonymizationThroughTheM1() {
        Setup setup = realBot("AUTO_EXITS");
        conditional(setup, "OPEN", 5_000L);

        assertThat(openExits.hasOpenExits(setup.tenantId(), setup.userId())).isTrue();
        RealAnonymization.CloseOutcome outcome =
                transactions.execute(status -> anonymization.closeForAnonymization(setup.tenantId(), setup.userId()));
        assertThat(outcome).isEqualTo(new RealAnonymization.CloseOutcome.Refused("OPEN_CONDITIONALS"));
        assertThat(openExits.hasOpenExits(UUID.randomUUID(), setup.userId())).isFalse();
    }

    /** BR8.1, U6 route: {@code GET /api/v1/real/release} says whether the exits are enabled. */
    @Test
    void theReleaseOfThePersonSaysWhetherTheExitsAreEnabled() throws Exception {
        Person person = signIn(true, true);

        exits.enabled.set(true);
        assertThat(body(call(person, get("/api/v1/real/release")))
                        .path("exitsEnabled")
                        .asBoolean())
                .isTrue();
        exits.enabled.set(false);
        assertThat(body(call(person, get("/api/v1/real/release")))
                        .path("exitsEnabled")
                        .asBoolean())
                .isFalse();
    }
}
