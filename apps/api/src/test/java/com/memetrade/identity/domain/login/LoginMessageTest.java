package com.memetrade.identity.domain.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.SyntheticIds;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class LoginMessageTest {

    private static final Instant ISSUED = Instant.parse("2026-10-28T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-28T12:05:00Z");

    private final String address = SyntheticIds.mint();
    private final LoginMessage message = new LoginMessage(
            "localhost:3000",
            address,
            "http://localhost:3000",
            "solana:mainnet",
            "AAAAAAAAAAAAAAAAAAAAAA",
            ISSUED,
            EXPIRES);

    @Test
    void rendersTheExactFixedLineTemplateOfBr113() {
        assertThat(message.render())
                .isEqualTo("localhost:3000 pede que você entre com a sua conta Solana\n"
                        + address + "\n"
                        + "Esta assinatura serve só para entrar e não autoriza operações.\n"
                        + "URI: http://localhost:3000\n"
                        + "Chain: solana:mainnet\n"
                        + "Nonce: AAAAAAAAAAAAAAAAAAAAAA\n"
                        + "Issued At: 2026-10-28T12:00:00Z\n"
                        + "Expiration Time: 2026-10-28T12:05:00Z");
    }

    @Test
    void parsesARenderedMessageBackToTheSameFields() {
        assertThat(LoginMessage.parse(message.render())).contains(message);
    }

    @Test
    void refusesMessagesWithWrongLineCountOrStatement() {
        String rendered = message.render();
        assertThat(LoginMessage.parse(rendered + "\nextra")).isEmpty();
        assertThat(LoginMessage.parse(rendered.replace("não autoriza", "autoriza")))
                .isEmpty();
        assertThat(LoginMessage.parse(rendered.replace("\n", "\r\n"))).isEmpty();
        assertThat(LoginMessage.parse(null)).isEmpty();
        assertThat(LoginMessage.parse("")).isEmpty();
    }

    @Test
    void refusesAMissingOrInvalidNonceLine() {
        String rendered = message.render();
        assertThat(LoginMessage.parse(rendered.replace("Nonce: AAAAAAAAAAAAAAAAAAAAAA", "Nonce: ")))
                .isEmpty();
        assertThat(LoginMessage.parse(rendered.replace("Nonce: ", "Nounce: "))).isEmpty();
        assertThat(LoginMessage.parse(rendered.replace("AAAAAAAAAAAAAAAAAAAAAA", "short")))
                .isEmpty();
    }

    @Test
    void refusesInvalidAddressOrInstants() {
        String rendered = message.render();
        assertThat(LoginMessage.parse(rendered.replace(address, "not-an-address")))
                .isEmpty();
        assertThat(LoginMessage.parse(rendered.replace("2026-10-28T12:05:00Z", "tomorrow")))
                .isEmpty();
    }
}
