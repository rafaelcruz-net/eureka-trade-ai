package com.memetrade.testsupport;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceException;
import com.memetrade.marketdata.api.SourceRef;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Test adapter that delays, fails or answers invalid data ON COMMAND, then delegates to a recorded fixture
 * (unit-test-instructions.md, "Fontes"). Each scripted step applies to one call, in order; without a script every call
 * answers the fixture. No network access.
 */
public final class ScriptedMarketSource implements MarketSourceAdapter {

    private final MarketSourceAdapter delegate;
    private final Deque<Step> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedMarketSource(MarketSourceAdapter delegate) {
        this.delegate = delegate;
    }

    public static ScriptedMarketSource of(String fixtureSourceId) {
        return new ScriptedMarketSource(FixtureMarketSource.load(fixtureSourceId));
    }

    /** The next call throws a retryable failure of {@code category}. */
    public synchronized ScriptedMarketSource thenFail(FailureCategory category) {
        script.addLast(new Step(new MarketSourceException(category, true, "scripted failure"), null, null));
        return this;
    }

    /** The next call throws {@code failure} as is. */
    public synchronized ScriptedMarketSource thenThrow(RuntimeException failure) {
        script.addLast(new Step(failure, null, null));
        return this;
    }

    /** The next call waits {@code delay} (real time, keep it short) before answering the fixture. */
    public synchronized ScriptedMarketSource thenDelay(Duration delay) {
        script.addLast(new Step(null, delay, null));
        return this;
    }

    /** The next call answers the fixture changed by {@code change} (for example invalid tokens). */
    public synchronized ScriptedMarketSource thenAnswer(UnaryOperator<FetchResult> change) {
        script.addLast(new Step(null, null, change));
        return this;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public SourceRef source() {
        return delegate.source();
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        calls.incrementAndGet();
        Step step;
        synchronized (this) {
            step = script.pollFirst();
        }
        if (step == null) {
            return delegate.fetch(request);
        }
        if (step.failure() != null) {
            throw step.failure();
        }
        if (step.delay() != null) {
            try {
                Thread.sleep(step.delay().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MarketSourceException(FailureCategory.TIMEOUT, true, "interrupted while delayed");
            }
        }
        FetchResult answer = delegate.fetch(request);
        return step.change() == null ? answer : step.change().apply(answer);
    }

    private record Step(RuntimeException failure, Duration delay, UnaryOperator<FetchResult> change) {}
}
