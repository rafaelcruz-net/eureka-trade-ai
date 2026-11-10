package com.memetrade.feedback.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.testsupport.RecordingJdbc;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u4-data-requests (J3, BR4.8): the count of the comments of a person filters by the tenant AND the person,
 * never reads the text and never writes. The real query runs in {@code AdminDataRequestIT}.
 */
@Tag("u4-data-requests")
class FeedbackOwnerDataPortTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void theCountComesFromOneQueryByTenantAndPerson() {
        RecordingJdbc jdbc = new RecordingJdbc(4L);
        FeedbackOwnerDataPort port = new JdbcFeedbackOwnerDataPort(jdbc.client());

        assertThat(port.countByUser(TENANT, USER)).isEqualTo(4L);
        assertThat(jdbc.statements()).singleElement().satisfies(sql -> assertThat(sql)
                .startsWith("select count(*) from feedback_entry ")
                .contains("user_id = :user")
                .contains("tenant_id = :tenant"));
        assertThat(jdbc.parameters()).containsEntry("tenant", TENANT).containsEntry("user", USER);
    }

    @Test
    void theTextOfACommentIsNeverRead() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);

        new JdbcFeedbackOwnerDataPort(jdbc.client()).countByUser(TENANT, USER);

        assertThat(jdbc.statements().getFirst()).doesNotContain("text");
    }

    @Test
    void aMissingCountIsZero() {
        assertThat(new JdbcFeedbackOwnerDataPort(new RecordingJdbc(null).client()).countByUser(TENANT, USER))
                .isZero();
    }

    @Test
    void aMissingTenantOrPersonIsRefusedBeforeAnyQuery() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);
        JdbcFeedbackOwnerDataPort port = new JdbcFeedbackOwnerDataPort(jdbc.client());

        assertThatThrownBy(() -> port.countByUser(null, USER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> port.countByUser(TENANT, null)).isInstanceOf(NullPointerException.class);
        assertThat(jdbc.statements()).isEmpty();
    }
}
