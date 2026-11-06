package com.memetrade.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.InMemoryIdempotencyStore;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 5 of U8, written BEFORE {@link KillSwitchService} from the rules (BR2.1, BR2.2, BR2.4, BR2.6, R-03 of the NFR
 * Design): the transitions of the state machine, the repetition without history, the reason of 1 to 500 characters and
 * the Idempotency-Key through the common mechanism of the identity module.
 */
@Tag("u8")
class KillSwitchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryKillSwitchStore store = new InMemoryKillSwitchStore();
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final InMemoryIdempotencyStore keys = new InMemoryIdempotencyStore();
    private final KillSwitchService service = new KillSwitchService(
            store, () -> 7L, audit, new IdempotencyService(keys, clock, Duration.ofHours(24)), clock);
    private final Principal admin = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);

    /** BR2.1: OFF to ON writes the state, one history row and one KILL_SWITCH_ON by the administrator, no tenant. */
    @Test
    void switchingOnWritesTheStateTheHistoryAndOneEvent() {
        KillSwitchService.Outcome outcome = service.change(command(SwitchState.ON, "Fonte simulada instável"));

        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.snapshot().state()).isEqualTo(SwitchState.ON);
        assertThat(outcome.snapshot().version()).isEqualTo(1);
        assertThat(outcome.snapshot().changedBy()).contains(admin.userId());
        assertThat(outcome.snapshot().changedAt()).contains(NOW);
        assertThat(outcome.snapshot().reason()).contains("Fonte simulada instável");
        assertThat(store.history()).singleElement().satisfies(change -> {
            assertThat(change.from()).isEqualTo(SwitchState.OFF);
            assertThat(change.to()).isEqualTo(SwitchState.ON);
            assertThat(change.version()).isEqualTo(1);
            assertThat(change.adminUserId()).isEqualTo(admin.userId());
            assertThat(change.correlationId()).isEqualTo("corr-1");
        });
        AuditEventCommand event = audit.withAction("KILL_SWITCH_ON").getFirst();
        assertThat(audit.events()).hasSize(1);
        assertThat(event.actorType()).isEqualTo(ActorType.ADMIN);
        assertThat(event.actor()).isEqualTo(admin.userId().toString());
        assertThat(event.tenantId()).isEmpty();
        assertThat(event.details())
                .containsEntry("reason", "Fonte simulada instável")
                .containsEntry("version", "1")
                .containsEntry("activeBotsToPause", "7");
    }

    /** BR2.4: ON again writes no history and no event; the version does not move. */
    @Test
    void repeatingOnChangesNothing() {
        service.change(command(SwitchState.ON, "primeiro"));

        KillSwitchService.Outcome again = service.change(command(SwitchState.ON, "segundo"));

        assertThat(again.changed()).isFalse();
        assertThat(again.snapshot().version()).isEqualTo(1);
        assertThat(again.snapshot().reason()).contains("primeiro");
        assertThat(store.history()).hasSize(1);
        assertThat(audit.events()).hasSize(1);
    }

    /** BR2.2: ON to OFF records OFF, a second history row and KILL_SWITCH_OFF, and clears the pending bots. */
    @Test
    void switchingOffClearsThePendingBotsAndRecordsTheChange() {
        service.change(command(SwitchState.ON, "acionado"));
        store.force(4, false);
        clock.advance(Duration.ofMinutes(5));

        KillSwitchService.Outcome off = service.change(command(SwitchState.OFF, "fonte voltou"));

        assertThat(off.changed()).isTrue();
        assertThat(off.snapshot().state()).isEqualTo(SwitchState.OFF);
        assertThat(off.snapshot().pendingBots()).isZero();
        assertThat(off.snapshot().version()).isEqualTo(2);
        assertThat(off.snapshot().changedAt()).contains(NOW.plus(Duration.ofMinutes(5)));
        assertThat(store.history()).extracting(KillSwitchChange::to).containsExactly(SwitchState.ON, SwitchState.OFF);
        assertThat(audit.withAction("KILL_SWITCH_OFF")).singleElement().satisfies(event -> assertThat(event.details())
                .containsEntry("version", "2"));
    }

    /** State machine: OFF to OFF has no effect at all. */
    @Test
    void offWhenAlreadyOffHasNoEffect() {
        KillSwitchService.Outcome outcome = service.change(command(SwitchState.OFF, "nada a fazer"));

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.snapshot()).isEqualTo(KillSwitchSnapshot.initial());
        assertThat(store.history()).isEmpty();
        assertThat(audit.events()).isEmpty();
    }

    /** Every change locks the single row before reading it (BR2.8 first level). */
    @Test
    void everyChangeLocksTheRow() {
        service.change(command(SwitchState.ON, "trava"));
        service.change(command(SwitchState.ON, "trava de novo"));

        assertThat(store.locks()).isEqualTo(2);
    }

    /** BR2.6: an empty reason after trimming is 400 on the field reason, and nothing is written. */
    @Test
    void aBlankReasonIsRefused() {
        assertValidation(() -> service.change(command(SwitchState.ON, "   \t ")), "reason");
        assertValidation(() -> service.change(command(SwitchState.ON, null)), "reason");
        assertThat(store.history()).isEmpty();
        assertThat(audit.events()).isEmpty();
    }

    /** BR2.6: 500 characters are accepted, 501 are refused; characters are code points. */
    @Test
    void theReasonHasAtMostFiveHundredCharacters() {
        assertValidation(() -> service.change(command(SwitchState.ON, "a".repeat(501))), "reason");

        KillSwitchService.Outcome emoji = service.change(command(SwitchState.ON, "😀".repeat(500)));

        assertThat(emoji.changed()).isTrue();
    }

    /** BR2.6: the state is ON or OFF; missing is 400 on the field state. */
    @Test
    void theStateIsRequired() {
        assertValidation(() -> service.change(command(null, "motivo")), "state");
    }

    /** R-03: the same key and body replay the first answer without a new history row or event. */
    @Test
    void theSameKeyAndBodyReplayWithoutANewChange() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        service.change(command(SwitchState.ON, "mesmo pedido", key));
        service.change(command(SwitchState.OFF, "outro pedido"));

        KillSwitchService.Outcome replay = service.change(command(SwitchState.ON, "mesmo pedido", key));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.changed()).isFalse();
        assertThat(store.history()).hasSize(2);
        assertThat(replay.snapshot().state()).isEqualTo(SwitchState.OFF);
    }

    /** R-03: the same key with another body is 409 IDEMPOTENCY_CONFLICT. */
    @Test
    void theSameKeyWithAnotherBodyIsAConflict() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        service.change(command(SwitchState.ON, "primeiro", key));

        assertThatThrownBy(() -> service.change(command(SwitchState.OFF, "primeiro", key)))
                .isInstanceOfSatisfying(ApiException.class, conflict -> assertThat(conflict.code())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(store.history()).hasSize(1);
    }

    /** A refused body is never stored: the same key with a valid body is processed afterwards. */
    @Test
    void aRefusedRequestIsNotStoredForItsKey() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        assertValidation(() -> service.change(command(SwitchState.ON, "", key)), "reason");

        KillSwitchService.Outcome valid = service.change(command(SwitchState.ON, "agora válido", key));

        assertThat(valid.changed()).isTrue();
        assertThat(valid.replayed()).isFalse();
    }

    /** The reason is stored trimmed; the history keeps it like the state. */
    @Test
    void theReasonIsTrimmed() {
        KillSwitchService.Outcome outcome = service.change(command(SwitchState.ON, "  com espaços  "));

        assertThat(outcome.snapshot().reason()).contains("com espaços");
        assertThat(store.history().getFirst().reason()).isEqualTo("com espaços");
    }

    /** ON, OFF, ON: the versions follow 1, 2, 3 and each change has its own event. */
    @Test
    void theVersionsFollowTheChanges() {
        service.change(command(SwitchState.ON, "um"));
        service.change(command(SwitchState.OFF, "dois"));
        KillSwitchService.Outcome third = service.change(command(SwitchState.ON, "três"));

        assertThat(third.snapshot().version()).isEqualTo(3);
        assertThat(store.history()).extracting(KillSwitchChange::version).containsExactly(1L, 2L, 3L);
        assertThat(audit.withAction("KILL_SWITCH_ON")).hasSize(2);
        assertThat(audit.withAction("KILL_SWITCH_OFF")).hasSize(1);
    }

    private KillSwitchService.Command command(SwitchState state, String reason) {
        return command(state, reason, new IdempotencyKey(UUID.randomUUID()));
    }

    private KillSwitchService.Command command(SwitchState state, String reason, IdempotencyKey key) {
        return new KillSwitchService.Command(state, reason, admin, key, "corr-1");
    }

    private static void assertValidation(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.fields()).anyMatch(violation -> violation.field().equals(field));
        });
    }
}
