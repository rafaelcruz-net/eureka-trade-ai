package com.memetrade.bots.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit events of U5 (NFR9.4) through the outbox of C9, IN the transaction of the change (a rollback leaves no
 * event). A command of the owner has actor PERSON; an automatic change (cycle, midnight, closing check, system pause)
 * has actor {@code system}, always with the tenant of the bot. An idempotent repetition publishes nothing: callers
 * publish only when the status really changed. Details carry identifiers and reasons only, never amounts nor wallets.
 */
@Component
public class BotAudit {

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public BotAudit(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void byPerson(BotRef bot, String action, Map<String, String> details) {
        publisher.publish(AuditEventCommand.person(
                bot.scope().tenantId(),
                bot.scope().ownerUserId(),
                action,
                target(bot),
                CorrelationId.current(),
                clock.instant(),
                details));
    }

    public void bySystem(BotRef bot, String action, Map<String, String> details) {
        publisher.publish(new AuditEventCommand(
                UUID.randomUUID(),
                Optional.of(bot.scope().tenantId()),
                AuditEventCommand.SYSTEM_ACTOR,
                ActorType.SYSTEM,
                action,
                target(bot),
                CorrelationId.current(),
                clock.instant(),
                details));
    }

    private static String target(BotRef bot) {
        return "bot:" + bot.botId();
    }
}
