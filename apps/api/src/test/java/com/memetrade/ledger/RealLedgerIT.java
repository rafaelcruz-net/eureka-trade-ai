package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 13, written before the code (BR3.5, BR6.3, NFR2.1, team.md mandatory tests "ledger sem edição", "separação
 * entre REAL e PAPER" and "deduplicação"): the REAL ledger is append-only for every login, one entry per idempotency
 * key, never read by the PAPER ledger, and the balance of another tenant is never visible.
 */
class RealLedgerIT extends AbstractPostgresIT {

    @Autowired
    private RealLedger ledger;

    private record Wallet(UUID tenantId, UUID userId, UUID botWalletId) {}

    private Wallet wallet() {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botWalletId = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        return new Wallet(person.tenantId(), person.userId(), botWalletId);
    }

    private static RealEntry entry(Wallet wallet, RealEntryKind kind, long lamports, String key) {
        return new RealEntry(wallet.tenantId(), wallet.botWalletId(), kind, lamports, null, key, key);
    }

    @Test
    void anEntryIsRecordedOncePerIdempotencyKey() {
        Wallet wallet = wallet();

        boolean first = ledger.record(entry(wallet, RealEntryKind.DEPOSIT, 1_000_000_000L, "dep:sigA"));
        boolean again = ledger.record(entry(wallet, RealEntryKind.DEPOSIT, 1_000_000_000L, "dep:sigA"));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(ledger.balance(wallet.tenantId(), wallet.botWalletId()).totalLamports())
                .isEqualTo(1_000_000_000L);
    }

    @Test
    void theBalanceFollowsTheSignConventionInTheDatabase() {
        Wallet wallet = wallet();
        ledger.record(entry(wallet, RealEntryKind.DEPOSIT, 2_000_000_000L, "dep:sigB"));
        ledger.record(entry(wallet, RealEntryKind.RESERVE, 500_005_000L, "wd:1:reserve"));

        var balance = ledger.balance(wallet.tenantId(), wallet.botWalletId());

        assertThat(balance.reservedLamports()).isEqualTo(500_005_000L);
        assertThat(balance.freeLamports()).isEqualTo(1_499_995_000L);
    }

    @Test
    void updateAndDeleteOfARealEntryFailForEveryLogin() {
        Wallet wallet = wallet();
        ledger.record(entry(wallet, RealEntryKind.DEPOSIT, 7L, "dep:sigC"));

        for (JdbcClient login : new JdbcClient[] {jdbc, connect(OWNER)}) {
            assertThatThrownBy(() -> login.sql("update real_ledger_entry set lamports = 8 where reference = 'dep:sigC'")
                            .update())
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> login.sql("delete from real_ledger_entry where reference = 'dep:sigC'")
                            .update())
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void theDatabaseRefusesAnEntryAgainstTheSignConvention() {
        Wallet wallet = wallet();

        assertThatThrownBy(() -> jdbc.sql("""
                                insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                                  reference, idempotency_key, created_at)
                                values (gen_random_uuid(), :tenant, :wallet, 'DEPOSIT', -1, 'x', 'x', now())""")
                        .param("tenant", wallet.tenantId())
                        .param("wallet", wallet.botWalletId())
                        .update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void realEntriesNeverReachThePaperLedger() {
        Wallet wallet = wallet();
        long paperBefore = jdbc.sql("select count(*) from ledger_entry where owner_user_id = :user")
                .param("user", wallet.userId())
                .query(Long.class)
                .single();

        ledger.record(entry(wallet, RealEntryKind.DEPOSIT, 9L, "dep:sigD"));

        assertThat(jdbc.sql("select count(*) from ledger_entry where owner_user_id = :user")
                        .param("user", wallet.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(paperBefore);
        assertThat(jdbc.sql("select distinct mode from real_ledger_entry where bot_wallet_id = :w")
                        .param("w", wallet.botWalletId())
                        .query(String.class)
                        .list())
                .containsExactly("REAL");
    }

    @Test
    void theBalanceOfAnotherTenantIsNeverVisible() {
        Wallet mine = wallet();
        Wallet other = wallet();
        ledger.record(entry(other, RealEntryKind.DEPOSIT, 11L, "dep:sigE"));

        assertThat(ledger.balance(mine.tenantId(), other.botWalletId()).totalLamports())
                .isZero();
    }
}
