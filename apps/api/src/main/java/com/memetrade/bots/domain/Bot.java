package com.memetrade.bots.domain;

import com.memetrade.decision.domain.StrategyId;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A PAPER bot of one person (entities.md {@code Bot}), as read from its row. Every bot is PAPER; tenant and person come
 * from the row and are the scope of every later query (BR2.10).
 *
 * @param parameters the strategy parameters by key, exact decimals; empty before step 1 is accepted
 * @param draftLimits the limits of step 2 while the bot is a draft (version 1 is recorded at VALIDATED, BR1.7)
 */
public record Bot(
        UUID botId,
        TenantScope scope,
        String name,
        StrategyId templateId,
        String templateVersion,
        Map<String, BigDecimal> parameters,
        TokenSelection selection,
        Optional<BigDecimal> virtualBalance,
        BotStatus status,
        long changeSeq,
        int draftStep,
        Optional<Integer> currentLimitsVersion,
        Optional<BotLimits> draftLimits,
        Optional<Instant> fundedAt,
        Optional<Instant> activatedAt,
        Optional<Instant> pausedAt,
        Optional<Instant> stoppedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Bot {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(status, "status");
        parameters = Map.copyOf(parameters);
        selection = selection == null ? TokenSelection.empty() : selection;
    }

    /** The principal of the owner, bound by the worker jobs before calling U6 and U7 (BR2.10, BR3.6). */
    public Principal owner() {
        return new Principal(scope.tenantId(), scope.ownerUserId(), Role.BETA_USER);
    }

    public BotRef ref() {
        return new BotRef(botId, scope);
    }

    public Bot withStatus(BotStatus next, Instant now) {
        return toBuilder().status(next).updatedAt(now).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** A changed copy of a bot (the row is rewritten whole by the repository). */
    public static final class Builder {
        private final Bot base;
        private Map<String, BigDecimal> parameters;
        private TokenSelection selection;
        private Optional<BigDecimal> virtualBalance;
        private BotStatus status;
        private int draftStep;
        private Optional<Integer> currentLimitsVersion;
        private Optional<BotLimits> draftLimits;
        private Optional<Instant> fundedAt;
        private Optional<Instant> activatedAt;
        private Optional<Instant> pausedAt;
        private Optional<Instant> stoppedAt;
        private Instant updatedAt;

        private Builder(Bot base) {
            this.base = base;
            this.parameters = base.parameters;
            this.selection = base.selection;
            this.virtualBalance = base.virtualBalance;
            this.status = base.status;
            this.draftStep = base.draftStep;
            this.currentLimitsVersion = base.currentLimitsVersion;
            this.draftLimits = base.draftLimits;
            this.fundedAt = base.fundedAt;
            this.activatedAt = base.activatedAt;
            this.pausedAt = base.pausedAt;
            this.stoppedAt = base.stoppedAt;
            this.updatedAt = base.updatedAt;
        }

        public Builder parameters(Map<String, BigDecimal> value) {
            this.parameters = value;
            return this;
        }

        public Builder selection(TokenSelection value) {
            this.selection = value;
            return this;
        }

        public Builder virtualBalance(BigDecimal value) {
            this.virtualBalance = Optional.of(value);
            return this;
        }

        public Builder status(BotStatus value) {
            this.status = value;
            return this;
        }

        public Builder draftStep(int value) {
            this.draftStep = value;
            return this;
        }

        public Builder currentLimitsVersion(int value) {
            this.currentLimitsVersion = Optional.of(value);
            return this;
        }

        public Builder draftLimits(Optional<BotLimits> value) {
            this.draftLimits = value;
            return this;
        }

        public Builder fundedAt(Instant value) {
            this.fundedAt = Optional.of(value);
            return this;
        }

        public Builder activatedAt(Instant value) {
            this.activatedAt = Optional.of(value);
            return this;
        }

        public Builder pausedAt(Instant value) {
            this.pausedAt = Optional.of(value);
            return this;
        }

        public Builder stoppedAt(Instant value) {
            this.stoppedAt = Optional.of(value);
            return this;
        }

        public Builder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        public Bot build() {
            return new Bot(
                    base.botId,
                    base.scope,
                    base.name,
                    base.templateId,
                    base.templateVersion,
                    parameters,
                    selection,
                    virtualBalance,
                    status,
                    base.changeSeq,
                    draftStep,
                    currentLimitsVersion,
                    draftLimits,
                    fundedAt,
                    activatedAt,
                    pausedAt,
                    stoppedAt,
                    base.createdAt,
                    updatedAt,
                    base.version);
        }
    }
}
