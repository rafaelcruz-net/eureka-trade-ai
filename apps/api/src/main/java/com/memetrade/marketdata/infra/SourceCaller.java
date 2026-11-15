package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceException;
import com.memetrade.marketdata.domain.SourceFetcher;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.ObjIntConsumer;

/**
 * Calls a source with a time limit and new attempts (reliability-design.md, NFR10.3), one {@link Retry} and one
 * {@link TimeLimiter} per source so a failure of one never affects the other.
 *
 * <ul>
 *   <li>time limit 5 s per call, run on the {@code market-source-calls} executor so a slow call is cancelled;
 *   <li>up to 2 new attempts (3 calls), waiting 1 s then 2 s with a random variation of up to 20% (fixed seed in the
 *       tests); the wait goes through the injectable {@link Sleeper};
 *   <li>retried: time-outs, network errors, 5xx and 429 answers and any unexpected exception; never retried: an
 *       invalid payload and the other 4xx answers ({@link MarketSourceException#retryable()}).
 * </ul>
 *
 * Resilience4j decides (attempts, interval, which failures); the wait itself is the {@link Sleeper}.
 */
public class SourceCaller implements SourceFetcher {

    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    public static final int MAX_ATTEMPTS = 3;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    /** Random variation of the wait, in percent either way (no floating point in the market module). */
    static final int JITTER_PERCENT = 20;

    /**
     * U2 (reliability-design.md "Dois níveis de proteção", infrastructure-specification.md "Prazo da coingecko"): the
     * real source gets 25 s for the whole round and ONE attempt; it retries each call itself, so retries never pile
     * up. Its own internal deadline (18 s) ends the round first; this limit only protects.
     */
    public static final CallPolicy COINGECKO_POLICY = new CallPolicy(Duration.ofSeconds(25), 1);

    /** Time limit of one fetch and number of attempts of a source. */
    public record CallPolicy(Duration timeout, int maxAttempts) {

        public CallPolicy {
            if (timeout == null || timeout.isNegative() || timeout.isZero() || maxAttempts < 1) {
                throw new IllegalArgumentException("A positive time limit and at least one attempt");
            }
        }
    }

    private final ExecutorService executor;
    private final Sleeper sleeper;
    private final Random jitter;
    private final CallPolicy defaultPolicy;
    private final Map<String, CallPolicy> policies;
    private final ObjIntConsumer<String> onRetry;
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final Map<String, TimeLimiter> limiters = new ConcurrentHashMap<>();

    /**
     * @param onRetry called with the source id once per new attempt (metric {@code market_collection_retries_total})
     */
    public SourceCaller(
            ExecutorService executor,
            Sleeper sleeper,
            Random jitter,
            Duration timeout,
            ObjIntConsumer<String> onRetry) {
        this(executor, sleeper, jitter, timeout, Map.of(), onRetry);
    }

    /**
     * @param timeout time limit of the sources without a policy of their own (sim-a and sim-b: 5 s, 3 attempts)
     * @param policies policy per source id (U2: {@code coingecko} with {@link #COINGECKO_POLICY})
     */
    public SourceCaller(
            ExecutorService executor,
            Sleeper sleeper,
            Random jitter,
            Duration timeout,
            Map<String, CallPolicy> policies,
            ObjIntConsumer<String> onRetry) {
        this.executor = executor;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.defaultPolicy = new CallPolicy(timeout, MAX_ATTEMPTS);
        this.policies = Map.copyOf(policies);
        this.onRetry = onRetry;
    }

    /** The policy of a source: its own, or the default one. */
    public CallPolicy policy(String sourceId) {
        return policies.getOrDefault(sourceId, defaultPolicy);
    }

    @Override
    public FetchOutcome fetch(MarketSourceAdapter adapter, FetchRequest request) {
        String sourceId = adapter.source().sourceId();
        Retry.AsyncContext<FetchResult> context = retry(sourceId).asyncContext();
        TimeLimiter limiter = limiters.computeIfAbsent(
                sourceId,
                id -> TimeLimiter.of(
                        "market-source-" + id,
                        TimeLimiterConfig.custom()
                                .timeoutDuration(policy(id).timeout())
                                .cancelRunningFuture(true)
                                .build()));
        int done = 0;
        FailureCategory lastCause = null;
        while (true) {
            try {
                FetchResult result = limiter.executeFutureSupplier(() -> executor.submit(() -> adapter.fetch(request)));
                context.onComplete();
                return new Fetched(result, done, Optional.ofNullable(lastCause));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Failed(
                        FailureCategory.TIMEOUT, interrupted.getClass().getSimpleName(), done);
            } catch (Exception failure) {
                Throwable cause = unwrap(failure);
                FailureCategory category = category(cause);
                long waitMillis = context.onError(cause);
                if (waitMillis < 0) {
                    return new Failed(category, cause.getClass().getSimpleName(), done);
                }
                lastCause = category;
                done++;
                onRetry.accept(sourceId, done);
                if (!sleep(waitMillis)) {
                    return new Failed(FailureCategory.TIMEOUT, "InterruptedException", done);
                }
            }
        }
    }

    /** Wait before the attempt {@code attempt + 1}: 1 s, 2 s, ... with a variation of up to 20% either way. */
    long interval(int attempt) {
        long base = FIRST_WAIT.toMillis() << Math.max(0, attempt - 1);
        int percent;
        synchronized (jitter) {
            percent = jitter.nextInt(2 * JITTER_PERCENT + 1) - JITTER_PERCENT;
        }
        return base * (100 + percent) / 100;
    }

    static FailureCategory category(Throwable failure) {
        if (failure instanceof TimeoutException) {
            return FailureCategory.TIMEOUT;
        }
        if (failure instanceof MarketSourceException source) {
            return source.category();
        }
        return FailureCategory.ERROR_RESPONSE;
    }

    static boolean retryable(Throwable failure) {
        return !(failure instanceof MarketSourceException source) || source.retryable();
    }

    private Retry retry(String sourceId) {
        IntervalFunction intervals = this::interval;
        return retries.computeIfAbsent(
                sourceId,
                id -> Retry.of(
                        "market-source-" + id,
                        RetryConfig.custom()
                                .maxAttempts(policy(id).maxAttempts())
                                .intervalFunction(intervals)
                                .retryOnException(SourceCaller::retryable)
                                .failAfterMaxAttempts(false)
                                .build()));
    }

    private boolean sleep(long millis) {
        try {
            sleeper.sleep(Duration.ofMillis(millis));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
