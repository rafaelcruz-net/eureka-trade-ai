package com.memetrade.botwallet.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" item 2, BR5.4): who set
 * the cap the person sees. The administrator only LOWERS a cap (U5 BR4.1), and each lowering leaves a LOWER_CAP command
 * to the TxSigner with the new value; the person's own changes leave none. So the cap is "ADMIN" exactly while it still
 * equals the value of the most recent lowering by the administrator; any later change by the person makes it "USER".
 */
class BotWalletViewCapSetByTest {

    @Test
    void withoutAnyLoweringByTheAdministratorTheCapIsTheUsers() {
        assertThat(BotWalletViews.capSetBy(2_000_000_000L, Optional.empty())).isEqualTo("USER");
    }

    @Test
    void theCapStillEqualToTheLastLoweringIsTheAdministrators() {
        assertThat(BotWalletViews.capSetBy(500_000_000L, Optional.of(500_000_000L)))
                .isEqualTo("ADMIN");
    }

    @Test
    void aCapChangedByThePersonAfterTheLoweringIsTheUsersAgain() {
        assertThat(BotWalletViews.capSetBy(3_000_000_000L, Optional.of(500_000_000L)))
                .isEqualTo("USER");
        assertThat(BotWalletViews.capSetBy(100_000_000L, Optional.of(500_000_000L)))
                .isEqualTo("USER");
    }
}
