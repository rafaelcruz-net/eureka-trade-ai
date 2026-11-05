package com.memetrade.testsupport;

import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.application.DraftInput;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotConfigValidator.RawLimits;
import com.memetrade.bots.domain.BotConfigValidator.RawRule;
import com.memetrade.bots.domain.BotConfigValidator.RawSelection;
import com.memetrade.bots.domain.BotConfigValidator.RawToken;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Builds bots of the U5 tests through the REAL commands (create, the three wizard steps, start), so every bot is made
 * exactly like the wizard makes it and every PAPER account is funded by the real {@code PaperExecution.fund} of U7.
 * Each command runs as the owner, bound in the authentication context of the test process. Synthetic data only.
 */
public final class U5Bots {

    private final BotCommandService commands;
    private final JdbcBotRepository bots;
    private final BiFunction<Principal, Supplier<Object>, Object> binder;

    public U5Bots(
            BotCommandService commands,
            JdbcBotRepository bots,
            BiFunction<Principal, Supplier<Object>, Object> binder) {
        this.commands = commands;
        this.bots = bots;
        this.binder = binder;
    }

    @SuppressWarnings("unchecked")
    public <T> T as(Principal person, Supplier<T> work) {
        return (T) binder.apply(person, (Supplier<Object>) (Supplier<?>) work);
    }

    public static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    /** A DRAFT of the momentum template. */
    public UUID draft(Principal person) {
        IdempotentResponse created =
                as(person, () -> commands.create(person, "MOMENTUM_VOLUME", "Bot de teste", key()));
        return UUID.fromString(created.body().replaceAll(".*\"botId\":\"([0-9a-f-]{36})\".*", "$1"));
    }

    /** A VALIDATED bot over {@code token} with the default limits of 1.000 USDC, or the limits given. */
    public UUID validated(Principal person, TokenId token, RawLimits limits) {
        UUID botId = draft(person);
        saveSteps(person, botId, token, limits, "1000.000000");
        return botId;
    }

    public UUID validated(Principal person, TokenId token) {
        return validated(person, token, defaultLimits());
    }

    /** An ACTIVE, funded bot over {@code token}. */
    public UUID active(Principal person, TokenId token) {
        return active(person, token, defaultLimits());
    }

    public UUID active(Principal person, TokenId token, RawLimits limits) {
        UUID botId = validated(person, token, limits);
        as(person, () -> commands.start(person, botId, key()));
        return botId;
    }

    /** An ACTIVE bot with a rules-only selection (no explicit token): the resume uses the neutral token. */
    public UUID activeWithRulesOnly(Principal person) {
        UUID botId = draft(person);
        as(person, () -> commands.saveDraft(person, botId, step1(null), key()));
        as(person, () -> commands.saveDraft(person, botId, step2(defaultLimits(), "1000.000000"), key()));
        as(person, () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), key()));
        as(person, () -> commands.start(person, botId, key()));
        return botId;
    }

    public void saveSteps(Principal person, UUID botId, TokenId token, RawLimits limits, String balance) {
        as(person, () -> commands.saveDraft(person, botId, step1(token), key()));
        as(person, () -> commands.saveDraft(person, botId, step2(limits, balance), key()));
        as(person, () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), key()));
    }

    public Bot bot(Principal person, UUID botId) {
        return bots.find(botId, TenantScope.of(person)).orElseThrow();
    }

    public static DraftInput step1(TokenId token) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("windowMinutes", "15");
        parameters.put("volumeGrowthMinPct", "20.0000");
        parameters.put("priceRiseMinPct", "2.0000");
        parameters.put("takeProfitPct", "10.0000");
        parameters.put("stopLossPct", "5.0000");
        parameters.put("maxHoldMinutes", "240");
        parameters.put("buyFractionPct", "10.0000");
        RawSelection selection = token == null
                ? new RawSelection(List.of(), List.of(new RawRule("LIQUIDITY", "GTE", "10000")))
                : new RawSelection(List.of(new RawToken(token.chain(), token.mint())), List.of());
        return new DraftInput(1, parameters, selection, null, null);
    }

    public static DraftInput step2(RawLimits limits, String balance) {
        return new DraftInput(2, null, null, limits, balance);
    }

    /** The default limits of 1.000 USDC (BR1.6). */
    public static RawLimits defaultLimits() {
        return new RawLimits(
                "100.000000", "200.000000", "500.000000", "50.000000", "10.0000", "2.0000", "10000.000000", 240L);
    }

    /** Wide limits for the cycle tests: no limit of U6 gets in the way unless the test wants it. */
    public static RawLimits wideLimits() {
        return new RawLimits(
                "100.000000", "1000.000000", "1000.000000", "1000.000000", "100.0000", "100.0000", "0.000000", 1L);
    }
}
