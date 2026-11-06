package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditBacklog;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.riskcontrol.api.RejectionCounts;
import com.memetrade.shared.error.ApiException;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.RiskRows;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 3 of U8 (contract-summary.md, C16/C17 additions): the new public interfaces of U6 ({@link RejectionCounts}), U2
 * ({@link AuditBacklog}) and the two additions to {@link IdentityAdministration} answer only numbers, tenant names and
 * the masked wallet, and the identity additions refuse any role other than ADMIN.
 */
class U8ContractsIT extends AbstractU8IT {

    @Autowired
    private RejectionCounts rejections;

    @Autowired
    private AuditBacklog backlog;

    @Autowired
    private IdentityAdministration identity;

    /** BR3.2: every tenant counts, only rows at or after the instant, only the number. */
    @Test
    void theRejectionCountIsGlobalAndStartsAtTheInstant() {
        Instant from = databaseNow().minusSeconds(1);
        long before = rejections.countRejectedSince(from);
        RiskRows rows = new RiskRows(jdbc);
        Principal one = newPerson();
        Principal other = newPerson();
        UUID botOne = UUID.randomUUID();
        UUID botOther = UUID.randomUUID();
        rows.rejection(one, botOne, rows.evaluation(one, botOne, "REJECTED", "0 seconds"), "ORDER_SIZE");
        rows.rejection(other, botOther, rows.evaluation(other, botOther, "REJECTED", "0 seconds"), "SLIPPAGE");

        assertThat(rejections.countRejectedSince(from)).isEqualTo(before + 2);
        assertThat(rejections.countRejectedSince(databaseNow().plusSeconds(3600)))
                .isZero();
    }

    /** BR3.1: the pending events of the outbox, the same number the relay measures. */
    @Test
    void theAuditBacklogIsThePendingCountOfTheOutbox() {
        Long pending = jdbc.sql("select count(*) from audit_outbox where delivered_at is null")
                .query(Long.class)
                .single();

        assertThat(backlog.pending()).isEqualTo(pending);
    }

    /** BR6.6: names of ACTIVE and SUSPENDED tenants, unknown ids absent, never a person. */
    @Test
    void theTenantNamesComeForActiveAndSuspendedTenantsOnly() {
        TestAccounts.Seeded active = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        TestAccounts.Seeded suspended = TestAccounts.seed(jdbc, SyntheticIds.mint(), "SUSPENDED", "ACTIVE");
        UUID unknown = UUID.randomUUID();

        Map<UUID, String> names = identity.tenantNames(
                List.of(active.tenantId(), suspended.tenantId(), unknown, active.tenantId()), admin());

        assertThat(names)
                .containsOnlyKeys(active.tenantId(), suspended.tenantId())
                .containsValue("Test tenant");
    }

    /** BR2.7: the "changed by" of the kill switch is the masked wallet (4 + 4), never the full address. */
    @Test
    void theAdministratorWalletIsMasked() {
        String address = SyntheticIds.mint();
        TestAccounts.Seeded account = TestAccounts.betaUser(jdbc, address);

        String masked =
                identity.maskedAdministratorWallet(account.userId(), admin()).orElseThrow();

        assertThat(masked)
                .hasSize(9)
                .startsWith(address.substring(0, 4))
                .endsWith(address.substring(address.length() - 4));
        assertThat(masked).doesNotContain(address);
        assertThat(identity.maskedAdministratorWallet(UUID.randomUUID(), admin()))
                .isEmpty();
    }

    /** C17 rule: the additions refuse a guest principal like every other operation of the interface. */
    @Test
    void theIdentityAdditionsRefuseAGuest() {
        Principal guest = newPerson();

        assertThatThrownBy(() -> identity.tenantNames(List.of(guest.tenantId()), guest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("administrator");
        assertThatThrownBy(() -> identity.maskedAdministratorWallet(guest.userId(), guest))
                .isInstanceOf(ApiException.class);
    }

    private static Principal admin() {
        return new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
    }

    private Instant databaseNow() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }
}
