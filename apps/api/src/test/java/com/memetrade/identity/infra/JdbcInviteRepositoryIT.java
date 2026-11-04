package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.domain.invite.Invite;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.identity.domain.invite.InviteCursor;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Passo 6 (BR1.3, BR1.6, NFR2.2, NFR2.4, NFR4.4): every operation against PostgreSQL, including a real row lock. */
class JdbcInviteRepositoryIT extends AbstractU3IT {

    private static final Instant T = Instant.parse("2026-10-28T12:00:00Z");

    @Autowired
    private JdbcInviteRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void anInsertedInvitationIsFoundByItsDigestWithAndWithoutLock() {
        String code = TestInvites.newCode();
        Invite invite = newInvite(TestInvites.tenant(jdbc, "ACTIVE"), code, T.minus(Duration.ofHours(1)));

        repository.insert(invite);

        Invite found = repository.findByDigest(InviteCodes.digest(code)).orElseThrow();
        assertThat(found).isEqualTo(invite);
        assertThat(found.codeSuffix()).isEqualTo(code.substring(39));
        Optional<Invite> lockedByDigest =
                transactions.execute(s -> repository.findByDigestForUpdate(InviteCodes.digest(code)));
        Optional<Invite> lockedById = transactions.execute(s -> repository.findByIdForUpdate(invite.inviteId()));
        Optional<Invite> unknownId = transactions.execute(s -> repository.findByIdForUpdate(UUID.randomUUID()));
        assertThat(lockedByDigest).contains(invite);
        assertThat(lockedById).contains(invite);
        assertThat(repository.findByDigest(InviteCodes.digest(TestInvites.newCode())))
                .isEmpty();
        assertThat(unknownId).isEmpty();
    }

    @Test
    void noColumnOfTheInvitationHoldsTheCode() {
        String code = TestInvites.newCode();
        Invite invite = newInvite(TestInvites.tenant(jdbc, "ACTIVE"), code, T);
        repository.insert(invite);

        String row = jdbc.sql("select row_to_json(i)::text from invite i where invite_id = :id")
                .param("id", invite.inviteId())
                .query(String.class)
                .single();

        assertThat(row).doesNotContain(code).contains(InviteCodes.digest(code));
    }

    @Test
    void markUsedAndMarkRevokedChangeOnlyAPendingInvitation() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        Invite toUse = newInvite(tenant, TestInvites.newCode(), T);
        Invite toRevoke = newInvite(tenant, TestInvites.newCode(), T);
        repository.insert(toUse);
        repository.insert(toRevoke);
        UUID user = TestAccounts.betaUser(jdbc, TestWallet.generate().address()).userId();

        assertThat(repository.markUsed(toUse.inviteId(), user, T)).isTrue();
        assertThat(repository.markRevoked(toUse.inviteId(), T)).isFalse();
        assertThat(repository.markRevoked(toRevoke.inviteId(), T)).isTrue();
        assertThat(repository.markRevoked(toRevoke.inviteId(), T)).isFalse();
        assertThat(repository.markUsed(toRevoke.inviteId(), UUID.randomUUID(), T))
                .isFalse();

        Invite used = repository.findByDigest(toUse.codeDigest()).orElseThrow();
        assertThat(used.storedStatus()).isEqualTo(InviteStatus.USED);
        assertThat(used.usedAt()).isEqualTo(T);
        assertThat(used.usedByUserId()).isEqualTo(user);
        Invite revoked = repository.findByDigest(toRevoke.codeDigest()).orElseThrow();
        assertThat(revoked.storedStatus()).isEqualTo(InviteStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(T);
    }

    @Test
    void pagesAreOrderedByCreationDescendingWithTheIdAsTieBreakAndStableBetweenCalls() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        // Later than any other invitation of the shared test database, so these five are the first pages.
        Instant future = Instant.parse("2300-01-01T00:00:00Z");
        List<Invite> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Two invitations share the same creation instant: the id breaks the tie.
            Instant created = future.plusSeconds(i < 2 ? 0 : i);
            Invite invite = newInvite(tenant, TestInvites.newCode(), created.plus(Duration.ofDays(1)), created);
            repository.insert(invite);
            inserted.add(invite);
        }

        List<Invite> first = repository.page(Optional.empty(), 3);
        List<Invite> again = repository.page(Optional.empty(), 3);
        List<Invite> second = repository.page(Optional.of(InviteCursor.after(first.getLast())), 3);

        assertThat(first).isEqualTo(again).hasSize(3);
        assertThat(first).extracting(Invite::createdAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(second).doesNotContainAnyElementsOf(first);
        List<UUID> walked = new ArrayList<>();
        first.forEach(invite -> walked.add(invite.inviteId()));
        second.stream().limit(2).forEach(invite -> walked.add(invite.inviteId()));
        assertThat(walked)
                .containsExactlyInAnyOrderElementsOf(
                        inserted.stream().map(Invite::inviteId).toList());
        // PostgreSQL orders uuid by unsigned bytes, which is the order of the lower-case text (UUID.compareTo is
        // signed).
        List<UUID> tied = inserted.subList(0, 2).stream()
                .map(Invite::inviteId)
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
        assertThat(second.subList(0, 2)).extracting(Invite::inviteId).containsExactlyElementsOf(tied);
    }

    @Test
    void countsAreByDerivedStateAtTheGivenInstant() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        Map<InviteStatus, Long> before = repository.countByStatusAt(now);
        repository.insert(newInvite(tenant, TestInvites.newCode(), now));
        repository.insert(newInvite(tenant, TestInvites.newCode(), now.minusSeconds(1)));
        Invite revoked = newInvite(tenant, TestInvites.newCode(), now.plusSeconds(3600));
        repository.insert(revoked);
        repository.markRevoked(revoked.inviteId(), now.minusSeconds(10));

        Map<InviteStatus, Long> after = repository.countByStatusAt(now);

        assertThat(after.getOrDefault(InviteStatus.REVOKED, 0L) - before.getOrDefault(InviteStatus.REVOKED, 0L))
                .isEqualTo(1);
        assertThat(after.getOrDefault(InviteStatus.EXPIRED, 0L) - before.getOrDefault(InviteStatus.EXPIRED, 0L))
                .isEqualTo(1);
        assertThat(after.getOrDefault(InviteStatus.PENDING, 0L) - before.getOrDefault(InviteStatus.PENDING, 0L))
                .as("valid up to expiresAt inclusive")
                .isEqualTo(1);
    }

    @Test
    void aSecondTransactionWaitsForTheRowLockOfTheFirst() throws Exception {
        Invite invite = newInvite(TestInvites.tenant(jdbc, "ACTIVE"), TestInvites.newCode(), T);
        repository.insert(invite);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(s -> {
            repository.findByIdForUpdate(invite.inviteId());
            locked.countDown();
            await(release);
            repository.markRevoked(invite.inviteId(), T);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Invite> waiter = CompletableFuture.supplyAsync(() -> transactions.execute(
                s -> repository.findByDigestForUpdate(invite.codeDigest()).orElseThrow()));

        Thread.sleep(300);
        assertThat(waiter).as("blocked by the first transaction's lock").isNotDone();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(waiter.get(10, TimeUnit.SECONDS).storedStatus())
                .as("reads the state the first transaction committed")
                .isEqualTo(InviteStatus.REVOKED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Invite newInvite(UUID tenant, String code, Instant expiresAt) {
        return newInvite(tenant, code, expiresAt, expiresAt.minus(Duration.ofDays(1)));
    }

    private static Invite newInvite(UUID tenant, String code, Instant expiresAt, Instant createdAt) {
        return new Invite(
                UUID.randomUUID(),
                tenant,
                InviteCodes.digest(code),
                InviteCodes.suffix(code),
                InviteStatus.PENDING,
                "Adm1…wxyz",
                createdAt,
                expiresAt,
                null,
                null,
                null);
    }
}
