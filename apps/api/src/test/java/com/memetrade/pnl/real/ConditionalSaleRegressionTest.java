package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR9.3, FR5.1, AC5.1.1 to AC5.1.3): the regression of
 * the conditional sale. The person has 100 XYZ with a cost of 100.000.000 lamports and a conditional sells the 100 for
 * 180.000.000 with a fee of 5.000: the realized includes +80.000.000 and the fees include 5.000.
 *
 * <p>Recorded red (2026-11-23): the first version ran this scenario against the calculation by orders of the U1
 * ({@code RealPnlCalculator}, fed only with the settled orders) and FAILED with a realized of 0, because
 * {@code conditional:<id>} is not an order and the U1 never saw it. The U1 calculation is gone with the U2; the
 * scenario now runs against the new calculation, read from the ledger by the reference {@code conditional:<id>}.
 */
class ConditionalSaleRegressionTest {

    @Test
    void ac511TheConditionalSaleEntersTheRealizedAndTheFees() {
        PnlScenario ledger = new PnlScenario().bot(RealCostBasisTest.A);
        ledger.buy(RealCostBasisTest.A, 100, 100_000_000L, 0);
        UUID conditional = ledger.conditional(100, 180_000_000L, 5_000L);

        RealPnlResult result = ledger.all();

        assertThat(result.pnl().total().realizedLamports()).isEqualTo(80_000_000L);
        assertThat(result.pnl().total().feesLamports()).isEqualTo(5_000L);
        assertThat(PnlScenario.line(result, RealCostBasisTest.A).shares())
                .singleElement()
                .satisfies(share -> assertThat(share.saleRef()).isEqualTo("conditional:" + conditional));
        assertThat(result.reconciliation().status()).isEqualTo(ReconciliationResult.Status.MATCH);
    }
}
