package com.memetrade.bots.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.StrategyTemplate;
import com.memetrade.bots.domain.StrategyTemplates;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.domain.TokenSelection;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 261121-paper-real-unico U1, Step 14 (BR1.3, ADR-009, AC2.4.1 to AC2.4.3): the deprecated {@code mode} of every
 * answer that carries a bot is always its {@code market}, for PAPER and REAL; {@code mode} stays present.
 */
class BotSummaryModeTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @ParameterizedTest
    @EnumSource(BotMarket.class)
    void theModeOfTheSummaryIsTheMarketOfTheBot(BotMarket market) {
        BotSummaryView summary = BotSummaryView.of(bot(market, BotState.DRAFT), NOW);

        assertThat(summary.mode()).isEqualTo(market.name());
        assertThat(summary.market()).isEqualTo(market.name());
    }

    @Test
    void aRealBotIsNeverReportedAsPaper() {
        BotSummaryView summary = BotSummaryView.of(bot(BotMarket.REAL, BotState.ACTIVE), NOW);

        assertThat(summary.mode()).isEqualTo("REAL").isNotEqualTo("PAPER");
    }

    @Test
    void theModeStaysPresentInTheSummary() {
        assertThat(Arrays.stream(BotSummaryView.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("mode", "market");
    }

    @Test
    void aRealBotWithoutLimitsHasNoLimitsVersionInItsSummary() {
        BotSummaryView summary = BotSummaryView.of(bot(BotMarket.REAL, BotState.VALIDATED), NOW);

        assertThat(summary.limitsVersion()).isNull();
        assertThat(summary.state()).isEqualTo(BotState.VALIDATED);
    }

    @Test
    void theStateOfTheBotIsCarriedWhateverTheMarket() {
        assertThat(BotSummaryView.of(bot(BotMarket.PAPER, BotState.ACTIVE), NOW).state())
                .isEqualTo(BotState.ACTIVE);
        assertThat(BotSummaryView.of(bot(BotMarket.REAL, BotState.STOPPED), NOW).state())
                .isEqualTo(BotState.STOPPED);
    }

    private static Bot bot(BotMarket market, BotState state) {
        StrategyTemplate template = StrategyTemplates.ALL.getFirst();
        return new Bot(
                UUID.randomUUID(),
                new TenantScope(UUID.randomUUID(), UUID.randomUUID()),
                "Robo",
                template.templateId(),
                template.version(),
                template.defaults(),
                TokenSelection.empty(),
                Optional.empty(),
                BotStatus.of(state),
                0,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW,
                0,
                market);
    }
}
