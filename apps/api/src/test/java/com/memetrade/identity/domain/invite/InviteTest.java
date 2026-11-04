package com.memetrade.identity.domain.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.api.InviteView;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 5 (BR1.2, NFR10.6, AC1.1.2, AC7.1.2): the derived state is exact at the limit, with the injected instant. */
@Tag("u3")
class InviteTest {

    private static final Instant T = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void aPendingInvitationIsValidAtExpiresAtInclusive() {
        Invite invite = invite(InviteStatus.PENDING, null, null, null);

        assertThat(invite.statusAt(T.minus(Duration.ofDays(1)))).isEqualTo(InviteStatus.PENDING);
        assertThat(invite.statusAt(T)).isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void oneSecondAfterExpiresAtAPendingInvitationReadsAsExpired() {
        Invite invite = invite(InviteStatus.PENDING, null, null, null);

        assertThat(invite.statusAt(T.plusSeconds(1))).isEqualTo(InviteStatus.EXPIRED);
        assertThat(invite.statusAt(T.plusNanos(1))).isEqualTo(InviteStatus.EXPIRED);
    }

    @Test
    void usedAndRevokedAreFinalAndNeverBecomeExpired() {
        Invite used = invite(InviteStatus.USED, T.minusSeconds(10), UUID.randomUUID(), null);
        Invite revoked = invite(InviteStatus.REVOKED, null, null, T.minusSeconds(10));

        assertThat(used.statusAt(T.plus(Duration.ofDays(400)))).isEqualTo(InviteStatus.USED);
        assertThat(revoked.statusAt(T.plus(Duration.ofDays(400)))).isEqualTo(InviteStatus.REVOKED);
    }

    @Test
    void expiredIsNeverAStoredState() {
        assertThatThrownBy(() -> invite(InviteStatus.EXPIRED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never stored");
    }

    @Test
    void theAdministratorViewCarriesTheDerivedStateTheDatesAndTheSuffixButNeverWhoIssuedIt() {
        Invite invite = invite(InviteStatus.PENDING, null, null, null);

        InviteView view = invite.viewAt(T.plusSeconds(1));

        assertThat(view.status()).isEqualTo(InviteStatus.EXPIRED);
        assertThat(view.inviteId()).isEqualTo(invite.inviteId());
        assertThat(view.tenantId()).isEqualTo(invite.tenantId());
        assertThat(view.expiresAt()).isEqualTo(T);
        assertThat(view.createdAt()).isEqualTo(T.minus(Duration.ofDays(2)));
        assertThat(view.codeSuffix()).isEqualTo("x9Zq");
        assertThat(view.usedAt()).isNull();
        assertThat(view.revokedAt()).isNull();
        assertThat(view.toString()).doesNotContain("Adm1");
    }

    private static Invite invite(InviteStatus status, Instant usedAt, UUID usedBy, Instant revokedAt) {
        return new Invite(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0".repeat(64),
                "x9Zq",
                status,
                "Adm1…wxyz",
                T.minus(Duration.ofDays(2)),
                T,
                usedAt,
                usedBy,
                revokedAt);
    }
}
