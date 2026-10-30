package com.memetrade.identity.domain.session;

import java.util.Optional;

/** Read port over tenant, app_user and wallet (identity module tables). */
public interface AccountRepository {

    Optional<Account> findByWallet(String walletAddress);
}
