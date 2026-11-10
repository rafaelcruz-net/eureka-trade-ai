package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.PersonDataPort;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The text a person signs to confirm an EMAIL request (BR4.11, NFR2.3; AC5.6.2, AC5.6.7). Its first line is the fixed
 * {@link PersonDataPort#VERIFICATION_FIRST_LINE}, so it never has the shape of a login message (which starts with the
 * domain of the app); it names the domain, the request, the purpose and the validity in UTC, so it is unique per request
 * and useless anywhere else. It is not a secret: the administrator sends it to the contact outside the system.
 */
public final class VerificationMessage {

    /** The purpose line, in Brazilian Portuguese like the rest of the text the person reads. */
    public static final String PURPOSE = "verificação de pedido de titular";

    private VerificationMessage() {}

    public static String build(String domain, UUID requestId, Instant expiresAt) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (domain.isBlank() || domain.contains("\n")) {
            throw new IllegalArgumentException("The domain of a verification message is one non-empty line");
        }
        return String.join(
                "\n",
                PersonDataPort.VERIFICATION_FIRST_LINE,
                "Domínio: " + domain,
                "Pedido: " + requestId,
                "Finalidade: " + PURPOSE,
                "Válida até: " + expiresAt,
                "Esta assinatura não autoriza login nem operações.");
    }
}
