package com.memetrade.identity.domain.invite;

import com.memetrade.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque cursor of the invitation list (BR1.3, NFR2.2): the {@code createdAt} and {@code inviteId} of the last item
 * of the previous page, in base64url. A cursor that does not decode is {@code 400 VALIDATION_FAILED} on
 * {@code cursor}.
 */
public record InviteCursor(Instant createdAt, UUID inviteId) {

    public InviteCursor {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(inviteId, "inviteId");
    }

    public static InviteCursor after(Invite invite) {
        return new InviteCursor(invite.createdAt(), invite.inviteId());
    }

    public String encode() {
        String plain = createdAt + "|" + inviteId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static InviteCursor decode(String value) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            return new InviteCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException notACursor) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.validation("cursor", "Must be a cursor returned by this list.");
    }
}
