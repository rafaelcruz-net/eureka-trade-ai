package com.memetrade.identity.domain.realterms;

import java.util.Optional;
import java.util.UUID;

/** The wallet verified at login of a person of the tenant (never another tenant's). */
@FunctionalInterface
public interface MainWalletLookup {

    Optional<String> mainWallet(UUID tenantId, UUID userId);
}
