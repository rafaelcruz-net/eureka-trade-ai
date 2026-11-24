package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 261121-paper-real-unico U4, Etapa 16 (after the layer; decision F3, NFR2.18; observability-design.md "Gauge de bots
 * presos"): the unavailable reads in a row of each bot, in memory, bounded and evicted.
 */
class OwnHoldingsStreaksTest {

    private static final HoldingsResult GOOD = new HoldingsResult.Available(List.of(), Instant.EPOCH);
    private static final HoldingsResult TIMEOUT = new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED);
    private static final HoldingsResult FAILED = new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED);

    private final MutableClock clock = new MutableClock();
    private final OwnHoldingsStreaks streaks = new OwnHoldingsStreaks(clock);
    private final Logger logger = (Logger) LoggerFactory.getLogger(OwnHoldingsStreaks.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void twoFailuresAndAGoodReadAreNotStuck() {
        UUID bot = UUID.randomUUID();
        streaks.update(bot, TIMEOUT);
        streaks.update(bot, FAILED);
        streaks.update(bot, GOOD);

        assertThat(streaks.stuckBots()).isZero();
        assertThat(streaks.streak(bot)).isZero();
    }

    @Test
    void threeFailuresInARowAreOneStuckBot() {
        UUID bot = UUID.randomUUID();
        streaks.update(bot, TIMEOUT);
        streaks.update(bot, FAILED);
        streaks.update(bot, TIMEOUT);

        assertThat(streaks.stuckBots()).isEqualTo(1);
        assertThat(streaks.streak(bot)).isEqualTo(3);
    }

    @Test
    void goodReadsOfAStaleSnapshotAreNeverStuck() {
        UUID bot = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            // A STALE_SNAPSHOT is decided under the lock after a GOOD read: the read itself was available.
            streaks.update(bot, GOOD);
        }

        assertThat(streaks.stuckBots()).isZero();
    }

    @Test
    void botNotOwnedRemovesTheEntry() {
        UUID bot = UUID.randomUUID();
        streaks.update(bot, TIMEOUT);
        streaks.update(bot, TIMEOUT);
        streaks.update(bot, new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED));

        assertThat(streaks.entries()).isZero();
    }

    @Test
    void anEntryUnseenFor21MinutesIsEvicted() {
        UUID bot = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            streaks.update(bot, FAILED);
        }
        clock.advance(Duration.ofMinutes(21));

        assertThat(streaks.stuckBots()).isZero();
        assertThat(streaks.entries()).isZero();
    }

    @Test
    void theMapHoldsAtMostTenThousandBotsAndWarnsOnce() {
        for (int i = 0; i < 10_001; i++) {
            streaks.update(UUID.randomUUID(), FAILED);
        }

        assertThat(streaks.entries()).isEqualTo(10_000);
        assertThat(appender.list)
                .filteredOn(event -> event.getFormattedMessage().contains("event=REAL_OWN_HOLDINGS_STREAKS_FULL"))
                .singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }
}
