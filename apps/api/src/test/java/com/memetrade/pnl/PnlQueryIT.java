package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.application.PnlQueryService;
import com.memetrade.pnl.application.PnlQueryService.PnlView;
import com.memetrade.pnl.domain.Mark.UnrealizedUnknownReason;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import com.memetrade.pnl.domain.PnlCalculator.Line;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.PaperDriver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 15 (written before the code): the P&L of a period (F7, BR6.1 to BR6.6, NFR3.2, NFR4.4): realized and fees of
 * the fills in [from, to), unrealized of the positions open NOW by the exit price, unknown and partial, "no liquidity"
 * with source and time, empty period, 366 days, another person's bot 404 and a bot not activated empty.
 */
class PnlQueryIT extends AbstractU7IT {

    private static final Instant FROM = MutableClock.DEFAULT_START.minus(Duration.ofHours(12));
    private static final Instant TO = MutableClock.DEFAULT_START.plus(Duration.ofHours(12));

    @Autowired
    private PaperExecution execution;

    @Autowired
    private PnlQueryService pnl;

    private PaperDriver driver;
    private Principal person;
    private TokenId token;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        person = newPerson();
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
    }

    private PnlView report(Optional<UUID> bot) {
        return pnl.report(person, FROM, TO, bot);
    }

    private static Line line(PnlView view, TokenId token) {
        return view.report().lines().stream()
                .filter(l -> l.mint().equals(token.mint()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void c1AndC4TheRealizedAndTheFeesOfThePeriodWithoutOpenPosition() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.500000", "100000.000000", 6);
        driver.sell(person, bot, token, "100");

        PnlView view = report(Optional.of(bot));

        assertThat(view.report().realized()).isEqualByComparingTo("48.425000");
        assertThat(view.report().fees()).isEqualByComparingTo("0.748875");
        assertThat(view.report().unrealized()).isEqualTo(new Amount.Known(new BigDecimal("0.000000")));
        assertThat(view.report().partial()).isFalse();
        assertThat(view.report().empty()).isFalse();
        assertThat(view.unrealizedAsOf()).isEqualTo(clock.instant());
    }

    @Test
    void c9TheUnrealizedOfAnOpenPositionByTheExitPrice() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);

        PnlView view = report(Optional.of(bot));

        assertThat(line(view, token).unrealized()).isEqualTo(new Amount.Known(new BigDecimal("18.298232")));
        assertThat(view.report().realized()).isEqualByComparingTo("0");
        assertThat(view.report().fees()).isEqualByComparingTo("0.301800");
    }

    @Test
    void c8AnUnknownPriceMakesTheTotalUnknownAndPartialNeverZero() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.price(token, ControllableMarketData.unknown());

        PnlView view = report(Optional.of(bot));

        assertThat(line(view, token).unrealized()).isEqualTo(new Amount.Unknown(UnrealizedUnknownReason.PRICE_UNKNOWN));
        assertThat(view.report().unrealized()).isInstanceOf(Amount.Unknown.class);
        assertThat(view.report().partial()).isTrue();
    }

    @Test
    void aTokenWithoutLiquidityIsMarkedWithTheLastPriceSourceAndTime() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.liquidity(token, market.known("0"));

        PnlView view = report(Optional.of(bot));

        assertThat(line(view, token).noLiquidity()).isTrue();
        assertThat(view.mark(token.chain(), token.mint())).isInstanceOfSatisfying(MarketValue.Known.class, mark -> {
            assertThat(mark.source()).isEqualTo(ControllableMarketData.SOURCE);
            assertThat(mark.observedAt()).isEqualTo(clock.instant());
        });
        assertThat(view.report().partial()).isTrue();
    }

    @Test
    void thePersonsWholePortfolioSumsTheBotsAndTheLinesSumToTheTotal() {
        UUID first = driver.fundedBot(person, "1000.000000");
        UUID second = driver.fundedBot(person, "1000.000000");
        TokenId other = newToken();
        market.set(other, "2.000000", "100000.000000", 6);
        driver.buy(person, first, token, "100");
        driver.buy(person, second, token, "50");
        driver.buy(person, second, other, "10");

        PnlView view = report(Optional.empty());

        assertThat(view.report().lines()).hasSize(2);
        BigDecimal fees = view.report().lines().stream().map(Line::fees).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(view.report().fees()).isEqualTo(fees);
        // 0.301800 (buy 100 at 1, C1) + 0.150825 (buy 50 at 1: consumption 0.0500, gross 50.275000).
        assertThat(line(view, token).fees()).isEqualByComparingTo("0.452625");
    }

    @Test
    void anEmptyPeriodIsExplainedAndABotNotActivatedIsEmpty() {
        UUID bot = driver.fundedBot(person, "1000.000000");

        assertThat(report(Optional.of(bot)).report().empty()).isTrue();
        assertThat(report(Optional.of(UUID.randomUUID())).report().empty())
                .as("not activated")
                .isTrue();
        assertThat(report(Optional.empty()).report().realized()).isEqualByComparingTo("0");
    }

    @Test
    void aBotOfAnotherPersonIsNotFound() {
        UUID bot = driver.fundedBot(person, "1000.000000");

        assertThatThrownBy(() -> pnl.report(newPerson(), FROM, TO, Optional.of(bot)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> pnl.report(samePersonTenant(person), FROM, TO, Optional.of(bot)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void thePeriodIsFromBeforeToAndAtMost366Days() {
        assertThat(pnl.report(person, TO.minus(Duration.ofDays(366)), TO, Optional.empty())
                        .report()
                        .empty())
                .isTrue();
        assertThatThrownBy(
                        () -> pnl.report(person, TO.minus(Duration.ofDays(366)).minusSeconds(1), TO, Optional.empty()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> pnl.report(person, TO, TO, Optional.empty()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void fillsOutsideThePeriodAreLeftOut() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");

        PnlView later = pnl.report(person, TO, TO.plus(Duration.ofDays(1)), Optional.of(bot));

        assertThat(later.report().fees()).isEqualByComparingTo("0");
        assertThat(later.report().empty()).as("the position is still open now").isFalse();
    }
}
