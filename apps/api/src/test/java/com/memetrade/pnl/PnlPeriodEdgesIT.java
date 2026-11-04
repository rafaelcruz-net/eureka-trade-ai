package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult.Filled;
import com.memetrade.ledger.domain.CompensationPort;
import com.memetrade.ledger.domain.Compensations;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.application.PnlQueryService;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.PaperDriver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 15 (performance-design.md, NFR4.4): whole UTC days come from the daily summary and the partial first and last
 * days from the fills; a period cutting the day of a COMPENSATED fee gives the same value both ways, over several days.
 */
class PnlPeriodEdgesIT extends AbstractU7IT {

    private static final Instant DAY_ONE = Instant.parse("2026-10-28T00:00:00Z");

    @Autowired
    private PaperExecution execution;

    @Autowired
    private PnlQueryService pnl;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private CompensationPort compensations;

    @Test
    void wholeDaysBySummaryAndEdgesByFillsGiveTheSameValueAsTheFillsAlone() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        Principal person = newPerson();
        TokenId token = newToken();
        clock.set(DAY_ONE.plus(Duration.ofHours(3)));
        market.set(token, "1.000000", "100000.000000", 6);
        UUID bot = driver.fundedBot(person, "5000.000000");
        Filled first = (Filled) driver.buy(person, bot, token, "100");
        for (int day = 0; day < 4; day++) {
            clock.set(DAY_ONE.plus(Duration.ofDays(day)).plus(Duration.ofHours(10)));
            market.set(token, "1.1" + day, "100000.000000", 6);
            driver.buy(person, bot, token, "10");
            clock.advance(Duration.ofHours(8));
            market.set(token, "1.2" + day, "100000.000000", 6);
            driver.sell(person, bot, token, "5");
        }
        UUID fee = feeOf(first.orderId());
        transactions.executeWithoutResult(status -> Compensations.compensate(compensations, fee, "edges"));
        clock.set(MutableClock.DEFAULT_START.plus(Duration.ofDays(5)));

        for (Instant[] period : new Instant[][] {
            {DAY_ONE, DAY_ONE.plus(Duration.ofDays(4))},
            {DAY_ONE.plus(Duration.ofHours(6)), DAY_ONE.plus(Duration.ofDays(3)).plus(Duration.ofHours(15))},
            {DAY_ONE.plus(Duration.ofHours(2)), DAY_ONE.plus(Duration.ofHours(23))},
            {DAY_ONE.minus(Duration.ofDays(1)), DAY_ONE.plus(Duration.ofDays(2)).plus(Duration.ofHours(1))}
        }) {
            BigDecimal[] direct = direct(person, bot, period[0], period[1]);
            PnlQueryService.PnlView view = pnl.report(person, period[0], period[1], Optional.of(bot));
            assertThat(view.report().realized())
                    .as(period[0] + " to " + period[1])
                    .isEqualByComparingTo(direct[0]);
            assertThat(view.report().fees()).as(period[0] + " to " + period[1]).isEqualByComparingTo(direct[1]);
        }
        BigDecimal[] dayOne = direct(person, bot, DAY_ONE, DAY_ONE.plus(Duration.ofDays(1)));
        assertThat(pnl.report(
                                person,
                                DAY_ONE.plus(Duration.ofHours(1)),
                                DAY_ONE.plus(Duration.ofDays(1)),
                                Optional.of(bot))
                        .report()
                        .fees())
                .as("the compensated fee of the 03:00 buy is outside [01:00, 24:00) only by its compensation")
                .isEqualByComparingTo(dayOne[1]);
        assertThat(reconciliation
                        .check(
                                person.tenantId(),
                                person.userId(),
                                com.memetrade.pnl.application.ReconciliationService.Trigger.ON_DEMAND)
                        .reconciled())
                .as("summary, fills and the compensation agree")
                .isTrue();
    }

    @Autowired
    private com.memetrade.pnl.application.ReconciliationService reconciliation;

    private BigDecimal[] direct(Principal person, UUID bot, Instant from, Instant to) {
        var fills = ledger.fills(person.tenantId(), person.userId(), Optional.of(bot), from, to);
        BigDecimal realized = fills.stream().map(f -> f.realized()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = fills.stream().map(f -> f.fees()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BigDecimal[] {realized, fees};
    }

    private UUID feeOf(UUID orderId) {
        return jdbc.sql("select entry_id from ledger_entry where reference_id = :o and type = 'FEE'")
                .param("o", orderId.toString())
                .query(UUID.class)
                .single();
    }
}
