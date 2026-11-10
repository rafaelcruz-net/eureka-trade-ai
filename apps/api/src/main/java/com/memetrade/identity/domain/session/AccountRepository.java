package com.memetrade.identity.domain.session;

import java.util.Optional;
import java.util.UUID;

/** Read port over tenant, app_user and wallet (identity module tables). */
public interface AccountRepository {

    Optional<Account> findByWallet(String walletAddress);

    /**
     * u5-anonymization (BR5.6, NFR5.6): locks the account with a SHARED lock until the end of the login transaction and
     * tells whether it is still ACTIVE. Called after {@link #findByWallet(String)} and before the session is opened, so
     * an anonymization (which locks the account exclusively) either finished before - and the login sees it - or waits
     * for the login and then revokes its new session.
     */
    boolean lockActiveForSession(UUID userId);
}
