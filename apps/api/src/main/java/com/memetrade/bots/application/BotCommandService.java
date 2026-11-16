package com.memetrade.bots.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotConfigValidator;
import com.memetrade.bots.domain.BotConfigValidator.RawLimits;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.domain.BotStateMachine.Change;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.StrategyTemplate;
import com.memetrade.bots.domain.StrategyTemplates;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.domain.TokenSelection;
import com.memetrade.bots.domain.Violation;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.FundResult;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The commands of the person on a PAPER bot (C4; BR1.1 to BR1.7, BR2.2 to BR2.7, BR5.1). Each one is ONE short
 * transaction in READ COMMITTED with {@code lock_timeout} of 2 s (NFR10.7), the common idempotency mechanism of U1 (the
 * same key gives the same answer and no second event, NFR4.2) and the locks in the global order of BR2.11 through
 * {@link LockOrder}. A domain refusal ({@code BotRuleViolation}, {@link BotValidationException},
 * {@link BotNotFoundException}) rolls everything back; nothing is half written.
 *
 * <p>The principal is the owner bound in the authentication context (the request in the api); U7 reads it too for
 * {@code fund}, {@code closePositions} and {@code snapshot}. Everything is PAPER: nothing is signed nor sent.
 */
@Service
public class BotCommandService {

    /** BR2.4: the token of the resume snapshot when the bot has neither an explicit token nor a position. */
    public static final TokenId NEUTRAL_TOKEN = new TokenId("solana", "11111111111111111111111111111111");

    private final JdbcBotRepository bots;
    private final LockOrder locks;
    private final BotAudit audit;
    private final IdempotencyGuard idempotency;
    private final PaperExecution paper;
    private final LedgerReadModel ledger;
    private final PortfolioRiskView risk;
    private final BotConfigValidator validator;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private com.memetrade.botwallet.api.RealStateQuery realState;

    /** 261113-trading-real-bot U4 (BR1.1): the REAL state of the person, read when a REAL bot is created. */
    @org.springframework.beans.factory.annotation.Autowired
    void useRealState(com.memetrade.botwallet.api.RealStateQuery realState) {
        this.realState = realState;
    }

    public BotCommandService(
            JdbcBotRepository bots,
            LockOrder locks,
            BotAudit audit,
            IdempotencyGuard idempotency,
            PaperExecution paper,
            LedgerReadModel ledger,
            PortfolioRiskView risk,
            BotConfigValidator validator,
            Clock clock,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.bots = bots;
        this.locks = locks;
        this.audit = audit;
        this.idempotency = idempotency;
        this.paper = paper;
        this.ledger = ledger;
        this.risk = risk;
        this.validator = validator;
        this.clock = clock;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The rows a command locked before its idempotency claim: the bot and the kill switch read with it. */
    private record Locked(Bot bot, boolean systemPaused) {}

    /** BR1.1: a DRAFT from a template with its default parameters; the owner row is created on the first bot. */
    public IdempotentResponse create(Principal person, String templateId, String name, IdempotencyKey key) {
        return create(person, templateId, name, null, key);
    }

    /**
     * 261113-trading-real-bot U4 (BR1.1): the market is chosen at creation and never changes; REAL needs an ACTIVE bot
     * wallet of the person (M1 readRealState), otherwise 422 VALIDATION_FAILED on {@code market}.
     */
    public IdempotentResponse create(
            Principal person, String templateId, String name, String market, IdempotencyKey key) {
        com.memetrade.bots.domain.BotMarket chosen;
        try {
            chosen = market == null
                    ? com.memetrade.bots.domain.BotMarket.PAPER
                    : com.memetrade.bots.domain.BotMarket.valueOf(market);
        } catch (IllegalArgumentException unknown) {
            throw invalid("market", "Informe PAPER ou REAL.");
        }
        if (chosen == com.memetrade.bots.domain.BotMarket.REAL
                && realState.readRealState(person.tenantId(), person.userId()).isEmpty()) {
            throw invalid("market", "Ative a carteira do bot antes de criar um bot REAL.");
        }
        String content = "create:" + templateId + ":" + name
                + (chosen == com.memetrade.bots.domain.BotMarket.REAL ? ":REAL" : "");
        return command(person, IdempotentOperation.BOT_CREATE, key, content, 201, () -> null, nothing -> {
            StrategyTemplate template = StrategyTemplates.find(templateId)
                    .orElseThrow(() -> invalid("templateId", "Estratégia modelo desconhecida."));
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty() || trimmed.length() > 60) {
                throw invalid("name", "Informe um nome de 1 a 60 caracteres.");
            }
            Instant now = clock.instant();
            TenantScope scope = TenantScope.of(person);
            bots.ensureOwner(scope, now);
            Bot bot = new Bot(
                    UUID.randomUUID(),
                    scope,
                    trimmed,
                    template.templateId(),
                    template.version(),
                    template.defaults(),
                    TokenSelection.empty(),
                    Optional.empty(),
                    BotStatus.of(com.memetrade.bots.api.BotState.DRAFT),
                    0,
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    now,
                    now,
                    0,
                    chosen);
            bots.insert(bot);
            audit.byPerson(
                    bot.ref(),
                    AuditActions.BOT_CREATED,
                    Map.of("templateId", template.templateId().name()));
            return bot;
        });
    }

    /** BR1.1, BR1.7: one step of the wizard; editing a VALIDATED bot returns it to DRAFT. */
    public IdempotentResponse saveDraft(Principal person, UUID botId, DraftInput input, IdempotencyKey key) {
        String content = "draft:" + botId + ":" + write(input);
        return command(
                person, IdempotentOperation.BOT_DRAFT_SAVE, key, content, 200, () -> lock(person, botId), bot -> {
                    return switch (input.step()) {
                        case 1 -> saveStepOne(bot, input);
                        case 2 -> saveStepTwo(bot, input);
                        case 3 -> validate(bot, person);
                        case 4 -> markStartStep(bot);
                        default -> throw invalid("step", "A etapa vai de 1 a 4.");
                    };
                });
    }

    /** BR2.2: VALIDATED to ACTIVE, funding the PAPER account on the first activation, in the global lock order. */
    public IdempotentResponse start(Principal person, UUID botId, IdempotencyKey key) {
        TenantScope scope = TenantScope.of(person);
        return command(
                person,
                IdempotentOperation.BOT_START,
                key,
                "start:" + botId,
                200,
                () -> lockCounting(person, botId),
                locked -> {
                    Bot bot = locked.bot();
                    Change change = BotStateMachine.start(bot.status(), locked.systemPaused(), bots.countActive(scope));
                    Instant now = clock.instant();
                    Bot.Builder next = bot.toBuilder()
                            .status(change.after())
                            .activatedAt(now)
                            .updatedAt(now);
                    // 261113-trading-real-bot U4 (U4-NFR2.1): a REAL bot never funds nor writes the PAPER ledger.
                    if (bot.fundedAt().isEmpty() && !bot.real()) {
                        BigDecimal amount = bot.virtualBalance()
                                .orElseThrow(() -> new IllegalStateException("A VALIDATED bot has a virtual balance"));
                        if (!(paper.fund(botId, amount, "fund:" + botId) instanceof FundResult.Funded)) {
                            throw new BotTechnicalFailure("The PAPER account could not be funded");
                        }
                        next.fundedAt(now);
                    }
                    Bot started = next.build();
                    bots.save(started);
                    audit.byPerson(started.ref(), AuditActions.BOT_STARTED, Map.of());
                    return started;
                });
    }

    /** BR2.3: the person's pause; a bot already paused by the person does not change (no second event). */
    public IdempotentResponse pause(Principal person, UUID botId, IdempotencyKey key) {
        return command(
                person, IdempotentOperation.BOT_PAUSE, key, "pause:" + botId, 200, () -> lock(person, botId), bot -> {
                    Change change = BotStateMachine.pause(bot.status());
                    if (!change.changed()) {
                        return bot;
                    }
                    Instant now = clock.instant();
                    Bot paused = bot.toBuilder()
                            .status(change.after())
                            .pausedAt(now)
                            .updatedAt(now)
                            .build();
                    bots.save(paused);
                    audit.byPerson(paused.ref(), AuditActions.BOT_PAUSED, Map.of("reason", PauseReason.PERSON.name()));
                    return paused;
                });
    }

    /** BR2.4: PAUSED to ACTIVE when no reason remains, in the global lock order. */
    public IdempotentResponse resume(Principal person, UUID botId, IdempotencyKey key) {
        TenantScope scope = TenantScope.of(person);
        return command(
                person,
                IdempotentOperation.BOT_RESUME,
                key,
                "resume:" + botId,
                200,
                () -> lockCounting(person, botId),
                locked -> {
                    Bot bot = locked.bot();
                    boolean systemPaused = locked.systemPaused();
                    BotLimits limits = currentLimits(bot);
                    Optional<BigDecimal> dayLoss =
                            bot.status().has(PauseReason.DAILY_LOSS) ? Optional.of(dayLoss(bot)) : Optional.empty();
                    Change change = BotStateMachine.resume(
                            bot.status(),
                            new BotStateMachine.ResumeCheck(
                                    systemPaused, bots.countActive(scope), limits.maxDailyLoss(), dayLoss));
                    Bot resumed = bot.withStatus(change.after(), clock.instant());
                    bots.save(resumed);
                    audit.byPerson(resumed.ref(), AuditActions.BOT_RESUMED, Map.of());
                    return resumed;
                });
    }

    /** BR2.7: closes the positions through U7 and ends STOPPED, or CLOSING_PENDING without a reliable price. */
    public IdempotentResponse stop(Principal person, UUID botId, IdempotencyKey key) {
        return command(
                person, IdempotentOperation.BOT_STOP, key, "stop:" + botId, 200, () -> lock(person, botId), bot -> {
                    BotStateMachine.stop(bot.status(), false);
                    int version = bot.currentLimitsVersion()
                            .orElseThrow(() -> new IllegalStateException("A started bot has a limits version"));
                    int pendingPositions = 0;
                    // 261113-trading-real-bot U4 (U4-NFR2.1, BR8.1): a REAL bot has no PAPER account and never writes
                    // the PAPER ledger; its positions stay in the bot wallet and are sold by "vender tudo e sacar".
                    if (!bot.real()) {
                        CloseResult result = paper.closePositions(botId, version, "close:" + botId);
                        if (!(result instanceof CloseResult.Closed closed)) {
                            throw new BotTechnicalFailure("The positions could not be closed");
                        }
                        pendingPositions = closed.pending().size();
                    }
                    Change change = BotStateMachine.stop(bot.status(), pendingPositions > 0);
                    Instant now = clock.instant();
                    Bot.Builder next = bot.toBuilder().status(change.after()).updatedAt(now);
                    boolean stopped = change.after().state() == com.memetrade.bots.api.BotState.STOPPED;
                    if (stopped) {
                        next.stoppedAt(now);
                    }
                    Bot ended = next.build();
                    bots.save(ended);
                    audit.byPerson(
                            ended.ref(),
                            stopped ? AuditActions.BOT_STOPPED : AuditActions.BOT_CLOSING_PENDING,
                            Map.of("pendingPositions", String.valueOf(pendingPositions)));
                    return ended;
                });
    }

    /** BR5.1: a new version of the limits, valid for the next decisions only (the cycle in progress keeps the old). */
    public IdempotentResponse changeLimits(Principal person, UUID botId, RawLimits limits, IdempotencyKey key) {
        String content = "limits:" + botId + ":" + write(limits);
        return command(person, IdempotentOperation.BOT_LIMITS, key, content, 201, () -> lock(person, botId), bot -> {
            BotStateMachine.requireLimitsEditable(bot.status());
            List<Violation> violations = validator.limits(limits);
            if (!violations.isEmpty()) {
                throw new BotValidationException(violations);
            }
            int version = bot.currentLimitsVersion().orElse(0) + 1;
            Instant now = clock.instant();
            bots.insertLimits(botId, version, BotConfigValidator.toLimits(limits), person.userId(), now);
            Bot changed =
                    bot.toBuilder().currentLimitsVersion(version).updatedAt(now).build();
            bots.save(changed);
            audit.byPerson(changed.ref(), AuditActions.BOT_LIMITS_CHANGED, Map.of("version", String.valueOf(version)));
            return changed;
        });
    }

    // ----- wizard steps -----

    private Bot saveStepOne(Bot bot, DraftInput input) {
        Bot editable = editable(bot);
        StrategyTemplate template = StrategyTemplates.of(bot.templateId());
        Map<String, String> raw = input.parameters() == null ? Map.of() : input.parameters();
        List<Violation> violations = new ArrayList<>(validator.parameters(template, raw));
        BotConfigValidator.RawSelection selection = input.tokenSelection() == null
                ? new BotConfigValidator.RawSelection(List.of(), List.of())
                : input.tokenSelection();
        violations.addAll(validator.selection(selection));
        if (!violations.isEmpty()) {
            throw new BotValidationException(violations);
        }
        Map<String, BigDecimal> parameters = new LinkedHashMap<>();
        template.parameters()
                .forEach(spec -> parameters.put(
                        spec.key(), new BigDecimal(raw.get(spec.key()).trim())));
        return saved(editable.toBuilder()
                .parameters(parameters)
                .selection(BotConfigValidator.toSelection(selection))
                .draftStep(Math.max(editable.draftStep(), 1))
                .updatedAt(clock.instant())
                .build());
    }

    private Bot saveStepTwo(Bot bot, DraftInput input) {
        Bot editable = editable(bot);
        List<Violation> violations = new ArrayList<>(validator.balance(input.virtualBalance()));
        if (input.limits() == null) {
            violations.add(new Violation("limits", Violation.Code.VALIDATION_FAILED, "Informe os limites."));
        } else {
            validator
                    .limits(input.limits())
                    .forEach(v -> violations.add(new Violation("limits." + v.field(), v.code(), v.message())));
        }
        if (!violations.isEmpty()) {
            throw new BotValidationException(violations);
        }
        return saved(editable.toBuilder()
                .virtualBalance(new BigDecimal(input.virtualBalance().trim()).setScale(6))
                .draftLimits(Optional.of(BotConfigValidator.toLimits(input.limits())))
                .draftStep(Math.max(editable.draftStep(), 2))
                .updatedAt(clock.instant())
                .build());
    }

    /** BR1.7: with steps 1 and 2 valid, records the limits version and the bot is VALIDATED. */
    private Bot validate(Bot bot, Principal person) {
        if (bot.status().state() == com.memetrade.bots.api.BotState.VALIDATED) {
            return bot;
        }
        StrategyTemplate template = StrategyTemplates.of(bot.templateId());
        List<Violation> pending = new ArrayList<>();
        Map<String, String> parameters = new LinkedHashMap<>();
        bot.parameters().forEach((k, v) -> parameters.put(k, v.toPlainString()));
        pending.addAll(validator.parameters(template, parameters));
        if (bot.selection().isEmpty()) {
            pending.add(new Violation(
                    "tokenSelection", Violation.Code.VALIDATION_FAILED, "Escolha pelo menos um token ou uma regra."));
        }
        if (bot.virtualBalance().isEmpty()) {
            pending.add(new Violation("virtualBalance", Violation.Code.VALIDATION_FAILED, "Informe o saldo virtual."));
        }
        if (bot.draftLimits().isEmpty()) {
            pending.add(new Violation("limits", Violation.Code.VALIDATION_FAILED, "Informe os limites."));
        }
        if (!pending.isEmpty()) {
            throw new BotValidationException(pending);
        }
        Change change = BotStateMachine.validate(bot.status());
        int version = bot.currentLimitsVersion().orElse(0) + 1;
        Instant now = clock.instant();
        bots.insertLimits(bot.botId(), version, bot.draftLimits().orElseThrow(), person.userId(), now);
        return saved(bot.toBuilder()
                .status(change.after())
                .currentLimitsVersion(version)
                .draftStep(Math.max(bot.draftStep(), 3))
                .updatedAt(now)
                .build());
    }

    private Bot markStartStep(Bot bot) {
        if (bot.status().state() != com.memetrade.bots.api.BotState.VALIDATED) {
            throw new com.memetrade.bots.domain.BotRuleViolation(
                    com.memetrade.bots.domain.BotRuleViolation.Code.INVALID_TRANSITION, "Validate the bot first");
        }
        return saved(bot.toBuilder().draftStep(4).updatedAt(clock.instant()).build());
    }

    /** A VALIDATED bot being edited returns to DRAFT (BR1.7); any other state cannot be edited. */
    private Bot editable(Bot bot) {
        Change change = BotStateMachine.edit(bot.status());
        return bot.withStatus(change.after(), clock.instant());
    }

    private Bot saved(Bot bot) {
        bots.save(bot);
        return bot;
    }

    // ----- helpers -----

    private Bot lock(Principal person, UUID botId) {
        return locks.lockBot(botId, TenantScope.of(person)).orElseThrow(BotNotFoundException::new);
    }

    private BotLimits currentLimits(Bot bot) {
        int version = bot.currentLimitsVersion()
                .orElseThrow(() -> new IllegalStateException("A started bot has a limits version"));
        return bots.limits(bot.botId(), version)
                .orElseThrow(() -> new IllegalStateException("Missing limits version " + version));
    }

    /**
     * BR2.4: the realized loss of the UTC day with fees, from C13 with the owner's principal and any token of the bot
     * (the day loss does not depend on it): the first explicit token, else the first token with a position, else the
     * neutral token, which never has a position.
     */
    private BigDecimal dayLoss(Bot bot) {
        TokenId token = bot.selection().tokens().stream()
                .findFirst()
                .or(() -> ledger.positions(bot.botId()).stream()
                        .filter(position -> position.quantity().signum() > 0)
                        .map(position -> new TokenId(position.chain(), position.tokenMint()))
                        .findFirst())
                .orElse(NEUTRAL_TOKEN);
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return risk.snapshot(bot.botId(), token.chain(), token.mint(), day).realizedDayLossWithFees();
    }

    /** BR2.11: the kill switch (shared), the owner row and the bot, in this order, for start and resume. */
    private Locked lockCounting(Principal person, UUID botId) {
        boolean systemPaused = locks.lockSystemPauseShared();
        locks.lockOwner(TenantScope.of(person));
        return new Locked(lock(person, botId), systemPaused);
    }

    /**
     * One command: the lock timeout, then the row locks ({@code prepare}, BEFORE the idempotency claim, so a lock that
     * is not obtained in time fails cleanly with {@code 503} and a repeated key waits for the bot like the first one),
     * then the claim of the common mechanism and the change.
     */
    private <T> IdempotentResponse command(
            Principal person,
            IdempotentOperation operation,
            IdempotencyKey key,
            String content,
            int status,
            Supplier<T> prepare,
            java.util.function.Function<T, Bot> action) {
        IdempotencyRequest request = new IdempotencyRequest(
                operation, IdempotencyScope.authenticated(person, operation), key, sha256(content));
        return transactions.execute(transaction -> {
            locks.applyCommandTimeouts();
            T locked = prepare.get();
            return idempotency.execute(request, () -> {
                Bot bot = action.apply(locked);
                String body = write(BotSummaryView.of(bot, clock.instant()));
                return IdempotentOutcome.of(IdempotentResponse.fresh(status, body));
            });
        });
    }

    private static BotValidationException invalid(String field, String message) {
        return new BotValidationException(List.of(new Violation(field, Violation.Code.VALIDATION_FAILED, message)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A bot answer could not be serialised", failure);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }
}
