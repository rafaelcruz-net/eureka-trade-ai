package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.shared.config.RecordedSwapFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U1 (BR10.1, review R-01), test-after (a test-profile helper of the e2e): without the
 * recordings the first cycle reads nothing; with them it runs only a new bot the strategy applies to, always in the
 * CURRENT slot.
 */
class RecordedSwapFirstCycleTest {

    private static final Instant NOW = Instant.parse("2026-11-21T15:03:20Z");
    private static final RecordedSwapFixture FIXTURE =
            new RecordedSwapFixture("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", 5, 50_000_000L, 154_230_000_000L);

    private final RecordedSwapStrategy strategy = mock(RecordedSwapStrategy.class);
    private final JdbcBotRepository bots = mock(JdbcBotRepository.class);
    private final JdbcCycleRepository cycles = mock(JdbcCycleRepository.class);
    private final CycleRunner runner = mock(CycleRunner.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void withoutTheRecordingsNothingIsRead() {
        RecordedSwapFirstCycle without =
                new RecordedSwapFirstCycle(Optional.empty(), strategy, bots, cycles, runner, clock);

        assertThat(without.runOnce()).isEmpty();
        verifyNoInteractions(bots, cycles, runner);
    }

    @Test
    void onlyANewBotOfTheRecordedSwapRunsAndInTheCurrentSlot() {
        BotRef fresh = ref();
        BotRef cycled = ref();
        BotRef product = ref();
        Bot freshBot = mock(Bot.class);
        Bot productBot = mock(Bot.class);
        when(bots.activeBots()).thenReturn(List.of(fresh, cycled, product));
        when(cycles.hasAnyCycle(cycled.botId())).thenReturn(true);
        when(bots.find(fresh.botId(), fresh.scope())).thenReturn(Optional.of(freshBot));
        when(bots.find(product.botId(), product.scope())).thenReturn(Optional.of(productBot));
        when(strategy.applies(freshBot)).thenReturn(true);
        when(strategy.applies(productBot)).thenReturn(false);
        when(runner.run(any(), any())).thenReturn(CycleResult.completed(UUID.randomUUID(), "BUY", "FILLED"));
        RecordedSwapFirstCycle first =
                new RecordedSwapFirstCycle(Optional.of(FIXTURE), strategy, bots, cycles, runner, clock);

        assertThat(first.runOnce()).hasSize(1);

        verify(runner).run(fresh, Instant.parse("2026-11-21T15:00:00Z"));
        verify(runner, never()).run(cycled, Instant.parse("2026-11-21T15:00:00Z"));
        verify(runner, never()).run(product, Instant.parse("2026-11-21T15:00:00Z"));
        verify(bots, never()).find(cycled.botId(), cycled.scope());
    }

    private static BotRef ref() {
        return new BotRef(UUID.randomUUID(), new TenantScope(UUID.randomUUID(), UUID.randomUUID()));
    }
}
