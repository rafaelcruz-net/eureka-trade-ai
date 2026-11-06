package com.memetrade.administration.domain;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the state of the kill switch requested by an administrator (BR2.1, BR2.2, BR2.4, BR2.6, R-03 of the NFR
 * Design), in ONE transaction:
 *
 * <ol>
 *   <li>the body is checked first (state ON or OFF, reason of 1 to 500 characters after trimming), so a refused body is
 *       never stored by the idempotency mechanism;
 *   <li>the common idempotency mechanism of the identity module claims the key: the same key and body replay, another
 *       body is {@code 409};
 *   <li>the single row is locked ({@code SELECT ... FOR UPDATE}, first level of BR2.8); the same state writes nothing;
 *       another state writes the row with {@code version + 1}, one history row and one {@code KILL_SWITCH_ON} or
 *       {@code KILL_SWITCH_OFF} by the administrator, without tenant.
 * </ol>
 *
 * The call of U5 is NOT made here: the caller does it after this transaction commits (BR2.1: the state is written
 * before the bots are paused), through the applier, which reads the CURRENT state under its own lock.
 */
public class KillSwitchService {

    /** BR2.6: at most 500 characters (code points) after trimming. */
    public static final int MAX_REASON = 500;

    private final KillSwitchStore store;
    private final ActiveBots activeBots;
    private final AuditEventPublisher audit;
    private final IdempotencyGuard idempotency;
    private final Clock clock;

    public KillSwitchService(
            KillSwitchStore store,
            ActiveBots activeBots,
            AuditEventPublisher audit,
            IdempotencyGuard idempotency,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.activeBots = Objects.requireNonNull(activeBots, "activeBots");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public Outcome change(Command command) {
        String reason = validate(command);
        AtomicReference<Outcome> fresh = new AtomicReference<>();
        IdempotentResponse response = idempotency.execute(request(command, reason), () -> {
            Outcome outcome = write(command.state(), reason, command);
            fresh.set(outcome);
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, stored(outcome)));
        });
        if (response.replayed() || fresh.get() == null) {
            return new Outcome(store.read(), false, true);
        }
        return fresh.get();
    }

    private Outcome write(SwitchState requested, String reason, Command command) {
        KillSwitchSnapshot current = store.lockForUpdate();
        if (current.state() == requested) {
            return new Outcome(current, false, false);
        }
        Instant now = clock.instant();
        long version = current.version() + 1;
        UUID adminId = command.admin().userId();
        store.writeState(new KillSwitchStore.StateWrite(requested, adminId, reason, now, version));
        store.appendChange(new KillSwitchChange(
                UUID.randomUUID(), version, current.state(), requested, adminId, reason, now, command.correlationId()));
        audit.publish(AuditEventCommand.admin(
                adminId,
                requested == SwitchState.ON ? "KILL_SWITCH_ON" : "KILL_SWITCH_OFF",
                "kill-switch",
                command.correlationId(),
                now,
                Map.of(
                        "reason", reason,
                        "version", Long.toString(version),
                        "activeBotsToPause", Long.toString(activeBots.count()))));
        return new Outcome(store.read(), true, false);
    }

    /** BR2.6: every refused field at once, before anything is claimed or written. */
    static String validate(Command command) {
        List<FieldViolation> violations = new ArrayList<>();
        if (command.state() == null) {
            violations.add(new FieldViolation("state", "Must be ON or OFF."));
        }
        String reason = command.reason() == null ? "" : command.reason().strip();
        int length = reason.codePointCount(0, reason.length());
        if (length == 0) {
            violations.add(new FieldViolation("reason", "Must not be empty."));
        } else if (length > MAX_REASON) {
            violations.add(new FieldViolation("reason", "Must have at most 500 characters."));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", violations, null, Map.of());
        }
        return reason;
    }

    private static IdempotencyRequest request(Command command, String reason) {
        return new IdempotencyRequest(
                IdempotentOperation.ADMIN_KILL_SWITCH,
                IdempotencyScope.authenticated(command.admin(), IdempotentOperation.ADMIN_KILL_SWITCH),
                command.key(),
                digest(command.state().name() + "\n" + reason));
    }

    /** The stored answer carries no reason (it is in the history), only what a replay needs to know. */
    private static String stored(Outcome outcome) {
        return "{\"state\":\"" + outcome.snapshot().state() + "\",\"version\":"
                + outcome.snapshot().version() + ",\"changed\":" + outcome.changed() + "}";
    }

    private static String digest(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** One request of the administrator. */
    public record Command(SwitchState state, String reason, Principal admin, IdempotencyKey key, String correlationId) {

        public Command {
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(correlationId, "correlationId");
        }

        @Override
        public String toString() {
            // The reason never reaches a log (observability-design.md).
            return "Command[state=" + state + "]";
        }
    }

    /** What the request left in the store; {@code replayed} when the Idempotency-Key answered a previous request. */
    public record Outcome(KillSwitchSnapshot snapshot, boolean changed, boolean replayed) {}
}
