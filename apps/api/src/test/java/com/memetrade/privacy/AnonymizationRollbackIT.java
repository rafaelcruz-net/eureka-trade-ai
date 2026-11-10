package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.anonymization.AnonymizationEvents;
import com.memetrade.privacy.domain.anonymization.AnonymizationOutcomes;
import com.memetrade.privacy.domain.anonymization.AnonymizationService;
import com.memetrade.privacy.domain.anonymization.AnonymizationTimeouts;
import com.memetrade.privacy.domain.request.Actor;
import com.memetrade.privacy.domain.request.DataRequestEvents;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (NFR5.2; BR5.2; AC6.2.4): a failure injected by a test bean at
 * each of the five write points of F2 (J2, J3, J1, J6 and the conclusion of the request) AFTER the real write of that
 * point rolls everything back: the account stays ACTIVE with its wallet, the sessions, the bots, the comments and the
 * challenges stay as they were, the request stays IN_PROGRESS, no event leaves and the failure is counted as
 * ROLLED_BACK. The service is the production class over the real ports of the context, each point wrapped.
 */
class AnonymizationRollbackIT extends AbstractAnonymizationIT {

    enum Point {
        J2,
        J3,
        J1,
        J6,
        CONCLUSION
    }

    @Autowired
    private DataRequestStore store;

    @Autowired
    private IdempotencyGuard idempotency;

    @Autowired
    private AccountAnonymizationPort accounts;

    @Autowired
    private BotOwnerDataPort botsPort;

    @Autowired
    private FeedbackOwnerDataPort feedbackPort;

    @Autowired
    private AnonymizationEvents anonymizationEvents;

    @Autowired
    private DataRequestEvents requestEvents;

    @Autowired
    private AnonymizationOutcomes outcomes;

    @Autowired
    private AnonymizationTimeouts timeouts;

    @Autowired
    private MeterRegistry meters;

    @ParameterizedTest
    @EnumSource(Point.class)
    void aFailureAtEachWritePointRollsEverythingBack(Point point) throws Exception {
        Deletion deletion = deletionInProgress();
        Principal person = deletion.guest().principal();
        seedSessions(person, 2);
        UUID active = seedBot(person, "ACTIVE");
        UUID draft = seedBot(person, "DRAFT");
        seedFeedback(person, 2);
        List<Map<String, Object>> botsBefore = bots(deletion.userId());
        long challenges = challengesOf(deletion.wallet());
        double failuresBefore = failures("ROLLED_BACK");
        AnonymizationService failing = failingAt(point);

        assertThatThrownBy(() -> transaction()
                        .execute(status -> failing.anonymize(new AnonymizationService.Anonymize(
                                deletion.admin(),
                                UUID.fromString(deletion.requestId()),
                                AnonymizationService.CONFIRMATION,
                                IdempotencyKey.parse(UUID.randomUUID().toString())))))
                .isInstanceOf(InjectedFailure.class)
                .hasMessage(point.name());

        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
        Map<String, Object> wallet = walletRow(deletion.userId());
        assertThat(wallet.get("address")).isEqualTo(deletion.wallet());
        assertThat(wallet.get("provider")).isEqualTo("PHANTOM");
        assertThat(wallet.get("anonymized_at")).isNull();
        assertThat(activeSessions(deletion.userId())).isEqualTo(3);
        assertThat(bots(deletion.userId())).isEqualTo(botsBefore);
        assertThat(botState(active)).isEqualTo("ACTIVE");
        assertThat(botState(draft)).isEqualTo("DRAFT");
        assertThat(feedbackOf(deletion.userId())).isEqualTo(2);
        assertThat(challengesOf(deletion.wallet())).isEqualTo(challenges);
        assertThat(status(deletion.requestId())).isEqualTo("IN_PROGRESS");
        assertThat(anonymizedEvents(deletion.userId())).isZero();
        assertThat(events(AuditActions.DATA_REQUEST_TRANSITIONED, deletion.requestId()))
                .isEqualTo(1);
        assertThat(failures("ROLLED_BACK")).isEqualTo(failuresBefore + 1);
    }

    private double failures(String reason) {
        io.micrometer.core.instrument.Counter counter = meters.find("memetrade.account.anonymization.failures")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private AnonymizationService failingAt(Point point) {
        BotOwnerDataPort bots = point != Point.J2
                ? botsPort
                : new BotOwnerDataPort() {
                    @Override
                    public long countOwnerBots(UUID tenantId, UUID ownerUserId) {
                        return botsPort.countOwnerBots(tenantId, ownerUserId);
                    }

                    @Override
                    public int anonymizeOwnerBots(UUID tenantId, UUID ownerUserId) {
                        botsPort.anonymizeOwnerBots(tenantId, ownerUserId);
                        throw new InjectedFailure(Point.J2);
                    }
                };
        FeedbackOwnerDataPort feedback = point != Point.J3
                ? feedbackPort
                : new FeedbackOwnerDataPort() {
                    @Override
                    public long countByUser(UUID tenantId, UUID userId) {
                        return feedbackPort.countByUser(tenantId, userId);
                    }

                    @Override
                    public int deleteByUser(UUID tenantId, UUID userId) {
                        feedbackPort.deleteByUser(tenantId, userId);
                        throw new InjectedFailure(Point.J3);
                    }
                };
        AccountAnonymizationPort people = point != Point.J1
                ? accounts
                : new AccountAnonymizationPort() {
                    @Override
                    public Optional<AnonymizationTarget> target(UUID userId) {
                        return accounts.target(userId);
                    }

                    @Override
                    public AccountAnonymization anonymizeAccount(UUID tenantId, UUID userId, Instant now) {
                        accounts.anonymizeAccount(tenantId, userId, now);
                        throw new InjectedFailure(Point.J1);
                    }

                    @Override
                    public Set<UUID> anonymizedAmong(Collection<UUID> userIds) {
                        return accounts.anonymizedAmong(userIds);
                    }
                };
        AnonymizationEvents events = point != Point.J6
                ? anonymizationEvents
                : (request, result, admin) -> {
                    anonymizationEvents.accountAnonymized(request, result, admin);
                    throw new InjectedFailure(Point.J6);
                };
        DataRequestEvents conclusion = point != Point.CONCLUSION ? requestEvents : new ConcludeThenFail(requestEvents);
        return new AnonymizationService(
                store, idempotency, people, bots, feedback, events, conclusion, outcomes, timeouts, clock);
    }

    /** The failure of the test bean, after the real write of its point. */
    static final class InjectedFailure extends RuntimeException {
        InjectedFailure(Point point) {
            super(point.name());
        }
    }

    private record ConcludeThenFail(DataRequestEvents delegate) implements DataRequestEvents {
        @Override
        public void created(DataSubjectRequest request, Actor actor) {
            delegate.created(request, actor);
        }

        @Override
        public void transitioned(DataSubjectRequest request, DataRequestStatus from, Actor actor) {
            delegate.transitioned(request, from, actor);
            throw new InjectedFailure(Point.CONCLUSION);
        }

        @Override
        public void verificationStarted(DataSubjectRequest request, Actor actor) {
            delegate.verificationStarted(request, actor);
        }

        @Override
        public void verified(DataSubjectRequest request, Actor actor) {
            delegate.verified(request, actor);
        }

        @Override
        public void overdue(DataSubjectRequest request) {
            delegate.overdue(request);
        }
    }
}
