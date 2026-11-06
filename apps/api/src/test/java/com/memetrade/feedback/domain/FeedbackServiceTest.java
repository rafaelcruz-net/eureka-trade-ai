package com.memetrade.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.InMemoryIdempotencyStore;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of U8, written BEFORE {@link FeedbackService} from the rules (BR6.1 to BR6.4, AC6.1.2, AC6.1.3, NFR4.2,
 * NFR4.3): 1 to 2000 code points, screen S1 to S9, tenant and person of the token, at most 10 per person and hour with
 * the lock taken before the count, and deduplication by the Idempotency-Key where 400 and 429 are never stored.
 */
@Tag("u8")
class FeedbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryFeedbackStore store = new InMemoryFeedbackStore();
    private final InMemoryIdempotencyStore keys = new InMemoryIdempotencyStore();
    private final RecordingEvents events = new RecordingEvents();
    private final FeedbackService service =
            new FeedbackService(store, new IdempotencyService(keys, clock, Duration.ofHours(24)), events, clock, 10);
    private final Principal guest = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @Test
    void aValidCommentIsStoredWithTheTenantAndPersonOfTheToken() {
        FeedbackService.Receipt receipt = service.submit(submission("S3", "A lista demorou a atualizar."));

        assertThat(receipt.replayed()).isFalse();
        assertThat(receipt.createdAt()).isEqualTo(NOW);
        assertThat(store.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.feedbackId()).isEqualTo(receipt.feedbackId());
            assertThat(entry.tenantId()).isEqualTo(guest.tenantId());
            assertThat(entry.userId()).isEqualTo(guest.userId());
            assertThat(entry.screen()).isEqualTo(FeedbackScreen.S3);
        });
        assertThat(events.received).containsExactly(FeedbackScreen.S3);
    }

    /** BR6.1: the text is kept exactly as sent, markup and surrounding spaces included (shown as text later). */
    @Test
    void theTextIsKeptExactlyAsSent() {
        String text = "  <script>alert('x')</script> **negrito**  ";

        service.submit(submission("S9", text));

        assertThat(store.entries().getFirst().text()).isEqualTo(text);
    }

    /** BR6.1, AC6.1.3: 1 character and 2000 code points are accepted (the edge cases of the functional spec). */
    @Test
    void oneAndTwoThousandCodePointsAreAccepted() {
        service.submit(submission("S1", "x"));
        service.submit(submission("S2", "😀".repeat(2000)));

        assertThat(store.entries()).hasSize(2);
    }

    /** BR6.1: 2001 code points are 400 on the field text, and nothing is written. */
    @Test
    void moreThanTwoThousandCodePointsAreRefused() {
        assertValidation(() -> service.submit(submission("S1", "😀".repeat(2001))), "text");
        assertThat(store.entries()).isEmpty();
        assertThat(events.refused).containsExactly(FeedbackEvents.Refusal.VALIDATION);
    }

    /** BR6.1: empty after trimming, or missing, is 400 on the field text. */
    @Test
    void aBlankTextIsRefused() {
        assertValidation(() -> service.submit(submission("S1", " \n\t ")), "text");
        assertValidation(() -> service.submit(submission("S1", null)), "text");
    }

    /** BR6.1: the screen is exactly S1 to S9. */
    @Test
    void theScreenIsOneOfTheNineScreens() {
        for (String screen : List.of("S1", "S5", "S9")) {
            service.submit(submission(screen, "ok " + screen));
        }
        for (String screen : new String[] {"S0", "S10", "s1", "A1", "", null}) {
            assertValidation(() -> service.submit(submission(screen, "texto")), "screen");
        }
        assertThat(store.entries()).hasSize(3);
    }

    /** BR6.3: the 11th comment of the hour is 429 RATE_LIMITED with Retry-After until the oldest leaves the window. */
    @Test
    void theEleventhCommentOfTheHourIsRateLimited() {
        for (int i = 0; i < 10; i++) {
            service.submit(submission("S2", "comentário " + i));
            clock.advance(Duration.ofMinutes(1));
        }

        assertThatThrownBy(() -> service.submit(submission("S2", "o décimo primeiro")))
                .isInstanceOfSatisfying(ApiException.class, limited -> {
                    assertThat(limited.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    // The oldest is 10 minutes old: it leaves the window in 50 minutes.
                    assertThat(limited.headers()).containsEntry("Retry-After", "3000");
                });
        assertThat(store.entries()).hasSize(10);
        assertThat(events.refused).containsExactly(FeedbackEvents.Refusal.RATE_LIMITED);
    }

    /** BR6.3: one hour after a comment, it no longer counts. */
    @Test
    void theWindowIsTheLastHour() {
        for (int i = 0; i < 10; i++) {
            service.submit(submission("S2", "comentário " + i));
        }
        clock.advance(Duration.ofHours(1));

        FeedbackService.Receipt later = service.submit(submission("S2", "uma hora depois"));

        assertThat(later.replayed()).isFalse();
        assertThat(store.entries()).hasSize(11);
    }

    /** NFR4.3: the person is locked BEFORE the count, so simultaneous submissions never pass the limit together. */
    @Test
    void thePersonIsLockedBeforeTheCount() {
        service.submit(submission("S4", "trava"));

        assertThat(store.calls()).containsExactly("lock", "count", "insert");
    }

    /** BR6.4: the same key and body replay the same 201 and never add a row nor count again. */
    @Test
    void theSameKeyAndBodyReplayTheSameAnswer() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        FeedbackService.Receipt first = service.submit(submission("S6", "mesmo texto", key));

        FeedbackService.Receipt again = service.submit(submission("S6", "mesmo texto", key));

        assertThat(again.replayed()).isTrue();
        assertThat(again.feedbackId()).isEqualTo(first.feedbackId());
        assertThat(again.createdAt()).isEqualTo(first.createdAt());
        assertThat(store.entries()).hasSize(1);
        assertThat(events.received).hasSize(1);
    }

    /** BR6.4: the same key with another body is 409 IDEMPOTENCY_CONFLICT. */
    @Test
    void theSameKeyWithAnotherBodyIsAConflict() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        service.submit(submission("S6", "primeiro texto", key));

        assertThatThrownBy(() -> service.submit(submission("S6", "outro texto", key)))
                .isInstanceOfSatisfying(ApiException.class, conflict -> assertThat(conflict.code())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(store.entries()).hasSize(1);
        assertThat(events.refused).containsExactly(FeedbackEvents.Refusal.IDEMPOTENCY_CONFLICT);
    }

    /** BR6.3: a 400 is not stored for its key; the corrected body with the same key is processed. */
    @Test
    void aRefusedBodyIsNotStoredForItsKey() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        assertValidation(() -> service.submit(submission("S1", "", key)), "text");

        FeedbackService.Receipt fixed = service.submit(submission("S1", "corrigido", key));

        assertThat(fixed.replayed()).isFalse();
        assertThat(store.entries()).hasSize(1);
    }

    /** BR6.3: a 429 is not stored either; after the window the same key and body are processed again. */
    @Test
    void aRateLimitedSubmissionIsProcessedAgainAfterTheWindow() {
        for (int i = 0; i < 10; i++) {
            service.submit(submission("S2", "comentário " + i));
        }
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        assertThatThrownBy(() -> service.submit(submission("S2", "espera", key)))
                .isInstanceOf(ApiException.class);
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        FeedbackService.Receipt accepted = service.submit(submission("S2", "espera", key));

        assertThat(accepted.replayed()).isFalse();
        assertThat(store.entries()).hasSize(11);
    }

    /** The limit is per person: another person of the same tenant is not affected. */
    @Test
    void theLimitIsPerPerson() {
        for (int i = 0; i < 10; i++) {
            service.submit(submission("S2", "comentário " + i));
        }
        Principal colleague = new Principal(guest.tenantId(), UUID.randomUUID(), Role.BETA_USER);

        service.submit(
                new FeedbackService.Submission(colleague, "S2", "colega", new IdempotencyKey(UUID.randomUUID())));

        assertThat(store.entries()).hasSize(11);
    }

    private FeedbackService.Submission submission(String screen, String text) {
        return submission(screen, text, new IdempotencyKey(UUID.randomUUID()));
    }

    private FeedbackService.Submission submission(String screen, String text, IdempotencyKey key) {
        return new FeedbackService.Submission(guest, screen, text, key);
    }

    private static void assertValidation(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.fields()).anyMatch(violation -> violation.field().equals(field));
        });
    }

    private static final class RecordingEvents implements FeedbackEvents {
        private final List<FeedbackScreen> received = new ArrayList<>();
        private final List<Refusal> refused = new ArrayList<>();

        @Override
        public void received(FeedbackScreen screen) {
            received.add(screen);
        }

        @Override
        public void refused(Refusal reason) {
            refused.add(reason);
        }
    }
}
