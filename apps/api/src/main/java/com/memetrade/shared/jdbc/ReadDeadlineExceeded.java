package com.memetrade.shared.jdbc;

import java.time.Duration;

/**
 * 261121-paper-real-unico U2 (NFR1.12, performance-design.md): a streamed read passed its hard deadline, found at a row
 * of the stream before it reached the consumer. Classified as a timeout, never as a success (outcome TIMEOUT).
 */
public class ReadDeadlineExceeded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReadDeadlineExceeded(Duration budget) {
        super("The read passed its deadline of " + budget.toMillis() + " ms");
    }
}
