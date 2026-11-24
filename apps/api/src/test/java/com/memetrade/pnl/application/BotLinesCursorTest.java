package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.pnl.api.RealPnlLineOrigin;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 3, WRITTEN BEFORE THE CODE (BR4.3, NFR1.11, NFR2.3, NFR3.7; performance-design.md
 * "Forma do cursor"): the cursor of C5 is {@code base64url} without padding of
 * {@code 0x01 || botId (16 bytes) || SHA-256("pnl-line:" + idempotencyKey + "|" + origin)[0..16)}, 44 characters. It is
 * refused when its length is not 44, a character is outside the alphabet, the version is not {@code 0x01} or the bot is
 * not the one of the path, and the refusal never echoes the text. The cursor carries no signature, slot, mint nor
 * address.
 */
class BotLinesCursorTest {

    private static final UUID BOT = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final String KEY = "6c4a7b0e-1d2f-4a3b-9c8d-7e6f5a4b3c2d:SWAP_FILL_OUT";
    private static final String SIGNATURE = "5wHu1qwD7q4Hk3yWkJm8sR2tP9vXc6aBnZr4LfGhJkQmYtE1oPsUdVwXy7zA9bCd";

    @Test
    void theCursorIsTheVersionTheBotAndTheDigestOfTheIdentityIn44Characters() throws Exception {
        String cursor = BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.BOT);

        assertThat(cursor).hasSize(44).matches("^[A-Za-z0-9_-]{44}$");
        byte[] raw = Base64.getUrlDecoder().decode(cursor);
        assertThat(raw).hasSize(33);
        assertThat(raw[0]).isEqualTo((byte) 0x01);
        ByteBuffer bot = ByteBuffer.wrap(raw, 1, 16);
        assertThat(new UUID(bot.getLong(), bot.getLong())).isEqualTo(BOT);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("pnl-line:" + KEY + "|BOT").getBytes(StandardCharsets.UTF_8));
        assertThat(Arrays.copyOfRange(raw, 17, 33)).isEqualTo(Arrays.copyOf(digest, 16));
    }

    @Test
    void theOriginIsPartOfTheIdentity() {
        assertThat(BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.LIQUIDATION))
                .isNotEqualTo(BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.BOT));
    }

    @Test
    void aCursorIsParsedBackToItsAnchor() {
        String cursor = BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.SOLD_BY_OTHER_BOT);

        BotLinesCursor.Anchor anchor = BotLinesCursor.parse(cursor, BOT);

        assertThat(anchor.botId()).isEqualTo(BOT);
        assertThat(anchor.matches(KEY, RealPnlLineOrigin.SOLD_BY_OTHER_BOT)).isTrue();
        assertThat(anchor.matches(KEY, RealPnlLineOrigin.BOT)).isFalse();
        assertThat(anchor.matches(KEY + "x", RealPnlLineOrigin.SOLD_BY_OTHER_BOT))
                .isFalse();
    }

    @Test
    void aCursorOfAnotherLengthIsRefusedWithoutEchoingIt() {
        String cursor = BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.BOT);

        assertRefused(cursor.substring(0, 43));
        assertRefused(cursor + "A");
        assertRefused("");
    }

    @Test
    void aCharacterOutsideTheAlphabetIsRefused() {
        String cursor = BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.BOT);

        assertRefused("+" + cursor.substring(1));
        assertRefused(cursor.substring(0, 43) + "=");
        assertRefused(cursor.substring(0, 20) + "/" + cursor.substring(21));
        assertRefused(cursor.substring(0, 20) + " " + cursor.substring(21));
    }

    @Test
    void anotherVersionIsRefused() {
        byte[] raw = Base64.getUrlDecoder().decode(BotLinesCursor.encode(BOT, KEY, RealPnlLineOrigin.BOT));
        raw[0] = 0x02;

        assertRefused(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
    }

    @Test
    void theCursorOfAnotherBotIsRefused() {
        String cursor = BotLinesCursor.encode(UUID.randomUUID(), KEY, RealPnlLineOrigin.BOT);

        assertRefused(cursor);
    }

    @Test
    void theCursorCarriesNoSignatureSlotMintNorAddress() {
        String key = SIGNATURE + ":SWAP_FILL_IN";

        String cursor = BotLinesCursor.encode(BOT, key, RealPnlLineOrigin.BOT);
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1);

        assertThat(cursor).doesNotContain(SIGNATURE.substring(0, 8));
        assertThat(decoded).doesNotContain(SIGNATURE.substring(0, 8)).doesNotContain("SWAP");
    }

    private static void assertRefused(String text) {
        assertThatThrownBy(() -> BotLinesCursor.parse(text, BOT))
                .isInstanceOf(BotLinesCursor.Malformed.class)
                .satisfies(failure -> {
                    if (!text.isEmpty()) {
                        assertThat(failure.getMessage()).doesNotContain(text);
                    }
                });
    }
}
