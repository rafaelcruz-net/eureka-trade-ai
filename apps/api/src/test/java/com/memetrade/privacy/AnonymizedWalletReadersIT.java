package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.account.AccountDetailsRepository;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import com.memetrade.identity.domain.session.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 4 of u5-anonymization (logical-components.md "Leitores de wallet"; BR5.7; NFR2.2): every reader of
 * {@code wallet.address} and {@code wallet.provider} handles the wallet without address of an anonymized account, and the
 * login re-reads the status under its shared lock.
 */
class AnonymizedWalletReadersIT extends AbstractAnonymizationIT {

    @Autowired
    private AccountDetailsRepository accountDetails;

    @Autowired
    private IdentityDirectory directory;

    @Autowired
    private PersonDataPort people;

    @Autowired
    private AccountAnonymizationPort anonymizations;

    @Autowired
    private AccountRepository accounts;

    @Test
    void theAccountScreenOfAnAnonymizedAccountIsNeverRead() throws Exception {
        Deletion deletion = deletionInProgress();
        UUID session = jdbc.sql("select session_id from user_session where user_id = :u")
                .param("u", deletion.userId())
                .query(UUID.class)
                .single();
        assertThat(accountDetails.find(deletion.userId(), deletion.tenantId(), session))
                .isPresent();

        anonymize(deletion);

        assertThat(accountDetails.find(deletion.userId(), deletion.tenantId(), session))
                .isEmpty();
    }

    @Test
    void theDirectoryHasNoWalletForAnAnonymizedAccount() throws Exception {
        Deletion deletion = deletionInProgress();
        assertThat(directory.walletOf(deletion.userId())).contains(deletion.wallet());

        anonymize(deletion);

        assertThat(directory.walletOf(deletion.userId())).isEmpty();
    }

    @Test
    void thePersonDataOfJ1IsEmptyAndTheAccountIsAnonymized() throws Exception {
        Deletion deletion = deletionInProgress();

        anonymize(deletion);

        assertThat(people.personSummary(deletion.tenantId(), deletion.userId())).isEmpty();
        assertThat(people.findAccount(deletion.userId()))
                .get()
                .extracting(PersonDataPort.AccountRef::state)
                .isEqualTo(PersonDataPort.AccountState.ANONYMIZED);
        assertThat(people.verifyAccountSignature(
                        deletion.userId(),
                        PersonDataPort.VERIFICATION_FIRST_LINE + "\nmensagem sintética",
                        deletion.guest()
                                .wallet()
                                .sign(PersonDataPort.VERIFICATION_FIRST_LINE + "\nmensagem sintética")))
                .isFalse();
    }

    @Test
    void theTargetAndTheAnonymizedAmongOfJ1FollowTheStatus() throws Exception {
        Deletion deletion = deletionInProgress();
        WalletGuest other = newWalletGuest();
        assertThat(anonymizations.target(deletion.userId())).get().satisfies(target -> {
            assertThat(target.role()).isEqualTo(Role.BETA_USER);
            assertThat(target.anonymized()).isFalse();
            assertThat(target.anonymizedAt()).isEmpty();
        });

        anonymize(deletion);

        assertThat(anonymizations.target(deletion.userId()).orElseThrow().anonymizedAt())
                .contains(clock.instant());
        assertThat(anonymizations.anonymizedAmong(
                        List.of(deletion.userId(), other.principal().userId())))
                .containsExactly(deletion.userId());
        assertThat(anonymizations.anonymizedAmong(List.of())).isEmpty();
        assertThat(anonymizations.target(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theLoginLockReadsTheStatusOfTheAccount() throws Exception {
        Deletion deletion = deletionInProgress();

        Boolean before = transaction().execute(status -> accounts.lockActiveForSession(deletion.userId()));
        anonymize(deletion);
        Boolean after = transaction().execute(status -> accounts.lockActiveForSession(deletion.userId()));

        assertThat(before).isTrue();
        assertThat(after).isFalse();
        Boolean unknown = transaction().execute(status -> accounts.lockActiveForSession(UUID.randomUUID()));
        assertThat(unknown).isFalse();
        assertThat(accounts.findByWallet(deletion.wallet())).isEmpty();
    }
}
