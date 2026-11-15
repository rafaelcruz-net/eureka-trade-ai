package com.memetrade.marketdata.api;

import java.util.Objects;

/**
 * U2 (BR1.5, BR1.8, entities.md Attribution): the text and the HTTPS link the provider of the active real source
 * requires on screen, for example "Dados: CoinGecko". The link is text here, so the public interface never depends on
 * the network classes.
 */
public record Attribution(String text, String url) {

    public Attribution {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(url, "url");
        if (text.isBlank() || !url.startsWith("https://")) {
            throw new IllegalArgumentException("An attribution has a text and an https link");
        }
    }
}
