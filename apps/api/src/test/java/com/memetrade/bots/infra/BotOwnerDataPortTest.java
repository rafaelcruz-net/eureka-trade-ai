package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.testsupport.RecordingJdbc;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u4-data-requests (J2, BR4.8): the count of the bots of a person filters by the tenant AND the owner, reads
 * only, and never returns a negative or a null. The real query runs in {@code AdminDataRequestIT}.
 */
@Tag("u4-data-requests")
class BotOwnerDataPortTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Test
    void theCountComesFromOneQueryByTenantAndOwner() {
        RecordingJdbc jdbc = new RecordingJdbc(3L);
        BotOwnerDataPort port = new JdbcBotOwnerDataPort(jdbc.client());

        assertThat(port.countOwnerBots(TENANT, OWNER)).isEqualTo(3L);
        assertThat(jdbc.statements()).singleElement().satisfies(sql -> assertThat(sql)
                .startsWith("select count(*) from bot ")
                .contains("tenant_id = :tenant")
                .contains("owner_user_id = :owner"));
        assertThat(jdbc.parameters()).containsEntry("tenant", TENANT).containsEntry("owner", OWNER);
    }

    @Test
    void aPersonWithoutBotsCountsZero() {
        assertThat(new JdbcBotOwnerDataPort(new RecordingJdbc(0L).client()).countOwnerBots(TENANT, OWNER))
                .isZero();
    }

    @Test
    void aMissingCountIsZeroNeverNull() {
        assertThat(new JdbcBotOwnerDataPort(new RecordingJdbc(null).client()).countOwnerBots(TENANT, OWNER))
                .isZero();
    }

    @Test
    void theQueryNeverWrites() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);

        new JdbcBotOwnerDataPort(jdbc.client()).countOwnerBots(TENANT, OWNER);

        assertThat(jdbc.statements().getFirst().toLowerCase())
                .doesNotContain("update")
                .doesNotContain("delete")
                .doesNotContain("insert");
    }

    @Test
    void aMissingTenantOrOwnerIsRefusedBeforeAnyQuery() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);
        JdbcBotOwnerDataPort port = new JdbcBotOwnerDataPort(jdbc.client());

        assertThatThrownBy(() -> port.countOwnerBots(null, OWNER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> port.countOwnerBots(TENANT, null)).isInstanceOf(NullPointerException.class);
        assertThat(jdbc.statements()).isEmpty();
    }
}
