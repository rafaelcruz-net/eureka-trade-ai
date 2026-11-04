package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.IdentityCounts;
import com.memetrade.identity.api.InvitePage;
import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.api.InviteView;
import com.memetrade.identity.api.IssuedInvite;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 7 (F1, F2, F3, F9; BR1.1 to BR1.8; NFR4.5, NFR6.3, NFR6.10, NFR9.4, NFR10.6) through the C17 interface, with
 * the real PostgreSQL, publisher and clock. The HTTP composition with the idempotency key (code only in the first
 * answer, missing key 400) is covered by {@code AdminInviteRouteIT} (Passo 13).
 */
class InviteAdministrationIT extends AbstractU3IT {

    private static final Principal ADMIN = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);

    @Autowired
    private IdentityAdministration administration;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void anInvitationIsIssuedPendingForAnActiveTenantWithOnlyTheDigestStoredAndOneEvent() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        Instant expiresAt = clock.instant().plus(Duration.ofDays(7));

        IssuedInvite issued = inTransaction(() -> administration.issue(tenant, expiresAt, ADMIN));

        InviteView invite = issued.invite();
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
        assertThat(invite.tenantId()).isEqualTo(tenant);
        assertThat(invite.expiresAt()).isEqualTo(expiresAt);
        assertThat(invite.createdAt()).isEqualTo(clock.instant());
        assertThat(issued.code()).hasSize(43).endsWith(invite.codeSuffix());
        assertThat(issued.toString()).doesNotContain(issued.code());
        String row = jdbc.sql("select row_to_json(i)::text from invite i where invite_id = :id")
                .param("id", invite.inviteId())
                .query(String.class)
                .single();
        assertThat(row)
                .doesNotContain(issued.code())
                .contains(InviteCodes.digest(issued.code()))
                .contains(WalletAddress.mask(ADMIN_WALLET.address()));
        assertThat(OutboxEvents.count(jdbc, AuditActions.INVITE_ISSUED, "invite:" + invite.inviteId()))
                .isEqualTo(1);
        assertThat(OutboxEvents.payloads(jdbc, AuditActions.INVITE_ISSUED))
                .noneMatch(payload -> payload.contains(issued.code()));
    }

    @Test
    void exactlyOneHourAndExactlyThirtyDaysAreAcceptedAndOneSecondLessOrMoreIsRefused() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        Instant now = clock.instant();

        assertThat(inTransaction(() -> administration.issue(tenant, now.plus(Duration.ofHours(1)), ADMIN))
                        .invite()
                        .status())
                .isEqualTo(InviteStatus.PENDING);
        assertThat(inTransaction(() -> administration.issue(tenant, now.plus(Duration.ofDays(30)), ADMIN))
                        .invite()
                        .status())
                .isEqualTo(InviteStatus.PENDING);
        assertUnprocessable(
                () -> administration.issue(tenant, now.plus(Duration.ofHours(1)).minusSeconds(1), ADMIN), "expiresAt");
        assertUnprocessable(
                () -> administration.issue(tenant, now.plus(Duration.ofDays(30)).plusSeconds(1), ADMIN), "expiresAt");
        assertUnprocessable(() -> administration.issue(tenant, null, ADMIN), "expiresAt");
    }

    @Test
    void anUnknownOrSuspendedTenantIsRefusedOnTenantIdAndNothingIsCreated() {
        UUID suspended = TestInvites.tenant(jdbc, "SUSPENDED");
        Instant expiresAt = clock.instant().plus(Duration.ofDays(1));
        long before = invitesOf(suspended);

        assertUnprocessable(() -> administration.issue(suspended, expiresAt, ADMIN), "tenantId");
        assertUnprocessable(() -> administration.issue(UUID.randomUUID(), expiresAt, ADMIN), "tenantId");
        assertUnprocessable(() -> administration.issue(null, expiresAt, ADMIN), "tenantId");
        assertThat(invitesOf(suspended)).isEqualTo(before);
    }

    @Test
    void theSeededTenantBetaIsAmongTheActiveTenantsOfTheForm() {
        assertThat(administration.activeTenants(ADMIN)).anySatisfy(option -> {
            assertThat(option.tenantId()).isEqualTo(TestInvites.BETA_TENANT);
            assertThat(option.name()).isEqualTo("Beta");
        });
        UUID suspended = TestInvites.tenant(jdbc, "SUSPENDED");
        assertThat(administration.activeTenants(ADMIN))
                .noneMatch(option -> option.tenantId().equals(suspended));
    }

    @Test
    void revokingAPendingInvitationWritesOneEventAndRevokingAgainReturnsItWithoutANewOne() {
        TestInvites.Created created = TestInvites.pending(jdbc).insert();
        String target = "invite:" + created.inviteId();

        InviteView first = inTransaction(() -> administration.revoke(created.inviteId(), ADMIN));
        InviteView second = inTransaction(() -> administration.revoke(created.inviteId(), ADMIN));

        assertThat(first.status()).isEqualTo(InviteStatus.REVOKED);
        assertThat(first.revokedAt()).isEqualTo(clock.instant());
        assertThat(second).isEqualTo(first);
        assertThat(OutboxEvents.count(jdbc, AuditActions.INVITE_REVOKED, target))
                .isEqualTo(1);
    }

    @Test
    void anExpiredOrUsedInvitationIsNotRevocableAndAnUnknownOneIs404() {
        TestInvites.Created expired = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().minusSeconds(1))
                .insert();
        UUID usedId = usedInvitation();

        assertThatThrownBy(() -> inTransaction(() -> administration.revoke(expired.inviteId(), ADMIN)))
                .isInstanceOfSatisfying(ApiException.class, problem -> {
                    assertThat(problem.code()).isEqualTo(ErrorCode.INVITE_NOT_REVOCABLE);
                    assertThat(problem.reason()).isEqualTo("EXPIRED");
                });
        assertThatThrownBy(() -> inTransaction(() -> administration.revoke(usedId, ADMIN)))
                .isInstanceOfSatisfying(ApiException.class, problem -> {
                    assertThat(problem.code()).isEqualTo(ErrorCode.INVITE_NOT_REVOCABLE);
                    assertThat(problem.reason()).isEqualTo("USED");
                });
        assertThatThrownBy(() -> inTransaction(() -> administration.revoke(UUID.randomUUID(), ADMIN)))
                .isInstanceOfSatisfying(ApiException.class, problem -> assertThat(problem.code())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(OutboxEvents.count(jdbc, AuditActions.INVITE_REVOKED, "invite:" + expired.inviteId()))
                .isZero();
    }

    @Test
    void theInvitationAtItsExpiryInstantIsStillRevocableAndOneSecondLaterIsNot() {
        Instant expiresAt = clock.instant().plus(Duration.ofHours(2));
        TestInvites.Created atLimit =
                TestInvites.pending(jdbc).expiresAt(expiresAt).insert();
        TestInvites.Created afterLimit =
                TestInvites.pending(jdbc).expiresAt(expiresAt).insert();

        clock.set(expiresAt);
        assertThat(inTransaction(() -> administration.revoke(atLimit.inviteId(), ADMIN))
                        .status())
                .isEqualTo(InviteStatus.REVOKED);
        clock.set(expiresAt.plusSeconds(1));
        assertThatThrownBy(() -> inTransaction(() -> administration.revoke(afterLimit.inviteId(), ADMIN)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void theListDerivesEveryStateWithOneInstantAndNeverCarriesTheCodeNorWhoIssuedIt() {
        // A creation instant later than the other invitations of the shared database keeps it on the first page.
        clock.set(Instant.parse("2250-01-01T00:00:00Z"));
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        IssuedInvite issued =
                inTransaction(() -> administration.issue(tenant, clock.instant().plus(Duration.ofHours(1)), ADMIN));
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        InvitePage page = administration.list(Optional.empty(), 100, ADMIN);

        assertThat(page.items())
                .filteredOn(item -> item.inviteId().equals(issued.invite().inviteId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(InviteStatus.EXPIRED);
                    assertThat(item.codeSuffix()).isEqualTo(issued.invite().codeSuffix());
                    assertThat(item.toString()).doesNotContain(issued.code()).doesNotContain("…");
                });
    }

    @Test
    void theListLimitMustBeBetween1And100AndTheCursorMustComeFromTheList() {
        for (int limit : new int[] {0, 101, -1}) {
            assertThatThrownBy(() -> administration.list(Optional.empty(), limit, ADMIN))
                    .isInstanceOfSatisfying(ApiException.class, problem -> {
                        assertThat(problem.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(problem.fields()).extracting("field").containsExactly("limit");
                    });
        }
        assertThatThrownBy(() -> administration.list(Optional.of("not-a-cursor!"), 20, ADMIN))
                .isInstanceOf(ApiException.class);
        TestInvites.pending(jdbc).insert();
        TestInvites.pending(jdbc).insert();
        InvitePage first = administration.list(Optional.empty(), 1, ADMIN);
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotNull();
        InvitePage second = administration.list(Optional.of(first.nextCursor()), 1, ADMIN);
        assertThat(second.items()).hasSize(1).doesNotContainAnyElementsOf(first.items());
    }

    @Test
    void countsHaveTheFourKeysAndOnlyNumbers() {
        TestAccounts.betaUser(jdbc, TestWallet.generate().address());

        IdentityCounts counts = administration.counts(ADMIN);

        assertThat(counts.inviteCounts()).containsOnlyKeys(InviteStatus.values());
        assertThat(counts.guestCount()).isPositive();
        assertThat(new IdentityCounts(0, null).inviteCounts()).containsOnlyKeys(InviteStatus.values());
        assertThat(new IdentityCounts(0, null).inviteCounts().values()).containsOnly(0L);
    }

    @Test
    void theInterfaceRefusesAPrincipalThatIsNotAnAdministratorWhateverTheRoute() {
        Principal guest = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        Instant expiresAt = clock.instant().plus(Duration.ofDays(1));

        for (Runnable call : new Runnable[] {
            () -> inTransaction(() -> administration.issue(tenant, expiresAt, guest)),
            () -> inTransaction(() -> administration.revoke(UUID.randomUUID(), guest)),
            () -> administration.list(Optional.empty(), 20, guest),
            () -> administration.counts(guest),
            () -> administration.activeTenants(guest),
            () -> administration.counts(null)
        }) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ApiException.class, problem -> assertThat(problem.code())
                            .isEqualTo(ErrorCode.FORBIDDEN_ROLE));
        }
        assertThat(invitesOf(tenant)).isZero();
    }

    @Test
    void issuanceAndRevocationRequireTheCallersTransaction() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");

        assertThatThrownBy(() -> administration.issue(tenant, clock.instant().plus(Duration.ofDays(1)), ADMIN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> administration.revoke(UUID.randomUUID(), ADMIN))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void tenAdministrativeActionsInARowStayWithinTheBudget() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        long started = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            IssuedInvite issued = inTransaction(
                    () -> administration.issue(tenant, clock.instant().plus(Duration.ofDays(1)), ADMIN));
            inTransaction(() -> administration.revoke(issued.invite().inviteId(), ADMIN));
            administration.list(Optional.empty(), 100, ADMIN);
        }
        long averageMillis = (System.nanoTime() - started) / 1_000_000 / 30;

        // NFR3.3: p95 <= 300 ms per action; the average of 30 actions far below it is a sanity bound, not the p95.
        assertThat(averageMillis).isLessThan(300);
    }

    private UUID usedInvitation() {
        TestAccounts.Seeded account =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        TestInvites.Created created =
                TestInvites.pending(jdbc).tenant(account.tenantId()).insert();
        jdbc.sql("update invite set status = 'USED', used_at = now(), used_by_user_id = :u where invite_id = :id")
                .param("u", account.userId())
                .param("id", created.inviteId())
                .update();
        return created.inviteId();
    }

    private long invitesOf(UUID tenant) {
        Long count = jdbc.sql("select count(*) from invite where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private void assertUnprocessable(Supplier<?> call, String field) {
        assertThatThrownBy(() -> inTransaction(call)).isInstanceOfSatisfying(ApiException.class, problem -> {
            assertThat(problem.code()).isEqualTo(ErrorCode.UNPROCESSABLE_FIELDS);
            assertThat(problem.code().status()).isEqualTo(422);
            assertThat(problem.code().code()).isEqualTo("VALIDATION_FAILED");
            assertThat(problem.fields()).extracting("field").containsExactly(field);
        });
    }

    private <T> T inTransaction(Supplier<T> call) {
        return transactions.execute(status -> call.get());
    }
}
