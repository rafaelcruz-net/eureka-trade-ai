package com.memetrade.identity.domain.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 6 (NFR2.2): the opaque cursor round-trips and anything else is 400 on {@code cursor}. */
@Tag("u3")
class InviteCursorTest {

    @Test
    void aCursorRoundTripsWithMicrosecondsAndTheId() {
        InviteCursor cursor = new InviteCursor(Instant.parse("2026-10-28T12:00:00.123456Z"), UUID.randomUUID());

        assertThat(InviteCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void anythingThatIsNotACursorOfThisListIs400OnCursor() {
        String noSeparator = Base64.getUrlEncoder().encodeToString("abc".getBytes(StandardCharsets.UTF_8));
        String badInstant = Base64.getUrlEncoder()
                .encodeToString(("yesterday|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        String badId = Base64.getUrlEncoder().encodeToString("2026-10-28T12:00:00Z|x".getBytes(StandardCharsets.UTF_8));

        for (String value : new String[] {"%%%", noSeparator, badInstant, badId, ""}) {
            assertThatThrownBy(() -> InviteCursor.decode(value))
                    .as(value)
                    .isInstanceOfSatisfying(ApiException.class, problem -> {
                        assertThat(problem.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(problem.fields()).extracting("field").containsExactly("cursor");
                    });
        }
    }
}
