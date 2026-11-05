package com.memetrade.bots.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.bots.application.BotSummaryView;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.BotProperties;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerChangeMarks;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.SourceStatusMarks;
import com.memetrade.shared.config.ProcessRole;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The SINGLE reader of the live channel (C7, BR8.2, performance-design.md Q1): every {@code poll-interval} (2 s) it
 * reads, in ONE query, the bot marks of the people with an open channel (the registered exception without
 * TenantScope: explicit pairs in, only marks out), the ledger mark of each person (U7, C18) and the global source mark
 * (U4). For each channel whose mark changed it fetches the details of THAT person only and sends {@code bot.state},
 * {@code trade.recorded}, {@code pnl.updated} or {@code data.status}; a {@code heartbeat} every 15 s; every 60 s a full
 * {@code bot.state} again (corrects the out-of-order mark caveat of C18); {@code auth.expired} when the token expires,
 * then the channel closes. The id of each event carries the marks seen ({@code b<bot>.l<ledger>.s<source>}), so a
 * reconnection with {@code Last-Event-ID} resumes without repeating. The cost does not grow with the number of
 * channels (NFR2.6). Only the api process has channels.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class DashboardMarkPoller {

    private static final Logger log = LoggerFactory.getLogger(DashboardMarkPoller.class);
    private static final Pattern EVENT_ID = Pattern.compile("^b(\\d+)\\.l(\\d+)\\.s(\\d+)$");
    private static final Duration FULL_REFRESH = Duration.ofSeconds(60);
    private static final int TRADES_PER_BOT = 20;

    private final JdbcBotRepository bots;
    private final Optional<LedgerChangeMarks> ledgerMarks;
    private final LedgerReadModel ledger;
    private final SourceStatusMarks sourceMarks;
    private final MarketDataQuery market;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration heartbeat;
    private final Duration timeout;
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();

    /**
     * @param ledgerMarks the C18 mark of U7; U7 declared the interface without a bean, so until it provides one the mark
     *     is derived from its public read model (the instant of the newest order of the person's bots), registered as
     *     an open point for U7
     */
    public DashboardMarkPoller(
            JdbcBotRepository bots,
            Optional<LedgerChangeMarks> ledgerMarks,
            LedgerReadModel ledger,
            SourceStatusMarks sourceMarks,
            MarketDataQuery market,
            ObjectMapper json,
            Clock clock,
            BotProperties properties) {
        this.bots = bots;
        this.ledgerMarks = ledgerMarks;
        this.ledger = ledger;
        this.sourceMarks = sourceMarks;
        this.market = market;
        this.json = json;
        this.clock = clock;
        this.heartbeat = properties.stream().heartbeatInterval();
        this.timeout = properties.stream().timeout();
    }

    public int openChannels() {
        return channels.size();
    }

    /** Opens a channel of {@code person} until {@code expiresAt}; sends what changed since {@code lastEventId}. */
    public SseEmitter open(Principal person, Instant expiresAt, Optional<String> lastEventId) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Instant now = clock.instant();
        Channel channel = new Channel(UUID.randomUUID(), person, emitter, expiresAt, now);
        lastEventId.map(EVENT_ID::matcher).filter(Matcher::matches).ifPresent(matched -> {
            channel.botMark = Long.parseLong(matched.group(1));
            channel.ledgerMark = Long.parseLong(matched.group(2));
            channel.sourceMark = Long.parseLong(matched.group(3));
        });
        emitter.onCompletion(() -> channels.remove(channel.id));
        emitter.onTimeout(() -> channels.remove(channel.id));
        emitter.onError(failure -> channels.remove(channel.id));
        channels.put(channel.id, channel);
        TenantScope scope = TenantScope.of(person);
        refresh(channel, bots.marksFor(List.of(scope)).getOrDefault(scope, 0L), sourceMarks.globalMark(), now, false);
        return emitter;
    }

    @Scheduled(fixedDelayString = "${memetrade.bots.stream.poll-interval}", initialDelayString = "PT2S")
    public void poll() {
        try {
            pollOnce();
        } catch (DataAccessException failure) {
            log.warn("Live channel poll failed: error={}", failure.getClass().getSimpleName());
        }
    }

    /** One round of the single reader over every open channel. */
    public void pollOnce() {
        if (channels.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<TenantScope> scopes = channels.values().stream()
                .map(channel -> TenantScope.of(channel.person))
                .toList();
        Map<TenantScope, Long> marks = bots.marksFor(scopes);
        long source = sourceMarks.globalMark();
        for (Channel channel : List.copyOf(channels.values())) {
            refresh(channel, marks.getOrDefault(TenantScope.of(channel.person), 0L), source, now, true);
        }
    }

    private void refresh(Channel channel, long botMark, long sourceMark, Instant now, boolean polling) {
        synchronized (channel) {
            try {
                if (!now.isBefore(channel.expiresAt)) {
                    send(channel, "auth.expired", Map.of("expiredAt", now.toString()));
                    channel.emitter.complete();
                    channels.remove(channel.id);
                    return;
                }
                long ledgerMark = ledgerMark(channel.person);
                boolean full =
                        polling && Duration.between(channel.lastFull, now).compareTo(FULL_REFRESH) >= 0;
                boolean botChanged = botMark != channel.botMark;
                boolean ledgerChanged = ledgerMark != channel.ledgerMark;
                boolean sourceChanged = sourceMark != channel.sourceMark;
                channel.botMark = botMark;
                channel.ledgerMark = ledgerMark;
                channel.sourceMark = sourceMark;
                if (botChanged || full) {
                    send(channel, "bot.state", botState(channel.person, now));
                    channel.lastFull = now;
                }
                if (ledgerChanged) {
                    List<Map<String, Object>> trades = newTrades(channel);
                    if (!trades.isEmpty()) {
                        send(channel, "trade.recorded", Map.of("items", trades));
                    }
                    send(channel, "pnl.updated", Map.of("ledgerMark", ledgerMark));
                }
                if (sourceChanged) {
                    send(channel, "data.status", Map.of("items", market.sourceStatuses()));
                }
                if (polling && Duration.between(channel.lastHeartbeat, now).compareTo(heartbeat) >= 0) {
                    send(channel, "heartbeat", Map.of("at", now.toString()));
                    channel.lastHeartbeat = now;
                }
            } catch (IOException | IllegalStateException gone) {
                channels.remove(channel.id);
            }
        }
    }

    /** The ledger mark of the person: C18 of U7 when it exists, else the newest order instant (milliseconds). */
    private long ledgerMark(Principal person) {
        if (ledgerMarks.isPresent()) {
            return ledgerMarks.get().markFor(person);
        }
        TenantScope scope = TenantScope.of(person);
        long newest = 0;
        for (Bot bot : bots.list(scope, Optional.empty(), Optional.empty(), 100).items()) {
            for (var trade : ledger.trades(scope.tenantId(), scope.ownerUserId(), bot.botId(), Optional.empty(), 1)
                    .items()) {
                newest = Math.max(newest, trade.createdAt().toEpochMilli());
            }
        }
        return newest;
    }

    /** The state of the bots of THIS person only (TenantScope of the channel). */
    private Map<String, Object> botState(Principal person, Instant now) {
        TenantScope scope = TenantScope.of(person);
        List<BotSummaryView> items = bots.list(scope, Optional.empty(), Optional.empty(), 100).items().stream()
                .map(bot -> BotSummaryView.of(bot, now))
                .toList();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("items", items);
        state.put("activeCount", bots.countActive(scope));
        return state;
    }

    /** The orders of the person's bots recorded after the last one this channel sent (U7 public read model). */
    private List<Map<String, Object>> newTrades(Channel channel) {
        TenantScope scope = TenantScope.of(channel.person);
        List<Map<String, Object>> trades = new ArrayList<>();
        Instant latest = channel.lastTradeAt;
        for (Bot bot : bots.list(scope, Optional.empty(), Optional.empty(), 100).items()) {
            for (var trade : ledger.trades(
                            scope.tenantId(), scope.ownerUserId(), bot.botId(), Optional.empty(), TRADES_PER_BOT)
                    .items()) {
                if (channel.lastTradeAt != null && !trade.createdAt().isAfter(channel.lastTradeAt)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("orderId", trade.orderId());
                item.put("botId", trade.botId());
                item.put("side", trade.side().name());
                item.put("chain", trade.chain());
                item.put("tokenMint", trade.tokenMint());
                item.put("status", trade.status().name());
                item.put("createdAt", trade.createdAt().toString());
                trades.add(item);
                latest = latest == null || trade.createdAt().isAfter(latest) ? trade.createdAt() : latest;
            }
        }
        channel.lastTradeAt = latest;
        return trades;
    }

    private void send(Channel channel, String event, Object data) throws IOException {
        String id = "b" + channel.botMark + ".l" + channel.ledgerMark + ".s" + channel.sourceMark;
        channel.emitter.send(SseEmitter.event().id(id).name(event).data(json.writeValueAsString(data)));
    }

    private static final class Channel {
        private final UUID id;
        private final Principal person;
        private final SseEmitter emitter;
        private final Instant expiresAt;
        private long botMark = -1;
        private long ledgerMark = -1;
        private long sourceMark = -1;
        private Instant lastFull;
        private Instant lastHeartbeat;
        private Instant lastTradeAt;

        private Channel(UUID id, Principal person, SseEmitter emitter, Instant expiresAt, Instant openedAt) {
            this.id = id;
            this.person = person;
            this.emitter = emitter;
            this.expiresAt = expiresAt;
            this.lastFull = openedAt;
            this.lastHeartbeat = openedAt;
            this.lastTradeAt = openedAt;
        }
    }
}
