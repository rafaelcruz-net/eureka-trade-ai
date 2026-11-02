package com.memetrade.marketdata.infra;

import java.time.Duration;

/**
 * Wait between two attempts of a source call (reliability-design.md). Injectable so the tests never wait for real;
 * production uses {@link #REAL}.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper REAL = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
