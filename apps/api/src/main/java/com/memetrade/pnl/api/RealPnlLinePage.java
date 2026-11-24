package com.memetrade.pnl.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U3, C10 {@code botLines} (BR4.2, BR4.3): one page of the lines of a REAL bot, newest first in
 * network order. {@code nextCursor} is the cursor of the last line of the page when older lines remain, empty at the
 * end. On the closed REAL list of NEVER_SUM_MODES.
 */
public record RealPnlLinePage(List<RealPnlLineItem> items, Optional<String> nextCursor) {

    public RealPnlLinePage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
