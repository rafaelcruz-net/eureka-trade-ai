package com.memetrade.bots.infra;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.decision.domain.StrategyId;
import com.memetrade.marketdata.api.TokenId;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the bots module (logical-components.md): {@code bot}, {@code bot_owner}, {@code bot_limits_version},
 * {@code bot_holding} and {@code system_pause_state}. Every read of customer data carries the {@link TenantScope}
 * (NFR5.1); the worker lists read the scope FROM EACH ROW ({@link BotRef}) and the jobs pass it on. The only query
 * without a scope is {@link #marksFor}, the registered exception of the live channel (performance-design.md): it
 * receives the explicit pairs of the open channels and returns only (tenant, person, mark). Row locks live in
 * {@link LockOrder} only.
 */
@Repository
public class JdbcBotRepository {

    static final String SELECT_BOT = """
            select b.bot_id, b.tenant_id, b.owner_user_id, b.name, b.template_id, b.template_version,
                   b.parameters::text as parameters, b.token_selection::text as token_selection, b.virtual_balance,
                   b.state, b.pause_reasons, b.change_seq, b.draft_step, b.current_limits_version,
                   b.draft_limits::text as draft_limits, b.funded_at, b.activated_at, b.paused_at, b.stopped_at,
                   b.created_at, b.updated_at, b.version, b.market
            from bot b""";

    /** PostgreSQL orders uuid by unsigned bytes: the all-zero id is below every other one (keyset start). */
    private static final UUID LOWEST_ID = new UUID(0L, 0L);

    private final JdbcClient jdbc;

    public JdbcBotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 261121-paper-real-unico U1 (C8, BR4.1): the bots of one person that are not drafts (never DRAFT nor VALIDATED),
     * in order of creation ({@code created_at}, then {@code bot_id}); optionally of one market or one bot. Filtered by
     * tenant and person (index {@code bot_owner_state_idx}); no lock.
     */
    public List<DirectoryRow> directory(TenantScope scope, Optional<String> market, Optional<UUID> botId) {
        return jdbc.sql("""
                        select bot_id, name, market, template_id, state, created_at from bot
                        where tenant_id = :tenant and owner_user_id = :owner
                          and state in ('ACTIVE', 'CLOSING_PENDING', 'PAUSED', 'STOPPED')
                          and (cast(:market as varchar) is null or market = cast(:market as varchar))
                          and (cast(:bot as uuid) is null or bot_id = cast(:bot as uuid))
                        order by created_at, bot_id""")
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .param("market", market.orElse(null))
                .param("bot", botId.orElse(null))
                .query((rs, row) -> new DirectoryRow(
                        rs.getObject("bot_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("market"),
                        rs.getString("template_id"),
                        BotState.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** One row of {@link #directory}: the minimal projection of a bot for the P&L (ADR-007). */
    public record DirectoryRow(
            UUID botId, String name, String market, String templateId, BotState state, Instant createdAt) {}

    /** A page of bots of one person, by last update descending (BR6.1). */
    public record BotPage(List<Bot> items, Optional<String> next) {}

    // ----- bots -----

    /** BR2.11: the control row of the person, created by the first bot (insert that never aborts on conflict). */
    public void ensureOwner(TenantScope scope, Instant now) {
        jdbc.sql("""
                        insert into bot_owner (tenant_id, owner_user_id, created_at) values (:tenant, :owner, :now)
                        on conflict (tenant_id, owner_user_id) do nothing
                        """)
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .param("now", Timestamp.from(now))
                .update();
    }

    public void insert(Bot bot) {
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, mode, name, template_id, template_version,
                                         parameters, token_selection, state, pause_reasons, draft_step, created_at,
                                         updated_at, market)
                        values (:bot, :tenant, :owner, 'PAPER', :name, :template, :version,
                                cast(:parameters as jsonb), cast(:selection as jsonb), :state, '', :step, :now, :now,
                                :market)
                        """)
                .param("market", bot.market().name())
                .param("bot", bot.botId())
                .param("tenant", bot.scope().tenantId())
                .param("owner", bot.scope().ownerUserId())
                .param("name", bot.name())
                .param("template", bot.templateId().name())
                .param("version", bot.templateVersion())
                .param("parameters", BotJson.parameters(bot.parameters()))
                .param("selection", BotJson.selection(bot.selection()))
                .param("state", bot.status().state().name())
                .param("step", bot.draftStep())
                .param("now", Timestamp.from(bot.createdAt()))
                .update();
    }

    public Optional<Bot> find(UUID botId, TenantScope scope) {
        return jdbc.sql(SELECT_BOT + " where b.bot_id = :bot and b.tenant_id = :tenant and b.owner_user_id = :owner")
                .param("bot", botId)
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(JdbcBotRepository::mapBot)
                .optional();
    }

    /**
     * Writes every mutable column of the bot and renews its change mark from the global sequence in the same write
     * (BR8.1), so the mark of the person grows with every change of state, reason, draft or limits.
     */
    public void save(Bot bot) {
        jdbc.sql("""
                        update bot set parameters = cast(:parameters as jsonb),
                                       token_selection = cast(:selection as jsonb),
                                       virtual_balance = :balance, state = :state, pause_reasons = :reasons,
                                       draft_step = :step, current_limits_version = :limits,
                                       draft_limits = cast(:draftLimits as jsonb), funded_at = :funded,
                                       activated_at = :activated, paused_at = :paused, stopped_at = :stopped,
                                       updated_at = :updated, change_seq = nextval('bot_change_seq'),
                                       version = version + 1
                        where bot_id = :bot and tenant_id = :tenant and owner_user_id = :owner
                        """)
                .param("parameters", BotJson.parameters(bot.parameters()))
                .param("selection", BotJson.selection(bot.selection()))
                .param("balance", bot.virtualBalance().orElse(null), java.sql.Types.NUMERIC)
                .param("state", bot.status().state().name())
                .param("reasons", reasons(bot.status()))
                .param("step", bot.draftStep())
                .param("limits", bot.currentLimitsVersion().orElse(null), java.sql.Types.INTEGER)
                .param("draftLimits", bot.draftLimits().map(BotJson::limits).orElse(null), java.sql.Types.VARCHAR)
                .param("funded", timestamp(bot.fundedAt()), java.sql.Types.TIMESTAMP)
                .param("activated", timestamp(bot.activatedAt()), java.sql.Types.TIMESTAMP)
                .param("paused", timestamp(bot.pausedAt()), java.sql.Types.TIMESTAMP)
                .param("stopped", timestamp(bot.stoppedAt()), java.sql.Types.TIMESTAMP)
                .param("updated", Timestamp.from(bot.updatedAt()))
                .param("bot", bot.botId())
                .param("tenant", bot.scope().tenantId())
                .param("owner", bot.scope().ownerUserId())
                .update();
    }

    /** Writes the new status (and its instants) of a bot: {@link #save} with the status timestamps of BR2. */
    public void updateStatus(Bot bot) {
        save(bot);
    }

    /** BR8.1: renews the mark of the bot without any other change (a completed cycle, F3). */
    public void renewMark(UUID botId) {
        jdbc.sql("update bot set change_seq = nextval('bot_change_seq') where bot_id = :bot")
                .param("bot", botId)
                .update();
    }

    /** BR6.1: the bots of a person, optionally of one state, by cursor (last update descending, then id). */
    public BotPage list(TenantScope scope, Optional<BotState> state, Optional<String> cursor, int limit) {
        Optional<Cursor> after = cursor.map(Cursor::decode);
        StringBuilder sql =
                new StringBuilder(SELECT_BOT).append(" where b.tenant_id = :tenant and b.owner_user_id = :owner");
        state.ifPresent(s -> sql.append(" and b.state = :state"));
        after.ifPresent(c -> sql.append(" and (b.updated_at, b.bot_id) < (:afterUpdated, :afterBot)"));
        sql.append(" order by b.updated_at desc, b.bot_id desc limit :limit");
        var statement = jdbc.sql(sql.toString())
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .param("limit", limit + 1);
        if (state.isPresent()) {
            statement = statement.param("state", state.get().name());
        }
        if (after.isPresent()) {
            statement = statement
                    .param("afterUpdated", Timestamp.from(after.get().updatedAt()))
                    .param("afterBot", after.get().botId());
        }
        List<Bot> rows = statement.query(JdbcBotRepository::mapBot).list();
        if (rows.size() <= limit) {
            return new BotPage(rows, Optional.empty());
        }
        List<Bot> page = rows.subList(0, limit);
        Bot last = page.getLast();
        return new BotPage(List.copyOf(page), Optional.of(new Cursor(last.updatedAt(), last.botId()).encode()));
    }

    /** BR2.2: the ACTIVE bots of the person, counted by the caller UNDER the owner lock. */
    public long countActive(TenantScope scope) {
        return jdbc.sql("select count(*) from bot where tenant_id = :tenant and owner_user_id = :owner"
                        + " and state = 'ACTIVE'")
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(Long.class)
                .single();
    }

    /** BR7.3: bots per state of every tenant (every state present, zero included), without any identifier. */
    public Map<BotState, Long> countByState() {
        Map<BotState, Long> counts = zeroes();
        jdbc.sql("select state, count(*) as n from bot group by state").query(rs -> {
            counts.put(BotState.valueOf(rs.getString("state")), rs.getLong("n"));
        });
        return counts;
    }

    /** BR7.3: bots per state of ONE person. */
    public Map<BotState, Long> countByState(TenantScope scope) {
        Map<BotState, Long> counts = zeroes();
        jdbc.sql("select state, count(*) as n from bot where tenant_id = :tenant and owner_user_id = :owner"
                        + " group by state")
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(rs -> {
                    counts.put(BotState.valueOf(rs.getString("state")), rs.getLong("n"));
                });
        return counts;
    }

    // ----- worker lists: the scope comes from each row (BR2.10) -----

    /** BR3.1: the ACTIVE bots of the slot, by the partial index of the dispatch. */
    public List<BotRef> activeBots() {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id from bot where state = 'ACTIVE' order by bot_id")
                .query(JdbcBotRepository::mapRef)
                .list();
    }

    /** BR2.6: the bots paused with {@code reason} (midnight: DAILY_LOSS). */
    public List<BotRef> botsWith(PauseReason reason) {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id from bot where state = 'PAUSED'"
                        + " and (',' || pause_reasons || ',') like :pattern order by bot_id")
                .param("pattern", "%," + reason.name() + ",%")
                .query(JdbcBotRepository::mapRef)
                .list();
    }

    /** BR2.8: the bots in {@code state} (closing check: CLOSING_PENDING). */
    public List<BotRef> botsIn(BotState state) {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id from bot where state = :state order by bot_id")
                .param("state", state.name())
                .query(JdbcBotRepository::mapRef)
                .list();
    }

    /** BR7.1: one batch of ACTIVE and PAUSED bots after {@code after} (keyset by id), as already committed. */
    public List<BotRef> activeOrPaused(Optional<UUID> after, int limit) {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id from bot where state in ('ACTIVE', 'PAUSED')"
                        + " and bot_id > :after order by bot_id limit :limit")
                .param("after", after.orElse(LOWEST_ID))
                .param("limit", limit)
                .query(JdbcBotRepository::mapRef)
                .list();
    }

    /** BR7.2: one batch of bots that still carry SYSTEM after {@code after}. */
    public List<BotRef> withSystemReason(Optional<UUID> after, int limit) {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id from bot where state = 'PAUSED'"
                        + " and (',' || pause_reasons || ',') like '%,SYSTEM,%' and bot_id > :after"
                        + " order by bot_id limit :limit")
                .param("after", after.orElse(LOWEST_ID))
                .param("limit", limit)
                .query(JdbcBotRepository::mapRef)
                .list();
    }

    // ----- marks (C18) -----

    /** BR8.1: the highest change mark of the person's bots; zero without bots. */
    public long markFor(TenantScope scope) {
        return jdbc.sql("select coalesce(max(change_seq), 0) from bot where tenant_id = :tenant"
                        + " and owner_user_id = :owner")
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(Long.class)
                .single();
    }

    /**
     * The registered exception without TenantScope (performance-design.md): ONE query for the explicit pairs of the
     * open live channels, returning only (tenant, person, mark); a pair without bots maps to zero.
     */
    public Map<TenantScope, Long> marksFor(Collection<TenantScope> scopes) {
        Map<TenantScope, Long> marks = new HashMap<>();
        if (scopes.isEmpty()) {
            return marks;
        }
        List<TenantScope> pairs = List.copyOf(new java.util.LinkedHashSet<>(scopes));
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            in.append(i == 0 ? "" : ", ")
                    .append("(:t")
                    .append(i)
                    .append(", :o")
                    .append(i)
                    .append(")");
            marks.put(pairs.get(i), 0L);
        }
        var statement = jdbc.sql("select tenant_id, owner_user_id, max(change_seq) as mark from bot"
                + " where (tenant_id, owner_user_id) in (" + in + ") group by tenant_id, owner_user_id");
        for (int i = 0; i < pairs.size(); i++) {
            statement = statement
                    .param("t" + i, pairs.get(i).tenantId())
                    .param("o" + i, pairs.get(i).ownerUserId());
        }
        statement.query(rs -> {
            marks.put(
                    new TenantScope(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class)),
                    rs.getLong("mark"));
        });
        return marks;
    }

    // ----- limits versions -----

    public void insertLimits(UUID botId, int version, BotLimits limits, UUID createdBy, Instant now) {
        jdbc.sql("""
                        insert into bot_limits_version (bot_id, version, max_order_size, max_token_exposure,
                            max_total_exposure, max_daily_loss, max_drawdown_pct, max_slippage_pct, min_liquidity,
                            min_interval_seconds, created_at, created_by)
                        values (:bot, :version, :order, :token, :total, :daily, :drawdown, :slippage, :liquidity,
                                :interval, :now, :by)
                        """)
                .param("bot", botId)
                .param("version", version)
                .param("order", limits.maxOrderSize())
                .param("token", limits.maxTokenExposure())
                .param("total", limits.maxTotalExposure())
                .param("daily", limits.maxDailyLoss())
                .param("drawdown", limits.maxDrawdownPct())
                .param("slippage", limits.maxSlippagePct())
                .param("liquidity", limits.minLiquidity())
                .param("interval", limits.minIntervalSeconds())
                .param("now", Timestamp.from(now))
                .param("by", createdBy)
                .update();
    }

    public Optional<BotLimits> limits(UUID botId, int version) {
        return jdbc.sql("""
                        select max_order_size, max_token_exposure, max_total_exposure, max_daily_loss,
                               max_drawdown_pct, max_slippage_pct, min_liquidity, min_interval_seconds
                        from bot_limits_version where bot_id = :bot and version = :version
                        """)
                .param("bot", botId)
                .param("version", version)
                .query((rs, row) -> new BotLimits(
                        rs.getBigDecimal("max_order_size"),
                        rs.getBigDecimal("max_token_exposure"),
                        rs.getBigDecimal("max_total_exposure"),
                        rs.getBigDecimal("max_daily_loss"),
                        rs.getBigDecimal("max_drawdown_pct"),
                        rs.getBigDecimal("max_slippage_pct"),
                        rs.getBigDecimal("min_liquidity"),
                        rs.getLong("min_interval_seconds")))
                .optional();
    }

    // ----- holdings (BR3.7) -----

    public Map<TokenId, Instant> holdings(UUID botId) {
        Map<TokenId, Instant> holdings = new LinkedHashMap<>();
        jdbc.sql("select chain, token_mint, opened_at from bot_holding where bot_id = :bot order by opened_at")
                .param("bot", botId)
                .query(rs -> {
                    holdings.put(
                            new TokenId(rs.getString("chain"), rs.getString("token_mint")),
                            rs.getTimestamp("opened_at").toInstant());
                });
        return holdings;
    }

    /** The first BUY fill with the position at zero opens the holding; a later one keeps the first opening. */
    public void openHolding(UUID botId, TokenId token, Instant openedAt) {
        jdbc.sql("""
                        insert into bot_holding (bot_id, chain, token_mint, opened_at) values (:bot, :chain, :mint, :at)
                        on conflict (bot_id, chain, token_mint) do nothing
                        """)
                .param("bot", botId)
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("at", Timestamp.from(openedAt))
                .update();
    }

    public void closeHolding(UUID botId, TokenId token) {
        jdbc.sql("delete from bot_holding where bot_id = :bot and chain = :chain and token_mint = :mint")
                .param("bot", botId)
                .param("chain", token.chain())
                .param("mint", token.mint())
                .update();
    }

    // ----- system pause state (C16) -----

    /** Read WITHOUT a lock (the cycle's extra defence, BR3.3). */
    public boolean systemPaused() {
        return jdbc.sql("select active from system_pause_state where id = 1")
                .query(Boolean.class)
                .single();
    }

    /** Written by the system pause and its lifting with the row locked (BR7.1, BR7.2). */
    public void setSystemPaused(boolean active, String correlationId, Instant now) {
        jdbc.sql("update system_pause_state set active = :active, changed_at = :now, correlation_id = :correlation"
                        + " where id = 1")
                .param("active", active)
                .param("now", Timestamp.from(now))
                .param("correlation", correlationId.length() > 100 ? correlationId.substring(0, 100) : correlationId)
                .update();
    }

    // ----- mapping -----

    static Bot mapBot(ResultSet rs, int row) throws SQLException {
        BotState state = BotState.valueOf(rs.getString("state"));
        String reasons = rs.getString("pause_reasons");
        EnumSet<PauseReason> parsed = EnumSet.noneOf(PauseReason.class);
        if (reasons != null && !reasons.isBlank()) {
            Arrays.stream(reasons.split(","))
                    .map(String::trim)
                    .map(PauseReason::valueOf)
                    .forEach(parsed::add);
        }
        int limitsVersion = rs.getInt("current_limits_version");
        boolean noLimits = rs.wasNull();
        String draftLimits = rs.getString("draft_limits");
        return new Bot(
                rs.getObject("bot_id", UUID.class),
                new TenantScope(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class)),
                rs.getString("name"),
                StrategyId.valueOf(rs.getString("template_id")),
                rs.getString("template_version"),
                BotJson.parameters(rs.getString("parameters")),
                BotJson.selection(rs.getString("token_selection")),
                Optional.ofNullable(rs.getBigDecimal("virtual_balance")),
                new BotStatus(state, parsed),
                rs.getLong("change_seq"),
                rs.getInt("draft_step"),
                noLimits ? Optional.empty() : Optional.of(limitsVersion),
                draftLimits == null ? Optional.empty() : Optional.of(BotJson.limits(draftLimits)),
                instant(rs, "funded_at"),
                instant(rs, "activated_at"),
                instant(rs, "paused_at"),
                instant(rs, "stopped_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"),
                com.memetrade.bots.domain.BotMarket.valueOf(rs.getString("market")));
    }

    private static BotRef mapRef(ResultSet rs, int row) throws SQLException {
        return new BotRef(
                rs.getObject("bot_id", UUID.class),
                new TenantScope(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class)));
    }

    private static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static Timestamp timestamp(Optional<Instant> instant) {
        return instant.map(Timestamp::from).orElse(null);
    }

    private static String reasons(BotStatus status) {
        List<String> names = new ArrayList<>();
        status.orderedReasons().forEach(reason -> names.add(reason.name()));
        return String.join(",", names);
    }

    private static Map<BotState, Long> zeroes() {
        Map<BotState, Long> counts = new EnumMap<>(BotState.class);
        for (BotState state : BotState.values()) {
            counts.put(state, 0L);
        }
        return counts;
    }

    /** The opaque keyset cursor of the list: last update (microseconds) and bot id, base64url. */
    record Cursor(Instant updatedAt, UUID botId) {
        String encode() {
            String raw = updatedAt.getEpochSecond() + "." + updatedAt.getNano() + ":" + botId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split(":", 2);
                String[] seconds = parts[0].split("\\.", 2);
                return new Cursor(
                        Instant.ofEpochSecond(Long.parseLong(seconds[0]), Long.parseLong(seconds[1])),
                        UUID.fromString(parts[1]));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException invalid) {
                throw new InvalidCursorException();
            }
        }
    }

    /** A cursor that this repository did not issue; the route answers 400 VALIDATION_FAILED on {@code cursor}. */
    public static final class InvalidCursorException extends RuntimeException {
        InvalidCursorException() {
            super("Invalid cursor");
        }
    }
}
