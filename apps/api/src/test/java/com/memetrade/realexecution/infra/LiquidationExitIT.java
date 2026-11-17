package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexits.AbstractRealExitsIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR4.3, BR6.8, logical-components.md
 * "Liquidação (U4)" and the adjustment of iteration 2): a position whose conditional is being cancelled (WAIT) stays for
 * the next round of the liquidation and never blocks the sale of the other positions in the same round.
 */
@Tag("u7-exits-and-fiscal")
class LiquidationExitIT extends AbstractRealExitsIT {

    @Autowired
    private LiquidationService liquidationService;

    private UUID liquidate(Setup setup) {
        return liquidationService.start(new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER));
    }

    private List<String> soldMints(UUID liquidationId) {
        return jdbc.sql("select mint from real_order where liquidation_id = :id order by created_at")
                .param("id", liquidationId)
                .query(String.class)
                .list();
    }

    private String liquidationState(UUID id) {
        return jdbc.sql("select state from real_liquidation where liquidation_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    @Test
    void aPositionInWaitDoesNotBlockTheSaleOfTheOtherPositionsInTheSameRound() {
        Setup setup = realBot("AUTO_EXITS");
        String other = token("0.0002", 6, 5);
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        position(wallet(setup), other, 7_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        UUID liquidation = liquidate(setup);

        liquidations.runOnce();

        assertThat(soldMints(liquidation)).containsExactly(other);
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(cancelReason(conditional)).isEqualTo("MARKET_SELL");
        assertThat(liquidationState(liquidation)).isEqualTo("SELLING");
    }

    @Test
    void theWaitingPositionIsSoldInALaterRoundAfterItsConditionalIsCancelled() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        UUID liquidation = liquidate(setup);

        liquidations.runOnce();
        assertThat(soldMints(liquidation)).isEmpty();
        assertThat(liquidationState(liquidation))
                .as("never WITHDRAWING while a position waits for its conditional")
                .isEqualTo("SELLING");

        jdbc.sql("update real_conditional_order set state = 'CANCELLED', final_at = now() where"
                        + " conditional_order_id = :id")
                .param("id", conditional)
                .update();
        liquidations.runOnce();

        assertThat(soldMints(liquidation)).containsExactly(setup.mint());
    }
}
