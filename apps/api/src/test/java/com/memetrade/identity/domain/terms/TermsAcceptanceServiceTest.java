package com.memetrade.identity.domain.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u3-reacceptance, written BEFORE {@link TermsAcceptanceService} from the rules (BR3.4, BR3.5, BR3.6;
 * NFR2.1, NFR8.2, NFR8.3; US4.2): accepting the effective current version records ONE {@code REACCEPTANCE} with the
 * instant of the clock and publishes {@code TERMS_ACCEPTED} by the person (version and source only); the same key or a
 * version already accepted answers the same result without recording; a version that is not the effective current one
 * is {@code 409 TERMS_VERSION_OUTDATED} with {@code currentVersion} and records nothing; tenant and person always come
 * from the principal.
 */
@Tag("u3-reacceptance")
class TermsAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryAcceptances store = new InMemoryAcceptances();
    private final FakeTerms terms = new FakeTerms("v2");
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final RecordingEvents events = new RecordingEvents();
    private final TermsAcceptanceService service = new TermsAcceptanceService(
            store,
            terms,
            new IdempotencyService(new InMemoryIdempotencyStore(), clock, Duration.ofHours(24)),
            audit,
            events,
            clock);

    private final Principal guest = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @Test
    void acceptingTheCurrentVersionRecordsOneReacceptanceWithTheClockAndAnswersUpToDate() {
        TermsAcceptanceService.Result result = service.accept(acceptance(guest, "v2", key()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(new TermsStatus("v2", "v2", NOW, true));
        assertThat(store.rows).hasSize(1);
        TermsAcceptanceStore.NewAcceptance row = store.rows.getFirst();
        assertThat(row.userId()).isEqualTo(guest.userId());
        assertThat(row.tenantId()).isEqualTo(guest.tenantId());
        assertThat(row.version()).isEqualTo("v2");
        assertThat(row.acceptedAt()).isEqualTo(NOW);
        assertThat(row.source()).isEqualTo(TermsAcceptanceSource.REACCEPTANCE);
        assertThat(events.accepted).containsExactly(TermsAcceptanceSource.REACCEPTANCE);
    }

    @Test
    void theInsertionPublishesTermsAcceptedByThePersonWithTheVersionAndTheSourceOnly() {
        service.accept(acceptance(guest, "v2", key()));

        List<AuditEventCommand> published = audit.withAction(AuditActions.TERMS_ACCEPTED);
        assertThat(published).hasSize(1);
        AuditEventCommand event = published.getFirst();
        assertThat(event.actorType()).isEqualTo(ActorType.PERSON);
        assertThat(event.actor()).isEqualTo(guest.userId().toString());
        assertThat(event.tenantId()).contains(guest.tenantId());
        assertThat(event.details()).isEqualTo(Map.of("version", "v2", "source", "REACCEPTANCE"));
        assertThat(event.wallet()).isNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(audit.events()).hasSize(1);
    }

    /** BR3.5: the same Idempotency-Key answers the stored result without a second row or event. */
    @Test
    void theSameKeyReplaysTheSameResultWithoutRecordingAgain() {
        IdempotencyKey key = key();
        TermsAcceptanceService.Result first = service.accept(acceptance(guest, "v2", key));
        clock.advance(Duration.ofMinutes(1));

        TermsAcceptanceService.Result again = service.accept(acceptance(guest, "v2", key));

        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(first.status());
        assertThat(store.rows).hasSize(1);
        assertThat(audit.events()).hasSize(1);
        assertThat(events.accepted).hasSize(1);
    }

    /** BR3.5: a version already accepted answers its existing status, with its original instant, and records nothing. */
    @Test
    void aVersionAlreadyAcceptedWithAnotherKeyAnswersTheExistingStatusWithoutRecording() {
        service.accept(acceptance(guest, "v2", key()));
        clock.advance(Duration.ofHours(3));

        TermsAcceptanceService.Result second = service.accept(acceptance(guest, "v2", key()));

        assertThat(second.replayed()).isFalse();
        assertThat(second.status()).isEqualTo(new TermsStatus("v2", "v2", NOW, true));
        assertThat(store.rows).hasSize(1);
        assertThat(audit.events()).hasSize(1);
        assertThat(events.accepted).hasSize(1);
    }

    /** NFR3.5: another request inserted the row first (the unique constraint answered "nothing inserted"). */
    @Test
    void whenAConcurrentAcceptanceWonTheRaceTheExistingRowIsAnsweredAndNoEventIsPublished() {
        Instant earlier = NOW.minusSeconds(1);
        store.rows.add(new TermsAcceptanceStore.NewAcceptance(
                UUID.randomUUID(),
                guest.userId(),
                guest.tenantId(),
                "v2",
                earlier,
                TermsAcceptanceSource.REACCEPTANCE));

        TermsAcceptanceService.Result result = service.accept(acceptance(guest, "v2", key()));

        assertThat(result.status()).isEqualTo(new TermsStatus("v2", "v2", earlier, true));
        assertThat(store.rows).hasSize(1);
        assertThat(audit.events()).isEmpty();
        assertThat(events.accepted).isEmpty();
    }

    /** BR3.6: a superseded version is 409 with the current version and records nothing. */
    @Test
    void aSupersededVersionIs409WithTheCurrentVersionAndRecordsNothing() {
        assertThatThrownBy(() -> service.accept(acceptance(guest, "v1", key())))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.TERMS_VERSION_OUTDATED);
                    assertThat(refused.code().status()).isEqualTo(409);
                    assertThat(refused.extensions()).containsEntry("currentVersion", "v2");
                });
        assertThat(store.rows).isEmpty();
        assertThat(audit.events()).isEmpty();
        assertThat(events.accepted).isEmpty();
    }

    @Test
    void aVersionAcceptedEarlierButNoLongerCurrentIsStill409() {
        store.rows.add(new TermsAcceptanceStore.NewAcceptance(
                UUID.randomUUID(), guest.userId(), guest.tenantId(), "v1", NOW, TermsAcceptanceSource.INVITE));

        assertThatThrownBy(() -> service.accept(acceptance(guest, "v1", key())))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.TERMS_VERSION_OUTDATED));
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void anUnknownOrFutureVersionIs409Too() {
        assertThatThrownBy(() -> service.accept(acceptance(guest, "v3", key())))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.extensions())
                        .containsEntry("currentVersion", "v2"));
        assertThat(store.rows).isEmpty();
    }

    /** A 409 is never stored: the key stays free for the acceptance of the current version. */
    @Test
    void a409DoesNotConsumeTheKey() {
        IdempotencyKey key = key();
        assertThatThrownBy(() -> service.accept(acceptance(guest, "v1", key))).isInstanceOf(ApiException.class);

        TermsAcceptanceService.Result result = service.accept(acceptance(guest, "v2", key));

        assertThat(result.replayed()).isFalse();
        assertThat(result.status().upToDate()).isTrue();
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherVersionIsAnIdempotencyConflict() {
        IdempotencyKey key = key();
        service.accept(acceptance(guest, "v2", key));
        terms.current = "v3";

        assertThatThrownBy(() -> service.accept(acceptance(guest, "v3", key)))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(store.rows).hasSize(1);
    }

    /** NFR2.1: tenant and person come from the principal; the key of one person never answers another one. */
    @Test
    void tenantAndPersonComeFromThePrincipalAndKeysAreScopedByPerson() {
        Principal other = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);
        IdempotencyKey shared = key();

        service.accept(acceptance(guest, "v2", shared));
        TermsAcceptanceService.Result otherResult = service.accept(acceptance(other, "v2", shared));

        assertThat(otherResult.replayed()).isFalse();
        assertThat(store.rows)
                .extracting(TermsAcceptanceStore.NewAcceptance::userId, TermsAcceptanceStore.NewAcceptance::tenantId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(guest.userId(), guest.tenantId()),
                        org.assertj.core.groups.Tuple.tuple(other.userId(), other.tenantId()));
        assertThat(audit.events())
                .extracting(AuditEventCommand::actor)
                .containsExactlyInAnyOrder(
                        guest.userId().toString(), other.userId().toString());
    }

    @Test
    void aNewCurrentVersionNeedsItsOwnAcceptance() {
        service.accept(acceptance(guest, "v2", key()));
        terms.current = "v3";
        clock.advance(Duration.ofDays(30));

        TermsAcceptanceService.Result result = service.accept(acceptance(guest, "v3", key()));

        assertThat(result.status()).isEqualTo(new TermsStatus("v3", "v3", NOW.plus(Duration.ofDays(30)), true));
        assertThat(store.rows)
                .extracting(TermsAcceptanceStore.NewAcceptance::version)
                .containsExactly("v2", "v3");
        assertThat(audit.events()).hasSize(2);
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    private static TermsAcceptanceService.Acceptance acceptance(Principal person, String version, IdempotencyKey key) {
        return new TermsAcceptanceService.Acceptance(person, version, key, "corr-1");
    }

    /** Emulates {@code INSERT ... ON CONFLICT (user_id, terms_version) DO NOTHING}. */
    static final class InMemoryAcceptances implements TermsAcceptanceStore {
        final List<NewAcceptance> rows = new ArrayList<>();

        @Override
        public synchronized boolean insertIfAbsent(NewAcceptance acceptance) {
            boolean exists = rows.stream()
                    .anyMatch(r -> r.userId().equals(acceptance.userId())
                            && r.version().equals(acceptance.version()));
            if (exists) {
                return false;
            }
            rows.add(acceptance);
            return true;
        }

        @Override
        public synchronized Optional<Instant> acceptedAt(UUID userId, UUID tenantId, String version) {
            return rows.stream()
                    .filter(r -> r.userId().equals(userId)
                            && r.tenantId().equals(tenantId)
                            && r.version().equals(version))
                    .map(NewAcceptance::acceptedAt)
                    .findFirst();
        }
    }

    static final class FakeTerms implements CurrentTerms {
        String current;

        FakeTerms(String current) {
            this.current = current;
        }

        @Override
        public TermsDocument current() {
            return new TermsDocument(current, "terms", "risk");
        }

        @Override
        public Optional<TermsDocument> find(String version) {
            return Optional.of(new TermsDocument(version, "terms", "risk"));
        }
    }

    static final class RecordingEvents implements TermsEvents {
        final List<TermsAcceptanceSource> accepted = new ArrayList<>();
        int blocked;

        @Override
        public void blocked() {
            blocked++;
        }

        @Override
        public void accepted(TermsAcceptanceSource source) {
            accepted.add(source);
        }
    }
}
