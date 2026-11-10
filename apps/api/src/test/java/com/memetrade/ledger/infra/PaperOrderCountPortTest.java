package com.memetrade.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.PaperOrderCountPort;
import com.memetrade.testsupport.RecordingJdbc;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u4-data-requests (J4, BR4.8): the count of the PAPER orders of a person filters by the owner, the tenant
 * AND the PAPER mode (REAL and PAPER never mix, project.md), and nothing in the ledger changes. The real query runs in
 * {@code AdminDataRequestIT}.
 */
@Tag("u4-data-requests")
class PaperOrderCountPortTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    @Test
    void theCountComesFromOneQueryByOwnerTenantAndPaper() {
        RecordingJdbc jdbc = new RecordingJdbc(7L);
        PaperOrderCountPort port = new JdbcPaperOrderCountPort(jdbc.client());

        assertThat(port.countPaperOrders(TENANT, OWNER)).isEqualTo(7L);
        assertThat(jdbc.statements()).singleElement().satisfies(sql -> assertThat(sql)
                .startsWith("select count(*) from paper_order ")
                .contains("owner_user_id = :owner")
                .contains("tenant_id = :tenant")
                .contains("mode = 'PAPER'"));
        assertThat(jdbc.parameters()).containsEntry("tenant", TENANT).containsEntry("owner", OWNER);
    }

    @Test
    void theLedgerIsNeverWritten() {
        RecordingJdbc jdbc = new RecordingJdbc(0L);

        new JdbcPaperOrderCountPort(jdbc.client()).countPaperOrders(TENANT, OWNER);

        assertThat(jdbc.statements().getFirst().toLowerCase())
                .doesNotContain("update")
                .doesNotContain("delete")
                .doesNotContain("insert")
                .doesNotContain("ledger_entry");
    }

    @Test
    void aMissingCountIsZero() {
        assertThat(new JdbcPaperOrderCountPort(new RecordingJdbc(null).client()).countPaperOrders(TENANT, OWNER))
                .isZero();
    }

    @Test
    void aMissingTenantOrOwnerIsRefusedBeforeAnyQuery() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);
        JdbcPaperOrderCountPort port = new JdbcPaperOrderCountPort(jdbc.client());

        assertThatThrownBy(() -> port.countPaperOrders(null, OWNER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> port.countPaperOrders(TENANT, null)).isInstanceOf(NullPointerException.class);
        assertThat(jdbc.statements()).isEmpty();
    }
}
