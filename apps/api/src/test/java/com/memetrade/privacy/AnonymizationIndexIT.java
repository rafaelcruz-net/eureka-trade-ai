package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of u5-anonymization (NFR3.2; performance-design.md): every statement of the anonymization reads and writes by
 * the person or the address through an index. Each plan is taken with {@code enable_seqscan = off}; a sequential scan
 * means a missing index (the one of {@code login_challenge.wallet_address} comes from migration 0015).
 */
class AnonymizationIndexIT extends AbstractAnonymizationIT {

    private final String tenant = UUID.randomUUID().toString();
    private final String person = UUID.randomUUID().toString();

    @Test
    void theBotsOfThePersonAreReadThroughTheOwnerIndex() {
        assertIndexed("select bot_id from bot where tenant_id = '" + tenant + "' and owner_user_id = '" + person
                + "' order by bot_id");
    }

    @Test
    void theSessionsOfThePersonAreReadThroughTheUserIndex() {
        assertIndexed("select session_id from user_session where user_id = '" + person + "' and tenant_id = '" + tenant
                + "' order by session_id");
        assertIndexed("update user_session set state = 'REVOKED' where user_id = '" + person + "' and tenant_id = '"
                + tenant + "' and state = 'ACTIVE'");
    }

    @Test
    void theCommentsOfThePersonAreErasedThroughThePersonIndex() {
        assertIndexed("delete from feedback_entry where tenant_id = '" + tenant + "' and user_id = '" + person + "'");
    }

    @Test
    void theWalletAndTheAccountAreWrittenByTheirKeys() {
        assertIndexed("update wallet set address = null, provider = null where user_id = '" + person + "'");
        assertIndexed("update app_user set status = 'ANONYMIZED' where user_id = '" + person + "' and tenant_id = '"
                + tenant + "'");
    }

    @Test
    void theChallengesOfTheAddressAreDeletedThroughTheNewIndex() {
        String plan = explain("delete from login_challenge where wallet_address = 'So1anaAddressOfTheTestxxxxxxxxxxx'");

        assertThat(plan).doesNotContain("Seq Scan").contains("login_challenge_wallet_idx");
    }

    @Test
    void theStoredChallengeAnswersAreDeletedThroughThePrimaryKey() {
        assertIndexed("delete from idempotency_record where scope in ('wallet:x:auth.challenge',"
                + " 'wallet:x:admin.auth.challenge')");
    }

    private void assertIndexed(String sql) {
        String plan = explain(sql);
        assertThat(plan).as(plan).doesNotContain("Seq Scan");
    }
}
